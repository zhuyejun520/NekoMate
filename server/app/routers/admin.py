from __future__ import annotations

import platform
import re
import secrets
import shutil
import sys
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select

from ..config import settings
from ..deps import AdminDep, SessionDep
from ..models import (
    AiFriend,
    AppSetting,
    Conversation,
    FriendRequest,
    Friendship,
    FutureTask,
    KnowledgeBase,
    Message,
    ModelProvider,
    Persona,
    PersonaKnowledge,
    PluginRecord,
    SystemLog,
    User,
)
from ..security import create_token, hash_password, verify_password
from ..serializers import (
    future_task,
    knowledge_base,
    log_item,
    plugin_record,
    provider,
    user_full,
)
from ..services.logger import ring, write_log
from ..services.push import manager as ws_manager
from ..services.settings_service import DEFAULTS, get_settings_map, set_setting
from ..utils import days_ago, iso, utcnow
from .meta import STARTED_AT

router = APIRouter(prefix="/api/admin", tags=["管理面板"])

SETTING_KEYS = set(DEFAULTS.keys()) | {"admin_notice"}
_admin_attempts: dict[str, list[float]] = {}


class LoginIn(BaseModel):
    username: str
    password: str


class PersonaIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    avatar: str | None = None
    tagline: str | None = None
    description: str | None = None
    system_prompt: str | None = None
    personality: str | None = None
    speaking_style: str | None = None
    greeting: str | None = None
    catchphrases: list[str] = Field(default_factory=list)
    is_public: bool = True
    enabled: bool = True
    kb_ids: list[int] = Field(default_factory=list)


class UserPatch(BaseModel):
    nickname: str | None = None
    role: str | None = None
    status: str | None = None
    city: str | None = None
    must_change_password: bool | None = None


class ResetPasswordIn(BaseModel):
    password: str = Field(min_length=6, max_length=72)


class SettingsIn(BaseModel):
    values: dict[str, Any]


def _db_size() -> int:
    db_path = settings.data_path / "nekomate.db"
    if db_path.exists():
        return db_path.stat().st_size
    return 0


def _dir_size(path: Path, limit: int = 2000) -> int:
    total = 0
    count = 0
    if not path.exists():
        return 0
    for p in path.rglob("*"):
        if p.is_file():
            try:
                total += p.stat().st_size
                count += 1
            except OSError:
                continue
        if count > limit:
            break
    return total


@router.post("/auth/login", summary="管理面板登录")
async def admin_login(payload: LoginIn, session: SessionDep, request: Request):
    ip = request.client.host if request.client else "-"
    key = f"{payload.username.strip()}:{ip}"
    now_ts = utcnow().timestamp()
    attempts = [t for t in _admin_attempts.get(key, []) if now_ts - t < 600]
    if len(attempts) >= 10:
        raise HTTPException(status_code=429, detail="尝试次数过多，请 10 分钟后再试")
    user = (
        await session.execute(select(User).where(User.username == payload.username.strip()))
    ).scalars().first()
    if user is None or not verify_password(payload.password, user.password_hash):
        attempts.append(now_ts)
        _admin_attempts[key] = attempts
        raise HTTPException(status_code=401, detail="账号或密码错误")
    _admin_attempts.pop(key, None)
    if user.role != "admin":
        raise HTTPException(status_code=403, detail="该账号没有管理权限")
    if user.status != "active":
        raise HTTPException(status_code=403, detail="账号已被禁用")
    user.last_seen_at = utcnow()
    await session.commit()
    await write_log("info", "admin", f"管理员 {user.username} 登录面板", ip=ip)
    return {"token": create_token(user.id, user.role, ttl_hours=24 * 7), "user": user_full(user)}


