from __future__ import annotations

import asyncio
from datetime import datetime, timedelta, timezone
from typing import Any

from sqlalchemy import select

from ..ai.engine import CompanionEngine
from ..ai.prompts import moment_prompt, now_text
from ..models import AiFriend, FutureTask, FutureTaskRun, Moment, Persona, User
from ..services.logger import prune_logs, write_log
from ..services.settings_service import get_settings_map
from ..utils import get_tz, parse_dt, utcnow
from . import push

engine = CompanionEngine()


# --------------------------------------------------------------------- cron
def _match_field(expr: str, value: int, lo: int, hi: int) -> bool:
    for part in expr.split(","):
        part = part.strip()
        if not part:
            continue
        step = 1
        if "/" in part:
            part, step_s = part.split("/", 1)
            try:
                step = max(1, int(step_s))
            except ValueError:
                step = 1
        if part in ("*", "?"):
            start, end = lo, hi
        elif "-" in part:
            a, b = part.split("-", 1)
            try:
                start, end = int(a), int(b)
            except ValueError:
                continue
        else:
            try:
                start = end = int(part)
            except ValueError:
                continue
        if start > end:
            start, end = end, start
        if start <= value <= end and (value - start) % step == 0:
            return True
    return False


def cron_matches(expression: str, dt: datetime) -> bool:
    fields = (expression or "").split()
    if len(fields) != 5:
        return False
    minute, hour, dom, month, dow = fields
    cron_dow = (dt.weekday() + 1) % 7  # Monday=0 -> 1 ... Sunday=6 -> 0
    if not _match_field(minute, dt.minute, 0, 59):
        return False
    if not _match_field(hour, dt.hour, 0, 23):
        return False
    if not _match_field(month, dt.month, 1, 12):
        return False
    dom_ok = _match_field(dom, dt.day, 1, 31)
    dow_ok = _match_field(dow, cron_dow, 0, 6)
    if dom != "*" and dow != "*":
        return dom_ok or dow_ok
    return dom_ok and dow_ok


def _tz(name: str | None):
    return get_tz(name)


def compute_next_run(schedule: dict[str, Any], after: datetime, tz_name: str = "Asia/Shanghai") -> datetime | None:
    kind = str((schedule or {}).get("kind") or "manual")
    now = after or utcnow()
    if kind == "manual":
        return None
    if kind == "once":
        at = parse_dt(schedule.get("at"))
        if at is None:
            return None
        return at if at > now else None
    if kind == "interval":
        minutes = max(1, int(schedule.get("minutes") or 30))
        base = parse_dt(schedule.get("start_at")) or now
        nxt = base + timedelta(minutes=minutes)
        guard = 0
        while nxt <= now and guard < 10000:
            nxt += timedelta(minutes=minutes)
            guard += 1
        return nxt
    if kind == "daily":
        raw = str(schedule.get("time") or "09:00")
        try:
            hh, mm = [int(x) for x in raw.split(":")[:2]]
        except Exception:
            hh, mm = 9, 0
        tz = _tz(tz_name)
        local_now = now.astimezone(tz)
        candidate = local_now.replace(hour=hh, minute=mm, second=0, microsecond=0)
        if candidate <= local_now:
            candidate += timedelta(days=1)
        return candidate.astimezone(timezone.utc)
    if kind == "cron":
        expr = str(schedule.get("expression") or "").strip()
        if len(expr.split()) != 5:
            return None
        tz = _tz(tz_name)
        cursor = now.astimezone(tz).replace(second=0, microsecond=0) + timedelta(minutes=1)
        for _ in range(60 * 24 * 40):
            if cron_matches(expr, cursor):
                return cursor.astimezone(timezone.utc)
            cursor += timedelta(minutes=1)
        return None
    return None


# ------------------------------------------------------------------ runner
async def _resolve_target(session, task: FutureTask):
    user = await session.get(User, task.user_id) if task.user_id else None
    ai = await session.get(AiFriend, task.ai_friend_id) if task.ai_friend_id else None
    if user is None and ai is not None:
        user = await session.get(User, ai.user_id)
    if ai is None and user is not None:
        ai = (
            await session.execute(
                select(AiFriend).where(AiFriend.user_id == user.id, AiFriend.status == "active").order_by(AiFriend.id.asc())
            )
        ).scalars().first()
    return user, ai


def _render_template(template: str, user: User, task: FutureTask, tz_name: str) -> str:
    return (
        (template or "")
        .replace("{user}", user.nickname or user.username)
        .replace("{name}", user.nickname or user.username)
        .replace("{time}", now_text(tz_name))
        .replace("{task}", task.name)
    )


