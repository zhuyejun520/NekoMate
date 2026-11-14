from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, select

from ..deps import AdminDep, SessionDep
from ..models import AiFriend, FutureTask, FutureTaskRun, Persona, User
from ..serializers import future_task
from ..services.scheduler import compute_next_run, execute_task
from ..services.settings_service import get_settings_map
from ..utils import iso, parse_dt, utcnow

router = APIRouter(prefix="/api/admin", tags=["管理·未来任务"])

TASK_TYPES = [
    {"value": "message", "label": "定时发消息", "desc": "在指定时间让 AI 发一条消息（可固定文案，也可由模型生成）"},
    {"value": "proactive", "label": "立即/周期主动搭话", "desc": "强制触发一次主动消息（忽略空闲判定与次数上限）"},
    {"value": "moment", "label": "生成日常动态", "desc": "以指定角色卡的口吻发一条“日常空间”动态"},
    {"value": "plugin", "label": "执行插件命令", "desc": "在用户会话里执行一条 AstrBot 插件命令"},
]

SCHEDULE_KINDS = [
    {"value": "once", "label": "一次性", "example": {"kind": "once", "at": "2026-11-08T09:00:00+08:00"}},
    {"value": "interval", "label": "固定间隔", "example": {"kind": "interval", "minutes": 120}},
    {"value": "daily", "label": "每天定时", "example": {"kind": "daily", "time": "08:30"}},
    {"value": "cron", "label": "Cron 表达式", "example": {"kind": "cron", "expression": "*/30 9-22 * * *"}},
    {"value": "manual", "label": "仅手动", "example": {"kind": "manual"}},
]


class TaskIn(BaseModel):
    name: str = Field(min_length=1, max_length=128)
    type: str = "message"
    schedule: dict[str, Any] = Field(default_factory=dict)
    payload: dict[str, Any] = Field(default_factory=dict)
    user_id: int | None = None
    ai_friend_id: int | None = None
    enabled: bool = True


def _run_view(run: FutureTaskRun) -> dict[str, Any]:
    return {
        "id": run.id,
        "task_id": run.task_id,
        "status": run.status,
        "output": run.output,
        "started_at": iso(run.started_at),
        "finished_at": iso(run.finished_at),
    }


@router.get("/tasks", summary="未来任务列表")
async def list_tasks(admin: AdminDep, session: SessionDep):
    rows = (await session.execute(select(FutureTask).order_by(FutureTask.id.desc()))).scalars().all()
    items = []
    for task in rows:
        runs = (
            await session.execute(
                select(FutureTaskRun).where(FutureTaskRun.task_id == task.id).order_by(FutureTaskRun.id.desc()).limit(3)
            )
        ).scalars().all()
        data = future_task(task)
        data["recent_runs"] = [_run_view(r) for r in runs]
        items.append(data)
    return {"tasks": items, "types": TASK_TYPES, "schedule_kinds": SCHEDULE_KINDS}


@router.get("/tasks/options", summary="任务可选用户/AI/角色卡")
async def task_options(admin: AdminDep, session: SessionDep):
    users = (await session.execute(select(User).order_by(User.id.desc()).limit(200))).scalars().all()
    ais = (await session.execute(select(AiFriend).order_by(AiFriend.id.desc()).limit(300))).scalars().all()
    personas = (await session.execute(select(Persona).order_by(Persona.id.asc()))).scalars().all()
    return {
        "users": [{"id": u.id, "username": u.username, "nickname": u.nickname} for u in users],
        "ai_friends": [
            {"id": a.id, "name": a.name, "user_id": a.user_id, "persona_id": a.persona_id, "root_enabled": a.root_enabled}
            for a in ais
        ],
        "personas": [{"id": p.id, "name": p.name} for p in personas],
    }


@router.post("/tasks", summary="新建未来任务")
async def create_task(payload: TaskIn, admin: AdminDep, session: SessionDep):
    if not payload.user_id and not payload.ai_friend_id:
        raise HTTPException(status_code=400, detail="请至少绑定一个用户或 AI 好友")
    app_settings = await get_settings_map(session)
    schedule = payload.schedule or {"kind": "manual"}
    next_run = None
    if payload.enabled and schedule.get("kind") not in (None, "manual"):
        next_run = compute_next_run(schedule, utcnow(), app_settings.get("timezone", "Asia/Shanghai"))
        if next_run is None:
            raise HTTPException(status_code=400, detail="无法计算下次执行时间，请检查时间格式（一次性任务必须在未来）")
    task = FutureTask(
        name=payload.name.strip(),
        type=payload.type if payload.type in {t["value"] for t in TASK_TYPES} else "message",
        schedule=schedule,
        payload=payload.payload or {},
        user_id=payload.user_id,
        ai_friend_id=payload.ai_friend_id,
        enabled=payload.enabled,
        next_run_at=next_run,
    )
    session.add(task)
    await session.commit()
    await session.refresh(task)
    return {"task": future_task(task)}


@router.put("/tasks/{task_id}", summary="修改未来任务")
async def update_task(task_id: int, payload: TaskIn, admin: AdminDep, session: SessionDep):
    task = await session.get(FutureTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    if not payload.user_id and not payload.ai_friend_id:
        raise HTTPException(status_code=400, detail="请至少绑定一个用户或 AI 好友")
    app_settings = await get_settings_map(session)
    task.name = payload.name.strip()
    task.type = payload.type if payload.type in {t["value"] for t in TASK_TYPES} else task.type
    task.schedule = payload.schedule or {"kind": "manual"}
    task.payload = payload.payload or {}
    task.user_id = payload.user_id
    task.ai_friend_id = payload.ai_friend_id
    task.enabled = payload.enabled
    task.next_run_at = (
        compute_next_run(task.schedule, utcnow(), app_settings.get("timezone", "Asia/Shanghai"))
        if payload.enabled and task.schedule.get("kind") not in (None, "manual")
        else None
    )
    await session.commit()
    return {"task": future_task(task)}


@router.delete("/tasks/{task_id}", summary="删除未来任务")
async def delete_task(task_id: int, admin: AdminDep, session: SessionDep):
    task = await session.get(FutureTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    await session.execute(delete(FutureTaskRun).where(FutureTaskRun.task_id == task_id))
    await session.delete(task)
    await session.commit()
    return {"ok": True}


@router.post("/tasks/{task_id}/run", summary="立即执行任务")
async def run_task(task_id: int, admin: AdminDep, session: SessionDep):
    task = await session.get(FutureTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="任务不存在")
    run = await execute_task(task_id)
    if run is None:
        raise HTTPException(status_code=500, detail="任务执行失败")
    return {"run": _run_view(run)}


@router.get("/tasks/{task_id}/runs", summary="任务执行历史")
async def task_runs(task_id: int, admin: AdminDep, session: SessionDep, limit: int = 50):
    rows = (
        await session.execute(
            select(FutureTaskRun)
            .where(FutureTaskRun.task_id == task_id)
            .order_by(FutureTaskRun.id.desc())
            .limit(max(1, min(limit, 200)))
        )
    ).scalars().all()
    return {"runs": [_run_view(r) for r in rows]}