@router.get("/overview", summary="欢迎页概览")
async def overview(admin: AdminDep, session: SessionDep):
    today = days_ago(1)
    week = days_ago(7)

    async def count(model, *where):
        stmt = select(func.count()).select_from(model)
        for w in where:
            stmt = stmt.where(w)
        return int((await session.execute(stmt)).scalar() or 0)

    users_total = await count(User)
    users_new_week = await count(User, User.created_at >= week)
    messages_total = await count(Message)
    messages_today = await count(Message, Message.created_at >= today)
    ai_total = await count(AiFriend)
    personas_total = await count(Persona)
    kb_total = await count(KnowledgeBase)
    plugins_total = await count(PluginRecord)
    tasks_total = await count(FutureTask, FutureTask.enabled.is_(True))
    requests_pending = await count(FriendRequest, FriendRequest.status == "pending")

    providers_rows = (await session.execute(select(ModelProvider).order_by(ModelProvider.priority.asc()))).scalars().all()
    recent_users = (
        await session.execute(select(User).order_by(User.id.desc()).limit(6))
    ).scalars().all()
    recent_logs = (
        await session.execute(
            select(SystemLog).where(SystemLog.level != "debug").order_by(SystemLog.id.desc()).limit(10)
        )
    ).scalars().all()
    tasks_rows = (
        await session.execute(select(FutureTask).order_by(FutureTask.id.desc()).limit(5))
    ).scalars().all()
    ai_rows = (
        await session.execute(select(AiFriend).order_by(AiFriend.id.desc()).limit(5))
    ).scalars().all()

    disk = shutil.disk_usage(settings.data_path)

    configured_kinds = {p.kind for p in providers_rows if p.enabled}
    roles = {
        "chat": "chat" in configured_kinds,
        "image": "image" in configured_kinds,
        "analysis": "analysis" in configured_kinds or "chat" in configured_kinds,
    }
    recent_user_items = []
    for u in recent_users:
        ai_cnt = int(
            (await session.execute(select(func.count(AiFriend.id)).where(AiFriend.user_id == u.id))).scalar() or 0
        )
        recent_user_items.append(user_full(u, stats={"ai_friends": ai_cnt}))
    checklist = [
        {"key": "provider", "label": "配置对话模型提供商", "done": "chat" in configured_kinds,
         "hint": "模型提供商 → 新增 → 选择 OpenAI 兼容协议"},
        {"key": "persona", "label": "创建角色卡", "done": personas_total > 0, "hint": "角色卡 → 新建，写清身份与说话方式"},
        {"key": "knowledge", "label": "导入微信/QQ 聊天记录", "done": kb_total > 0, "hint": "知识库 → 新建 → 导入聊天记录"},
        {"key": "user", "label": "注册第一个用户", "done": users_total > 0, "hint": "客户端注册，或在用户管理中创建"},
        {"key": "plugin", "label": "认识插件系统", "done": plugins_total > 0, "hint": "插件 → 内置示例已自动安装"},
        {"key": "task", "label": "配置一个未来任务", "done": tasks_total > 0, "hint": "未来任务 → 新建定时/周期任务"},
    ]

    return {
        "welcome": {
            "admin": admin.nickname or admin.username,
            "site_name": settings.app_title,
            "version": settings.version,
            "server_time": iso(utcnow()),
            "uptime_seconds": int((utcnow() - STARTED_AT).total_seconds()),
            "notice": (await get_settings_map(session)).get("admin_notice") or "",
            "must_change_password": admin.must_change_password,
        },
        "stats": {
            "users": users_total,
            "users_new_week": users_new_week,
            "messages": messages_total,
            "messages_today": messages_today,
            "ai_friends": ai_total,
            "personas": personas_total,
            "knowledge_bases": kb_total,
            "plugins": plugins_total,
            "tasks": tasks_total,
            "pending_requests": requests_pending,
            "online": ws_manager.online_count(),
        },
        "checklist": checklist,
        "roles": roles,
        "providers": [
            {
                "kind": p.kind,
                "name": p.name,
                "model": p.model,
                "enabled": p.enabled,
                "status": p.status,
            }
            for p in providers_rows
        ],
        "recent_users": recent_user_items,
        "recent_logs": [log_item(l) for l in recent_logs],
        "recent_tasks": [future_task(t) for t in tasks_rows],
        "recent_ai_friends": [
            {"id": a.id, "name": a.name, "avatar": a.avatar, "user_id": a.user_id, "root_enabled": a.root_enabled}
            for a in ai_rows
        ],
        "system": {
            "python": sys.version.split()[0],
            "platform": f"{platform.system()} {platform.release()}",
            "db_size": _db_size(),
            "data_dir": str(settings.data_path),
            "uploads_size": _dir_size(settings.uploads_dir),
            "disk_free": disk.free,
            "disk_total": disk.total,
        },
    }


