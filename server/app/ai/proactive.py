from __future__ import annotations

import asyncio
import random
from datetime import datetime, time, timedelta
from typing import Any

from sqlalchemy import select

from ..models import AiFriend, ProactiveState
from ..services.logger import write_log
from ..services.settings_service import get_settings_map
from ..utils import get_tz, utcnow
from .engine import CompanionEngine, default_ai_settings

engine = CompanionEngine()
IDLE_CHECK_INTERVAL = 20


def _tz(name: str | None):
    return get_tz(name)


def _parse_hhmm(value: Any) -> time | None:
    try:
        hh, mm = [int(x) for x in str(value).split(":")[:2]]
        return time(hour=hh, minute=mm)
    except Exception:
        return None


def in_quiet_hours(now_local: datetime, start: Any, end: Any) -> bool:
    s, e = _parse_hhmm(start), _parse_hhmm(end)
    if not s or not e:
        return False
    current = now_local.time()
    if s <= e:
        return s <= current < e
    return current >= s or current < e


async def proactive_tick() -> dict[str, int]:
    """一次扫描：处理空闲收尾与主动消息。"""
    from ..database import SessionLocal

    stats = {"ended": 0, "sent": 0, "skipped": 0, "stopped": 0}
    if SessionLocal is None:
        return stats
    async with SessionLocal() as session:
        app_settings = await get_settings_map(session)
        tz_name = app_settings.get("timezone", "Asia/Shanghai")
        tz = _tz(tz_name)
        now = utcnow()
        states = (
            await session.execute(
                select(ProactiveState).where(ProactiveState.phase.in_(("active", "waiting"))).limit(200)
            )
        ).scalars().all()

        for state in states:
            ai = await session.get(AiFriend, state.ai_friend_id)
            if ai is None or ai.status != "active":
                continue
            cfg = {**default_ai_settings(app_settings), **(ai.settings or {})}
            if not cfg.get("proactive_enabled", True):
                continue
            last_user = state.last_user_at
            if last_user is not None and last_user.tzinfo is None:
                last_user = last_user.replace(tzinfo=now.tzinfo)

            if state.phase == "active":
                if last_user is None:
                    state.last_user_at = now
                    await session.commit()
                    continue
                idle = timedelta(minutes=int(cfg["idle_minutes"]))
                if now - last_user >= idle:
                    delay = random.randint(int(cfg["min_delay_minutes"]), int(cfg["max_delay_minutes"]))
                    state.phase = "waiting"
                    state.streak = 0
                    state.next_proactive_at = now + timedelta(minutes=delay)
                    await session.commit()
                    stats["ended"] += 1
                    await write_log(
                        "info",
                        "proactive",
                        f"会话进入空闲：{int(cfg['idle_minutes'])} 分钟未发言，{delay} 分钟后 AI 将主动发起聊天",
                        ai_friend_id=ai.id,
                        user_id=state.user_id,
                    )
                continue

            # phase == waiting
            next_at = state.next_proactive_at
            if next_at is None:
                state.next_proactive_at = now + timedelta(minutes=int(cfg["min_delay_minutes"]))
                await session.commit()
                continue
            if next_at.tzinfo is None:
                next_at = next_at.replace(tzinfo=now.tzinfo)
            if now < next_at:
                continue
            if state.streak >= int(cfg["max_streak"]):
                state.phase = "stopped"
                state.next_proactive_at = None
                await session.commit()
                stats["stopped"] += 1
                await write_log(
                    "info",
                    "proactive",
                    f"已连续主动发起 {state.streak} 次且用户未回复，暂停主动消息，等待用户先开口",
                    ai_friend_id=ai.id,
                    user_id=state.user_id,
                )
                continue

            local_now = now.astimezone(tz)
            if in_quiet_hours(local_now, cfg.get("quiet_start"), cfg.get("quiet_end")):
                state.next_proactive_at = now + timedelta(minutes=30)
                await session.commit()
                stats["skipped"] += 1
                continue

            try:
                msg = await engine.proactive(session, state)
                if msg is not None:
                    stats["sent"] += 1
                else:
                    state.next_proactive_at = now + timedelta(minutes=max(15, int(cfg["min_delay_minutes"])))
                    await session.commit()
                    stats["skipped"] += 1
            except Exception as exc:
                await write_log("error", "proactive", f"主动消息发送失败: {exc}", ai_friend_id=ai.id)
                state.next_proactive_at = now + timedelta(minutes=30)
                await session.commit()
                stats["skipped"] += 1
    return stats


async def proactive_worker(stop_event: asyncio.Event) -> None:
    await write_log("info", "proactive", "主动消息后台循环已启动（空闲 30 分钟结束会话，1-3 小时后随机主动搭话）")
    while not stop_event.is_set():
        try:
            await proactive_tick()
        except Exception as exc:
            await write_log("error", "proactive", f"主动消息循环异常: {exc}")
        try:
            await asyncio.wait_for(stop_event.wait(), timeout=IDLE_CHECK_INTERVAL)
        except asyncio.TimeoutError:
            pass
    await write_log("info", "proactive", "主动消息后台循环已停止")