async def execute_task(task_id: int) -> FutureTaskRun | None:
    from ..database import SessionLocal

    if SessionLocal is None:
        return None
    async with SessionLocal() as session:
        task = await session.get(FutureTask, task_id)
        if task is None:
            return None
        run = FutureTaskRun(task_id=task.id, status="running")
        session.add(run)
        await session.commit()
        await session.refresh(run)

        output = ""
        status = "ok"
        try:
            app_settings = await get_settings_map(session)
            tz_name = app_settings.get("timezone", "Asia/Shanghai")
            user, ai = await _resolve_target(session, task)
            if user is None:
                raise RuntimeError("任务未绑定有效用户")
            payload = task.payload or {}

            if task.type == "message":
                if ai is None:
                    raise RuntimeError("任务未绑定 AI 好友")
                conv = await engine.open_conversation(session, ai, user)
                text = str(payload.get("text") or "").strip()
                if text:
                    text = _render_template(text, user, task, tz_name)
                    await engine.store_ai_message(
                        session,
                        conversation=conv,
                        ai_friend=ai,
                        content=text,
                        meta={"task_id": task.id, "scheduled": True},
                    )
                else:
                    state = await engine.ensure_state(session, ai, user, conv.id)
                    msg = await engine.proactive(session, state, force=True)
                    text = msg.content if msg else "（未生成内容）"
                output = text
            elif task.type == "proactive":
                if ai is None:
                    raise RuntimeError("任务未绑定 AI 好友")
                conv = await engine.open_conversation(session, ai, user)
                state = await engine.ensure_state(session, ai, user, conv.id)
                msg = await engine.proactive(session, state, force=True)
                output = msg.content if msg else "（未生成内容）"
            elif task.type == "moment":
                persona = await session.get(Persona, payload.get("persona_id") or (ai.persona_id if ai else 0))
                content = str(payload.get("text") or "").strip()
                if not content:
                    from ..ai import prompts as ai_prompts
                    from ..ai.providers import ProviderError, chat, pick_provider

                    if persona is None:
                        raise RuntimeError("没有可用的角色卡")
                    provider = await pick_provider(session, "chat", user_id=user.id)
                    if provider.is_demo:
                        content = f"今天也照常在想一些小事。{now_text(tz_name)}"
                    else:
                        system = ai_prompts.build_system_prompt(
                            persona=persona,
                            ai_name=ai.name if ai else persona.name,
                            user=user,
                            style_profile=None,
                            style_samples=[],
                            memories=[],
                            now=now_text(tz_name),
                            mode="proactive",
                            tz_name=tz_name,
                        )
                        try:
                            content = await chat(
                                provider,
                                [
                                    {"role": "system", "content": system},
                                    {"role": "user", "content": moment_prompt(persona.name, now_text(tz_name), user.nickname)},
                                ],
                                temperature=0.95,
                                max_tokens=200,
                            )
                        except ProviderError:
                            content = f"{now_text(tz_name)}，随手记一笔。"
                moment = Moment(
                    user_id=user.id,
                    content=content,
                    images=payload.get("images") or [],
                    visibility=payload.get("visibility") or "friends",
                    ai_persona_id=persona.id if persona else None,
                )
                session.add(moment)
                await session.commit()
                await session.refresh(moment)
                await push.manager.send_user(user.id, {"type": "moment", "moment_id": moment.id})
                output = content
            elif task.type == "plugin":
                from .plugin_loader import plugin_manager

                if ai is None:
                    raise RuntimeError("任务未绑定 AI 好友")
                conv = await engine.open_conversation(session, ai, user)
                command = str(payload.get("command") or "").strip()
                result = await plugin_manager.dispatch(
                    text=command, user=user, conversation=conv, ai_friend=ai, sender_name=user.nickname
                )
                output = result.text if result else "（插件无输出）"
            else:
                raise RuntimeError(f"未知任务类型: {task.type}")
        except Exception as exc:
            status = "error"
            output = f"{type(exc).__name__}: {exc}"
            await write_log("error", "task", f"任务「{task.name}」执行失败: {exc}", task_id=task.id)
        finally:
            run.status = status
            run.output = output[:2000]
            run.finished_at = utcnow()
            task.last_run_at = run.started_at
            task.run_count = (task.run_count or 0) + 1
            app_settings = await get_settings_map(session)
            task.next_run_at = compute_next_run(
                task.schedule or {}, run.finished_at, app_settings.get("timezone", "Asia/Shanghai")
            )
            if task.next_run_at is None and str((task.schedule or {}).get("kind")) not in ("manual",):
                task.enabled = False
            await session.commit()
            await session.refresh(run)
        return run


class TaskScheduler:
    def __init__(self) -> None:
        self._stop = asyncio.Event()
        self._task: asyncio.Task | None = None
        self._last_cleanup = datetime.now(timezone.utc) - timedelta(hours=6)

    def start(self) -> None:
        if self._task is None or self._task.done():
            self._stop.clear()
            self._task = asyncio.create_task(self._loop(), name="task-scheduler")

    async def stop(self) -> None:
        self._stop.set()
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except (asyncio.CancelledError, Exception):
                pass
            self._task = None

    async def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                await self.tick()
            except Exception as exc:
                await write_log("error", "scheduler", f"任务调度器异常: {exc}")
            try:
                await asyncio.wait_for(self._stop.wait(), timeout=15)
            except asyncio.TimeoutError:
                pass

    async def tick(self) -> None:
        from ..database import SessionLocal

        if SessionLocal is None:
            return
        async with SessionLocal() as session:
            now = utcnow()
            tasks = (
                await session.execute(
                    select(FutureTask)
                    .where(
                        FutureTask.enabled.is_(True),
                        FutureTask.next_run_at.is_not(None),
                        FutureTask.next_run_at <= now,
                    )
                    .order_by(FutureTask.next_run_at.asc())
                    .limit(10)
                )
            ).scalars().all()
            for task in tasks:
                await execute_task(task.id)

            if datetime.now(timezone.utc) - self._last_cleanup > timedelta(hours=6):
                self._last_cleanup = datetime.now(timezone.utc)
                await prune_logs(30)
                try:
                    from sqlalchemy import delete

                    from ..models import Memory

                    async with SessionLocal() as cleanup:
                        await cleanup.execute(
                            delete(Memory).where(
                                Memory.kind == "short",
                                Memory.expires_at.is_not(None),
                                Memory.expires_at < utcnow(),
                            )
                        )
                        await cleanup.commit()
                except Exception as exc:
                    await write_log("warning", "scheduler", f"清理过期短期记忆失败: {exc}")


scheduler = TaskScheduler()