# ------------------------------------------------------------------ personas
@router.get("/personas", summary="角色卡列表")
async def list_personas(admin: AdminDep, session: SessionDep):
    rows = (await session.execute(select(Persona).order_by(Persona.id.desc()))).scalars().all()
    out = []
    for p in rows:
        kb_ids = (
            await session.execute(select(PersonaKnowledge.kb_id).where(PersonaKnowledge.persona_id == p.id))
        ).scalars().all()
        used = (
            await session.execute(select(func.count(AiFriend.id)).where(AiFriend.persona_id == p.id))
        ).scalar() or 0
        data = {
            "id": p.id,
            "name": p.name,
            "avatar": p.avatar,
            "tagline": p.tagline,
            "description": p.description,
            "system_prompt": p.system_prompt,
            "personality": p.personality,
            "speaking_style": p.speaking_style,
            "greeting": p.greeting,
            "catchphrases": p.catchphrases or [],
            "is_public": p.is_public,
            "enabled": p.enabled,
            "owner_id": p.owner_id,
            "created_at": iso(p.created_at),
            "updated_at": iso(p.updated_at),
            "kb_ids": list(kb_ids),
            "ai_friends": int(used),
        }
        if p.owner_id:
            owner = await session.get(User, p.owner_id)
            data["owner_name"] = (owner.nickname or owner.username) if owner else f"用户 {p.owner_id}"
        out.append(data)
    return {"personas": out}


@router.post("/personas", summary="新建角色卡")
async def create_persona(payload: PersonaIn, admin: AdminDep, session: SessionDep):
    persona = Persona(
        name=payload.name.strip(),
        avatar=payload.avatar,
        tagline=payload.tagline,
        description=payload.description,
        system_prompt=payload.system_prompt,
        personality=payload.personality,
        speaking_style=payload.speaking_style,
        greeting=payload.greeting,
        catchphrases=payload.catchphrases[:12],
        is_public=payload.is_public,
        enabled=payload.enabled,
        owner_id=admin.id,
    )
    session.add(persona)
    await session.flush()
    for kb_id in payload.kb_ids:
        if await session.get(KnowledgeBase, kb_id):
            session.add(PersonaKnowledge(persona_id=persona.id, kb_id=kb_id))
    await session.commit()
    await session.refresh(persona)
    await write_log("info", "admin", f"创建角色卡「{persona.name}」", admin_id=admin.id)
    return {"id": persona.id}


@router.post("/personas/import", summary="从 JSON 批量导入角色卡")
async def import_personas(admin: AdminDep, session: SessionDep, payload: Any = Body(...)):
    # 支持数组、{personas:[...]}、{characters:[...]}、{cards:[...]} 或单个对象。
    cards: list[Any]
    if isinstance(payload, list):
        cards = payload
    elif isinstance(payload, dict):
        for key in ("personas", "characters", "cards", "data", "items"):
            if isinstance(payload.get(key), list):
                cards = payload[key]
                break
        else:
            cards = [payload]
    else:
        raise HTTPException(status_code=400, detail="JSON 必须是对象或数组")

    def first(card: dict, *keys):
        for key in keys:
            value = card.get(key)
            if value is not None and str(value).strip() != "":
                return str(value).strip()
        return ""

    def as_bool(value, default=True):
        if isinstance(value, bool):
            return value
        if isinstance(value, str):
            return value.strip().lower() not in ("false", "0", "no", "off", "私有", "关闭")
        return default

    existing = set(
        (
            await session.execute(select(Persona.name).where(Persona.is_public.is_(True)))
        ).scalars().all()
    )
    imported = 0
    skipped = 0
    errors: list[str] = []
    for index, raw in enumerate(cards[:500]):
        if not isinstance(raw, dict):
            skipped += 1
            errors.append(f"第 {index + 1} 项不是对象")
            continue
        name = first(raw, "name", "char_name", "character_name", "character", "title")[:64]
        if not name:
            skipped += 1
            errors.append(f"第 {index + 1} 项缺少 name")
            continue
        if name in existing:
            skipped += 1
            continue
        description = first(raw, "description", "background", "profile", "intro", "persona")
        scenario = first(raw, "scenario", "world", "setting")
        if scenario and scenario not in description:
            description = (description + "\n\n场景：" + scenario).strip()
        greeting = first(raw, "greeting", "first_message", "first_mes")
        system_prompt = first(raw, "system_prompt", "prompt", "system", "jailbreak")
        personality = first(raw, "personality", "traits", "character_traits")
        speaking_style = first(raw, "speaking_style", "style", "speech", "dialogue_style")
        tagline = first(raw, "tagline", "subtitle", "summary")[:128]
        raw_catch = raw.get("catchphrases") or raw.get("catchphrases_list") or []
        if isinstance(raw_catch, str):
            raw_catch = [x for x in re.split(r"[,，;；、\s]+", raw_catch) if x]
        catchphrases = [str(x).strip()[:64] for x in raw_catch if str(x).strip()][:12]
        card = Persona(
            name=name,
            avatar=first(raw, "avatar", "image", "icon") or None,
            tagline=tagline or None,
            description=description or None,
            system_prompt=system_prompt or None,
            personality=personality or None,
            speaking_style=speaking_style or None,
            greeting=greeting or None,
            catchphrases=catchphrases,
            is_public=as_bool(raw.get("is_public"), True),
            enabled=as_bool(raw.get("enabled"), True),
            owner_id=admin.id,
            extra={"imported": True, "source": "admin_json"},
        )
        session.add(card)
        await session.flush()
        kb_ids = raw.get("kb_ids") or raw.get("knowledge_ids") or []
        if isinstance(kb_ids, list):
            for kb_id in kb_ids[:20]:
                try:
                    kb_id = int(kb_id)
                except Exception:
                    continue
                if await session.get(KnowledgeBase, kb_id):
                    session.add(PersonaKnowledge(persona_id=card.id, kb_id=kb_id))
        existing.add(name)
        imported += 1
    await session.commit()
    await write_log("info", "admin", f"JSON 导入角色卡：成功 {imported}，跳过 {skipped}", admin_id=admin.id)
    return {"imported": imported, "skipped": skipped, "errors": errors[:20]}


@router.put("/personas/{persona_id}", summary="修改角色卡")
async def update_persona(persona_id: int, payload: PersonaIn, admin: AdminDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    persona.name = payload.name.strip()
    persona.avatar = payload.avatar
    if payload.avatar is not None:
        rows = (
            await session.execute(select(AiFriend).where(AiFriend.persona_id == persona.id))
        ).scalars().all()
        for ai in rows:
            ai.avatar = payload.avatar
    persona.tagline = payload.tagline
    persona.description = payload.description
    persona.system_prompt = payload.system_prompt
    persona.personality = payload.personality
    persona.speaking_style = payload.speaking_style
    persona.greeting = payload.greeting
    persona.catchphrases = payload.catchphrases[:12]
    persona.is_public = payload.is_public
    persona.enabled = payload.enabled
    await session.execute(
        PersonaKnowledge.__table__.delete().where(PersonaKnowledge.persona_id == persona.id)
    )
    for kb_id in payload.kb_ids:
        if await session.get(KnowledgeBase, kb_id):
            session.add(PersonaKnowledge(persona_id=persona.id, kb_id=kb_id))
    await session.commit()
    await write_log("info", "admin", f"更新角色卡「{persona.name}」", admin_id=admin.id)
    return {"ok": True}


@router.delete("/personas/{persona_id}", summary="删除角色卡")
async def delete_persona(persona_id: int, admin: AdminDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    used = (
        await session.execute(select(func.count(AiFriend.id)).where(AiFriend.persona_id == persona_id))
    ).scalar() or 0
    if used:
        raise HTTPException(status_code=409, detail=f"该角色卡已被 {used} 个 AI 好友使用，请先删除或更换相关 AI 好友")
    await session.execute(
        PersonaKnowledge.__table__.delete().where(PersonaKnowledge.persona_id == persona_id)
    )
    await session.delete(persona)
    await session.commit()
    return {"ok": True}


# --------------------------------------------------------------------- users
@router.get("/users", summary="用户列表")
async def list_users(admin: AdminDep, session: SessionDep, q: str | None = None, page: int = 1, size: int = 20):
    page = max(1, page)
    size = max(1, min(size, 100))
    stmt = select(User)
    if q:
        pattern = f"%{q.strip()}%"
        stmt = stmt.where(or_(User.username.ilike(pattern), User.nickname.ilike(pattern)))
    total = (
        await session.execute(select(func.count()).select_from(stmt.subquery()))
    ).scalar() or 0
    rows = (
        await session.execute(stmt.order_by(User.id.desc()).offset((page - 1) * size).limit(size))
    ).scalars().all()
    items = []
    for u in rows:
        friends = (
            await session.execute(select(func.count(Friendship.id)).where(Friendship.user_id == u.id))
        ).scalar() or 0
        ais = (
            await session.execute(select(func.count(AiFriend.id)).where(AiFriend.user_id == u.id))
        ).scalar() or 0
        msgs = (
            await session.execute(select(func.count(Message.id)).where(Message.sender_id == u.id))
        ).scalar() or 0
        keys = (
            await session.execute(
                select(func.count(ModelProvider.id)).where(ModelProvider.owner_user_id == u.id)
            )
        ).scalar() or 0
        data = user_full(
            u,
            stats={
                "friends": int(friends),
                "ai_friends": int(ais),
                "messages": int(msgs),
                "private_keys": int(keys),
            },
        )
        items.append(data)
    return {"users": items, "total": int(total), "page": page, "size": size}


@router.put("/users/{user_id}", summary="修改用户")
async def update_user(user_id: int, payload: UserPatch, admin: AdminDep, session: SessionDep):
    user = await session.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    if payload.nickname is not None:
        user.nickname = payload.nickname.strip()[:32] or user.nickname
    if payload.role in ("user", "admin"):
        if user.id == admin.id and payload.role != "admin":
            raise HTTPException(status_code=400, detail="不能取消自己的管理员权限")
        user.role = payload.role
    if payload.status in ("active", "banned", "disabled"):
        if user.id == admin.id and payload.status != "active":
            raise HTTPException(status_code=400, detail="不能禁用自己")
        user.status = "active" if payload.status == "active" else "disabled"
    if payload.city is not None:
        user.city = payload.city
    if payload.must_change_password is not None:
        user.must_change_password = payload.must_change_password
    await session.commit()
    return {"user": user_full(user)}


@router.post("/users/{user_id}/reset-password", summary="重置用户密码")
async def reset_user_password(user_id: int, payload: ResetPasswordIn, admin: AdminDep, session: SessionDep):
    user = await session.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    user.password_hash = hash_password(payload.password)
    user.must_change_password = True
    await session.commit()
    await write_log("info", "admin", f"管理员重置了 {user.username} 的密码", admin_id=admin.id)
    return {"ok": True}


@router.delete("/users/{user_id}", summary="删除用户")
async def delete_user(user_id: int, admin: AdminDep, session: SessionDep):
    from sqlalchemy import delete as sql_delete

    from ..models import (
        AppWhitelist,
        ConversationAiMember,
        ConversationMember,
        Memory,
        Moment,
        MomentComment,
        MomentLike,
        ProactiveState,
        UsageEvent,
    )

    user = await session.get(User, user_id)
    if user is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    if user.id == admin.id:
        raise HTTPException(status_code=400, detail="不能删除自己")

    # AI 好友及其会话、记忆、白名单、主动消息状态
    ai_rows = (await session.execute(select(AiFriend).where(AiFriend.user_id == user.id))).scalars().all()
    for ai in ai_rows:
        conv_ids = (await session.execute(select(Conversation.id).where(Conversation.ai_friend_id == ai.id))).scalars().all()
        if conv_ids:
            await session.execute(sql_delete(Message).where(Message.conversation_id.in_(conv_ids)))
            await session.execute(sql_delete(ConversationMember).where(ConversationMember.conversation_id.in_(conv_ids)))
            await session.execute(sql_delete(Conversation).where(Conversation.id.in_(conv_ids)))
        await session.execute(sql_delete(Memory).where(Memory.ai_friend_id == ai.id))
        await session.execute(sql_delete(UsageEvent).where(UsageEvent.ai_friend_id == ai.id))
        await session.execute(sql_delete(AppWhitelist).where(AppWhitelist.ai_friend_id == ai.id))
        await session.execute(sql_delete(ConversationAiMember).where(ConversationAiMember.ai_friend_id == ai.id))
        await session.execute(sql_delete(ProactiveState).where(ProactiveState.ai_friend_id == ai.id))
        await session.delete(ai)

    # 群聊：转让群主或删除成员关系；私聊保留给对方
    owned = (
        await session.execute(
            select(Conversation).where(Conversation.owner_id == user.id, Conversation.type == "group")
        )
    ).scalars().all()
    for conv in owned:
        others = (
            await session.execute(
                select(ConversationMember).where(
                    ConversationMember.conversation_id == conv.id, ConversationMember.user_id != user.id
                )
            )
        ).scalars().all()
        conv.owner_id = others[0].user_id if others else None
    await session.execute(sql_delete(ConversationMember).where(ConversationMember.user_id == user.id))
    await session.execute(sql_delete(ConversationAiMember).where(ConversationAiMember.added_by == user.id))

    await session.execute(sql_delete(Friendship).where(Friendship.user_id == user.id))
    await session.execute(sql_delete(Friendship).where(Friendship.friend_id == user.id))
    await session.execute(sql_delete(FriendRequest).where(FriendRequest.from_user_id == user.id))
    await session.execute(sql_delete(FriendRequest).where(FriendRequest.to_user_id == user.id))

    moment_ids = (await session.execute(select(Moment.id).where(Moment.user_id == user.id))).scalars().all()
    if moment_ids:
        await session.execute(sql_delete(MomentLike).where(MomentLike.moment_id.in_(moment_ids)))
        await session.execute(sql_delete(MomentComment).where(MomentComment.moment_id.in_(moment_ids)))
        await session.execute(sql_delete(Moment).where(Moment.id.in_(moment_ids)))
    await session.execute(sql_delete(MomentLike).where(MomentLike.user_id == user.id))
    await session.execute(sql_delete(MomentComment).where(MomentComment.user_id == user.id))

    username = user.username
    await session.delete(user)
    await session.commit()
    await write_log("info", "admin", f"删除用户 {username}（含 AI 好友、会话、记忆与动态）", admin_id=admin.id)
    return {"ok": True}


# ------------------------------------------------------------------- settings
@router.get("/settings", summary="系统设置")
async def get_admin_settings(admin: AdminDep, session: SessionDep):
    values = await get_settings_map(session)
    return {
        "values": values,
        "defaults": DEFAULTS,
        "app": {
            "app_name": settings.app_name,
            "version": settings.version,
            "data_dir": str(settings.data_path),
            "public_base_url": settings.public_base_url,
            "registration_env": settings.registration_enabled,
        },
    }


@router.put("/settings", summary="保存系统设置")
async def save_admin_settings(payload: SettingsIn, admin: AdminDep, session: SessionDep):
    saved = {}
    for key, value in payload.values.items():
        if key not in SETTING_KEYS:
            continue
        if key in ("registration_enabled", "auto_create_ai_friend", "weather_enabled", "plugin_dispatch_enabled"):
            value = bool(value)
        elif key in ("default_idle_minutes", "proactive_min_minutes", "proactive_max_minutes", "proactive_max_streak",
                     "short_memory_days", "max_context_messages", "usage_reply_cooldown_minutes"):
            try:
                value = max(0, int(value))
            except (TypeError, ValueError):
                continue
        await set_setting(session, key, value)
        saved[key] = value
    await write_log("info", "admin", f"更新系统设置: {', '.join(saved.keys())}", admin_id=admin.id)
    return {"ok": True, "saved": saved, "values": await get_settings_map(session)}


@router.get("/system", summary="系统状态")
async def system_status(admin: AdminDep, session: SessionDep):
    db_size = _db_size()
    disk = shutil.disk_usage(settings.data_path)
    counts = {}
    for name, model in (
        ("users", User),
        ("messages", Message),
        ("conversations", Conversation),
        ("ai_friends", AiFriend),
        ("personas", Persona),
        ("knowledge_bases", KnowledgeBase),
        ("providers", ModelProvider),
        ("plugins", PluginRecord),
        ("tasks", FutureTask),
        ("logs", SystemLog),
    ):
        counts[name] = int((await session.execute(select(func.count()).select_from(model))).scalar() or 0)
    return {
        "version": settings.version,
        "python": sys.version.split()[0],
        "platform": f"{platform.system()} {platform.release()}",
        "data_dir": str(settings.data_path),
        "db_size": db_size,
        "uploads_size": _dir_size(settings.uploads_dir),
        "disk": {"total": disk.total, "used": disk.used, "free": disk.free},
        "uptime_seconds": int((utcnow() - STARTED_AT).total_seconds()),
        "counts": counts,
        "online": ws_manager.online_count(),
    }


# ---------------------------------------------------------------------- logs
@router.get("/logs", summary="日志查询")
async def list_logs(
    admin: AdminDep,
    session: SessionDep,
    level: str | None = None,
    category: str | None = None,
    q: str | None = None,
    limit: int = 100,
    offset: int = 0,
):
    limit = max(1, min(limit, 500))
    stmt = select(SystemLog)
    count_stmt = select(func.count()).select_from(SystemLog)
    filters = []
    if level and level != "all":
        filters.append(SystemLog.level == level)
    if category and category != "all":
        filters.append(SystemLog.category == category)
    if q:
        filters.append(SystemLog.message.ilike(f"%{q.strip()}%"))
    for f in filters:
        stmt = stmt.where(f)
        count_stmt = count_stmt.where(f)
    total = int((await session.execute(count_stmt)).scalar() or 0)
    rows = (
        await session.execute(stmt.order_by(SystemLog.id.desc()).offset(offset).limit(limit))
    ).scalars().all()
    return {"logs": [log_item(l) for l in rows], "total": total, "ring": ring.tail(30)}


@router.delete("/logs", summary="清理日志")
async def clear_logs(admin: AdminDep, session: SessionDep, days: int = 0):
    from sqlalchemy import delete

    if days <= 0:
        await session.execute(delete(SystemLog))
    else:
        await session.execute(delete(SystemLog).where(SystemLog.created_at < days_ago(days)))
    await session.commit()
    return {"ok": True}


@router.get("/settings/raw", summary="原始设置行")
async def raw_settings(admin: AdminDep, session: SessionDep):
    rows = (await session.execute(select(AppSetting))).scalars().all()
    return {"settings": [{"key": r.key, "value": r.value, "updated_at": iso(r.updated_at)} for r in rows]}
