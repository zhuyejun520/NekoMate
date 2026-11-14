from __future__ import annotations

import re
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, or_, select

from ..ai.engine import CompanionEngine
from ..ai.providers import ProviderError, generate_image, has_private_key, pick_provider, vision_ocr
from ..bootstrap import DEFAULT_WHITELIST
from ..deps import SessionDep, UserDep
from ..models import (
    AiFriend,
    AppWhitelist,
    Conversation,
    ConversationAiMember,
    ConversationMember,
    KnowledgeBase,
    KnowledgeChunk,
    KnowledgeDocument,
    Memory,
    Message,
    ModelProvider,
    Persona,
    PersonaKnowledge,
    ProactiveState,
    UsageEvent,
    User,
)
from ..serializers import ai_friend as serialize_ai_friend
from ..serializers import memory_item, message as serialize_message, persona as serialize_persona
from ..serializers import usage_event, whitelist_item
from ..serializers import user_brief
from ..services.knowledge import import_chat_text, kb_counters, rebuild_style_from_documents
from ..services.logger import write_log
from ..services.settings_service import get_settings_map
from ..services.push import manager as ws_manager
from ..utils import truncate, utcnow

router = APIRouter(prefix="/api/v1/ai", tags=["AI 好友"])
engine = CompanionEngine()

ALLOWED_SETTINGS = {
    "idle_minutes": (1, 1440),
    "min_delay_minutes": (5, 1440),
    "max_delay_minutes": (5, 1440),
    "max_streak": (1, 10),
    "usage_cooldown_minutes": (0, 240),
    "memory_enabled": (0, 1),
    "proactive_enabled": (0, 1),
    "affinity_enabled": (0, 1),
    "split_replies": (0, 1),
    "user_segment_delay": (0, 30),
    "affinity": (0, 100),
}


class AiFriendIn(BaseModel):
    persona_id: int
    name: str | None = Field(default=None, max_length=32)


class PrivatePersonaIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    avatar: str | None = None
    tagline: str | None = Field(default=None, max_length=128)
    description: str | None = None
    system_prompt: str | None = None
    personality: str | None = None
    speaking_style: str | None = None
    greeting: str | None = None
    catchphrases: list[str] = Field(default_factory=list)
    enabled: bool = True


class AiFriendUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=32)
    avatar: str | None = None
    status: str | None = None
    root_enabled: bool | None = None
    settings: dict[str, Any] | None = None


class ChatIn(BaseModel):
    content: str = Field(min_length=1, max_length=4000)


class WhitelistIn(BaseModel):
    package_name: str = Field(min_length=1, max_length=128)
    label: str | None = Field(default=None, max_length=64)
    note: str | None = Field(default=None, max_length=128)
    enabled: bool = True


class WhitelistPatch(BaseModel):
    label: str | None = None
    note: str | None = None
    enabled: bool | None = None


class UsageIn(BaseModel):
    events: list[dict[str, Any]]


class ResetIn(BaseModel):
    clear_messages: bool = True
    greeting: bool = True


class ImageIn(BaseModel):
    prompt: str = Field(min_length=1, max_length=600)
    size: str = "1024x1024"
    post: bool = True


async def _get_ai(session, user, ai_id: int) -> AiFriend:
    ai = await session.get(AiFriend, ai_id)
    if ai is None or ai.user_id != user.id:
        raise HTTPException(status_code=404, detail="AI 好友不存在")
    return ai


def _sanitize_settings(raw: dict[str, Any], current: dict[str, Any]) -> dict[str, Any]:
    data = dict(current or {})
    for key, bounds in ALLOWED_SETTINGS.items():
        if key not in raw:
            continue
        value = raw[key]
        if key in ("memory_enabled", "proactive_enabled", "affinity_enabled", "split_replies"):
            data[key] = bool(value)
            continue
        try:
            ivalue = int(value)
        except (TypeError, ValueError):
            continue
        data[key] = max(bounds[0], min(bounds[1], ivalue))
    if isinstance(raw.get("proactive_sources"), list):
        allowed = {"recent_chat", "weather", "smalltalk"}
        data["proactive_sources"] = [s for s in raw["proactive_sources"] if s in allowed] or ["recent_chat", "smalltalk"]
    for key in ("quiet_start", "quiet_end"):
        if key in raw:
            value = str(raw[key] or "").strip()
            if not value or (len(value) == 5 and value[2] == ":"):
                data[key] = value or None
    if data.get("max_delay_minutes", 180) < data.get("min_delay_minutes", 60):
        data["max_delay_minutes"] = data["min_delay_minutes"]
    return data


async def _ai_view(session, ai: AiFriend, *, with_whitelist: bool = True) -> dict[str, Any]:
    persona = await session.get(Persona, ai.persona_id)
    state = (
        await session.execute(
            select(ProactiveState).where(ProactiveState.user_id == ai.user_id, ProactiveState.ai_friend_id == ai.id)
        )
    ).scalars().first()
    conv = await engine.ensure_conversation(session, ai, await _owner(session, ai))
    whitelist = []
    if with_whitelist:
        whitelist = (
            await session.execute(
                select(AppWhitelist).where(AppWhitelist.ai_friend_id == ai.id).order_by(AppWhitelist.id.asc())
            )
        ).scalars().all()
    data = serialize_ai_friend(ai, persona_obj=persona, state=state, conversation_id=conv.id, whitelist=whitelist if with_whitelist else None)
    data.setdefault("settings", {})
    data["settings"].setdefault("affinity", 0)
    data["settings"].setdefault("affinity_enabled", True)
    data["kb_ids"] = list(
        (await session.execute(select(PersonaKnowledge.kb_id).where(PersonaKnowledge.persona_id == ai.persona_id))).scalars().all()
    )
    return data


async def _owner(session, ai: AiFriend):
    return await session.get(User, ai.user_id)


@router.get("/personas", summary="可选角色卡")
async def list_personas(user: UserDep, session: SessionDep):
    rows = (
        await session.execute(
            select(Persona)
            .where(
                Persona.enabled.is_(True),
                (Persona.is_public.is_(True)) | (Persona.owner_id == user.id),
            )
            .order_by(Persona.is_public.desc(), Persona.id.asc())
        )
    ).scalars().all()
    out = []
    for p in rows:
        kb_ids = (
            await session.execute(select(PersonaKnowledge.kb_id).where(PersonaKnowledge.persona_id == p.id))
        ).scalars().all()
        out.append(serialize_persona(p, kb_ids=list(kb_ids)))
    return {"personas": out, "can_create_private": await has_private_key(session, user.id)}


@router.post("/personas", summary="创建私人角色卡（需要专属模型 Key）")
async def create_private_persona(payload: PrivatePersonaIn, user: UserDep, session: SessionDep):
    if not await has_private_key(session, user.id):
        raise HTTPException(status_code=403, detail="需要管理员为你分配专属模型 Key 后才能创建私人角色卡")
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
        is_public=False,
        enabled=payload.enabled,
        owner_id=user.id,
    )
    session.add(persona)
    await session.commit()
    await session.refresh(persona)
    await write_log("info", "ai", f"创建私人角色卡「{persona.name}」", user_id=user.id)
    return {"persona": serialize_persona(persona, kb_ids=[])}


@router.put("/personas/{persona_id}", summary="修改私人角色卡")
async def update_private_persona(persona_id: int, payload: PrivatePersonaIn, user: UserDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None or persona.owner_id != user.id:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    persona.name = payload.name.strip()
    persona.avatar = payload.avatar
    persona.tagline = payload.tagline
    persona.description = payload.description
    persona.system_prompt = payload.system_prompt
    persona.personality = payload.personality
    persona.speaking_style = payload.speaking_style
    persona.greeting = payload.greeting
    persona.catchphrases = payload.catchphrases[:12]
    persona.enabled = payload.enabled
    if payload.avatar is not None:
        rows = (
            await session.execute(select(AiFriend).where(AiFriend.persona_id == persona.id))
        ).scalars().all()
        for ai in rows:
            ai.avatar = payload.avatar
    await session.commit()
    return {"persona": serialize_persona(persona, kb_ids=[])}


@router.put("/personas/{persona_id}/avatar", summary="修改私人角色卡/AI 头像")
async def update_private_persona_avatar(persona_id: int, payload: PersonaAvatarIn, user: UserDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None or persona.owner_id != user.id:
        raise HTTPException(status_code=404, detail="私人角色卡不存在")
    persona.avatar = payload.avatar
    rows = (
        await session.execute(select(AiFriend).where(AiFriend.persona_id == persona.id))
    ).scalars().all()
    for ai in rows:
        ai.avatar = payload.avatar
    await session.commit()
    await write_log("info", "ai", f"更新私人角色头像「{persona.name}」", user_id=user.id)
    return {"ok": True, "avatar": payload.avatar, "updated_ai": len(rows)}


@router.get("/private-key", summary="我的专属模型 Key")
async def get_private_key(user: UserDep, session: SessionDep):
    row = (
        await session.execute(
            select(ModelProvider)
            .where(ModelProvider.owner_user_id == user.id)
            .order_by(ModelProvider.id.desc())
        )
    ).scalars().first()
    return {"provider": _private_key_view(row) if row else None}


@router.put("/private-key", summary="保存我的专属模型 Key")
async def save_private_key(payload: PrivateKeyIn, user: UserDep, session: SessionDep):
    base_url = payload.base_url.strip().rstrip("/")
    if not (base_url.startswith("http://") or base_url.startswith("https://")):
        raise HTTPException(status_code=400, detail="Base URL 必须以 http:// 或 https:// 开头")
    row = (
        await session.execute(
            select(ModelProvider)
            .where(ModelProvider.owner_user_id == user.id)
            .order_by(ModelProvider.id.desc())
        )
    ).scalars().first()
    api_key = (payload.api_key or "").strip()
    if row is None:
        if not api_key:
            raise HTTPException(status_code=400, detail="API Key 不能为空")
        row = ModelProvider(
            name=(payload.name or "我的专属Key").strip()[:64] or "我的专属Key",
            kind="chat",
            base_url=base_url,
            api_key=api_key,
            model=payload.model.strip(),
            enabled=True,
            priority=1,
            owner_user_id=user.id,
            extra={"source": "android"},
        )
        session.add(row)
    else:
        if payload.name:
            row.name = payload.name.strip()[:64] or row.name
        row.base_url = base_url
        if api_key:
            row.api_key = api_key
        row.model = payload.model.strip()
        row.enabled = True
    await session.commit()
    await session.refresh(row)
    await write_log("info", "ai", "保存专属模型 Key", user_id=user.id)
    return {"provider": _private_key_view(row)}


@router.delete("/private-key", summary="删除我的专属模型 Key")
async def delete_private_key(user: UserDep, session: SessionDep):
    rows = (
        await session.execute(select(ModelProvider).where(ModelProvider.owner_user_id == user.id))
    ).scalars().all()
    for row in rows:
        await session.delete(row)
    await session.commit()
    return {"ok": True}


async def _persona_world_view(session, p: Persona, usage: int = 0) -> dict[str, Any]:
    owner = await session.get(User, p.owner_id) if p.owner_id else None
    data = serialize_persona(p, kb_ids=[])
    extra = p.extra or {}
    data["usage_count"] = int(usage)
    data["author"] = user_brief(owner)
    data["review_status"] = extra.get("review_status") or ("approved" if p.is_public and p.enabled else "draft")
    data["source_persona_id"] = extra.get("source_persona_id")
    return data


def _admin_only(user: User) -> None:
    if user.role not in ("admin", "super_admin"):
        raise HTTPException(status_code=403, detail="只有管理员可以管理角色卡")


@router.get("/personas/world", summary="角色卡大世界首页/搜索")
async def persona_world(
    user: UserDep,
    session: SessionDep,
    page: int = 1,
    q: str = "",
    author: str = "",
):
    page = max(1, page)
    limit = 100  # 50 行 × 2 列
    filters = [Persona.is_public.is_(True), Persona.enabled.is_(True)]
    if q:
        pattern = f"%{q.strip()}%"
        filters.append(or_(
            Persona.name.ilike(pattern),
            Persona.tagline.ilike(pattern),
            Persona.description.ilike(pattern),
        ))
    if author:
        pattern = f"%{author.strip()}%"
        filters.append(or_(User.username.ilike(pattern), User.nickname.ilike(pattern)))
    total_stmt = (
        select(func.count(func.distinct(Persona.id)))
        .select_from(Persona)
        .outerjoin(User, User.id == Persona.owner_id)
        .where(*filters)
    )
    total = int((await session.execute(total_stmt)).scalar() or 0)
    stmt = (
        select(Persona, func.count(AiFriend.id).label("usage_count"))
        .outerjoin(AiFriend, AiFriend.persona_id == Persona.id)
        .outerjoin(User, User.id == Persona.owner_id)
        .where(*filters)
        .group_by(Persona.id)
        .order_by(func.count(AiFriend.id).desc(), Persona.id.asc())
        .offset((page - 1) * limit)
        .limit(limit)
    )
    rows = (await session.execute(stmt)).all()
    items = [await _persona_world_view(session, p, usage) for p, usage in rows]
    pages = max(1, (total + limit - 1) // limit)
    return {"personas": items, "page": page, "pages": pages, "total": total}


@router.get("/personas/mine", summary="我的角色卡（含投稿状态）")
async def my_personas(user: UserDep, session: SessionDep):
    rows = (
        await session.execute(
            select(Persona).where(Persona.owner_id == user.id).order_by(Persona.updated_at.desc())
        )
    ).scalars().all()
    out = []
    for p in rows:
        usage = (
            await session.execute(select(func.count(AiFriend.id)).where(AiFriend.persona_id == p.id))
        ).scalar() or 0
        out.append(await _persona_world_view(session, p, usage))
    return {"personas": out, "can_manage": user.role in ("admin", "super_admin")}


@router.post("/personas/{persona_id}/submit", summary="投稿私人角色卡到角色卡大世界")
async def submit_persona(persona_id: int, user: UserDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None or persona.owner_id != user.id or persona.is_public:
        raise HTTPException(status_code=404, detail="私人角色卡不存在")
    # 投稿后原卡直接转为公共卡（待审核状态），不再保留私人副本。
    extra = dict(persona.extra or {})
    extra["review_status"] = "pending"
    extra["submitted_at"] = utcnow().isoformat(timespec="seconds")
    extra.pop("source_persona_id", None)
    persona.extra = extra
    persona.is_public = True
    persona.enabled = False
    await session.commit()
    await session.refresh(persona)
    await write_log("info", "ai", f"投稿角色卡「{persona.name}」", user_id=user.id)
    return {"persona": await _persona_world_view(session, persona, 0), "message": "已提交管理员审核"}


@router.get("/personas/pending", summary="待审核角色卡（管理员）")
async def pending_personas(user: UserDep, session: SessionDep):
    _admin_only(user)
    rows = (
        await session.execute(
            select(Persona)
            .where(Persona.is_public.is_(True), Persona.enabled.is_(False))
            .order_by(Persona.updated_at.desc())
        )
    ).scalars().all()
    out = []
    for p in rows:
        out.append(await _persona_world_view(session, p, 0))
    return {"personas": out}


@router.get("/personas/admin/public", summary="公共角色卡管理（管理员）")
async def admin_public_personas(user: UserDep, session: SessionDep, q: str = ""):
    _admin_only(user)
    stmt = select(Persona, func.count(AiFriend.id)).outerjoin(
        AiFriend, AiFriend.persona_id == Persona.id
    ).where(Persona.is_public.is_(True)).group_by(Persona.id)
    if q:
        pattern = f"%{q.strip()}%"
        stmt = stmt.where(or_(Persona.name.ilike(pattern), Persona.tagline.ilike(pattern)))
    rows = (await session.execute(stmt.order_by(Persona.id.desc()).limit(300))).all()
    return {"personas": [await _persona_world_view(session, p, usage) for p, usage in rows]}


@router.post("/personas/{persona_id}/approve", summary="通过角色卡审核")
async def approve_persona(persona_id: int, user: UserDep, session: SessionDep):
    _admin_only(user)
    persona = await session.get(Persona, persona_id)
    if persona is None or not persona.is_public:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    persona.enabled = True
    extra = dict(persona.extra or {})
    extra["review_status"] = "approved"
    extra["reviewed_at"] = utcnow().isoformat(timespec="seconds")
    persona.extra = extra
    await session.commit()
    return {"ok": True, "persona": await _persona_world_view(session, persona, 0)}


@router.post("/personas/{persona_id}/reject", summary="拒绝角色卡审核")
async def reject_persona(persona_id: int, user: UserDep, session: SessionDep):
    _admin_only(user)
    persona = await session.get(Persona, persona_id)
    if persona is None or not persona.is_public:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    persona.enabled = True
    extra = dict(persona.extra or {})
    extra["review_status"] = "rejected"
    extra["reviewed_at"] = utcnow().isoformat(timespec="seconds")
    persona.is_public = False
    persona.extra = extra
    await session.commit()
    return {"ok": True}


@router.put("/personas/{persona_id}/admin", summary="管理员编辑公共角色卡")
async def admin_update_persona(persona_id: int, payload: PersonaAdminIn, user: UserDep, session: SessionDep):
    _admin_only(user)
    persona = await session.get(Persona, persona_id)
    if persona is None or not persona.is_public:
        raise HTTPException(status_code=404, detail="公共角色卡不存在")
    if payload.name is not None:
        persona.name = payload.name.strip()[:64] or persona.name
    if payload.avatar is not None:
        persona.avatar = payload.avatar
        rows = (
            await session.execute(select(AiFriend).where(AiFriend.persona_id == persona.id))
        ).scalars().all()
        for ai in rows:
            ai.avatar = payload.avatar
    if payload.tagline is not None:
        persona.tagline = payload.tagline
    if payload.description is not None:
        persona.description = payload.description
    if payload.system_prompt is not None:
        persona.system_prompt = payload.system_prompt
    if payload.personality is not None:
        persona.personality = payload.personality
    if payload.speaking_style is not None:
        persona.speaking_style = payload.speaking_style
    if payload.greeting is not None:
        persona.greeting = payload.greeting
    await session.commit()
    return {"ok": True, "persona": await _persona_world_view(session, persona, 0)}


@router.delete("/personas/{persona_id}/admin", summary="管理员删除公共角色卡")
async def admin_delete_persona(persona_id: int, user: UserDep, session: SessionDep):
    _admin_only(user)
    persona = await session.get(Persona, persona_id)
    if persona is None or not persona.is_public:
        raise HTTPException(status_code=404, detail="公共角色卡不存在")
    used = (await session.execute(select(func.count(AiFriend.id)).where(AiFriend.persona_id == persona.id))).scalar() or 0
    if used:
        raise HTTPException(status_code=409, detail=f"还有 {used} 个 AI 好友在使用这张角色卡")
    await session.execute(delete(PersonaKnowledge).where(PersonaKnowledge.persona_id == persona.id))
    await session.delete(persona)
    await session.commit()
    return {"ok": True}


@router.delete("/personas/{persona_id}", summary="删除私人角色卡")
async def delete_private_persona(persona_id: int, user: UserDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None or persona.owner_id != user.id:
        raise HTTPException(status_code=404, detail="角色卡不存在")
    used = (
        await session.execute(select(func.count(AiFriend.id)).where(AiFriend.persona_id == persona.id))
    ).scalar() or 0
    if used:
        raise HTTPException(status_code=409, detail=f"该角色卡已被 {used} 个 AI 好友使用，请先删除对应 AI 好友")
    await session.execute(PersonaKnowledge.__table__.delete().where(PersonaKnowledge.persona_id == persona.id))
    await session.delete(persona)
    await session.commit()
    return {"ok": True}


class PrivateKbIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    description: str | None = None
    kind: str = "fact"


class PrivateKbUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    description: str | None = None
    kind: str | None = None
    enabled: bool | None = None


class PersonaAvatarIn(BaseModel):
    avatar: str = Field(min_length=1)


class PrivateKeyIn(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    base_url: str = Field(min_length=1, max_length=255)
    api_key: str | None = Field(default=None, max_length=500)
    model: str = Field(default="", max_length=128)


class PersonaAdminIn(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    avatar: str | None = None
    tagline: str | None = Field(default=None, max_length=128)
    description: str | None = None
    system_prompt: str | None = None
    personality: str | None = None
    speaking_style: str | None = None
    greeting: str | None = None


def _mask_api_key(value: str | None) -> str:
    if not value:
        return ""
    if len(value) <= 8:
        return "****"
    return value[:4] + "..." + value[-4:]


def _private_key_view(row: ModelProvider) -> dict[str, Any]:
    return {
        "id": row.id,
        "name": row.name,
        "base_url": row.base_url,
        "model": row.model,
        "masked_key": _mask_api_key(row.api_key),
        "has_key": bool(row.api_key),
        "enabled": row.enabled,
    }


class PrivateKbTextIn(BaseModel):
    title: str = Field(default="导入文本", max_length=200)
    text: str = Field(min_length=1, max_length=300000)


class PrivateKbImageIn(BaseModel):
    title: str = Field(default="聊天截图", max_length=200)
    image_url: str = Field(min_length=1)


class PersonaKbIn(BaseModel):
    kb_ids: list[int] = Field(default_factory=list)


def _private_kb_view(kb: KnowledgeBase, docs: int, chunks: int, persona_ids: list[int]) -> dict[str, Any]:
    return {
        "id": kb.id,
        "name": kb.name,
        "description": kb.description,
        "kind": kb.kind,
        "enabled": kb.enabled,
        "owner_id": kb.owner_id,
        "stats": kb.stats or {},
        "documents": docs,
        "chunks": chunks,
        "persona_ids": persona_ids,
        "created_at": kb.created_at.isoformat() if kb.created_at else None,
    }


async def _persona_ids_for_kb(session, kb_id: int) -> list[int]:
    return list(
        (
            await session.execute(select(PersonaKnowledge.persona_id).where(PersonaKnowledge.kb_id == kb_id))
        ).scalars().all()
    )


async def _own_kb(session, user: User, kb_id: int) -> KnowledgeBase:
    kb = await session.get(KnowledgeBase, kb_id)
    if kb is None or kb.owner_id != user.id:
        raise HTTPException(status_code=404, detail="知识库不存在")
    return kb


async def _ingest_private_text(session, kb: KnowledgeBase, title: str, text: str, source_type: str = "txt") -> dict[str, Any]:
    text = (text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="文本为空")
    title = (title or "导入文本").strip()[:200] or "导入文本"
    try:
        result = await import_chat_text(
            session,
            kb,
            title=title,
            raw_text=text,
            source_type=source_type,
            max_samples=300,
        )
        result["source_type"] = source_type
        return result
    except ValueError:
        pass

    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", text) if p.strip()]
    if not paragraphs:
        paragraphs = [text]
    chunks: list[str] = []
    for paragraph in paragraphs:
        for i in range(0, len(paragraph), 800):
            piece = paragraph[i : i + 800].strip()
            if piece:
                chunks.append(piece)
    doc = KnowledgeDocument(
        kb_id=kb.id,
        title=title,
        source_type=source_type,
        raw_text=text[:2_000_000],
        target_speaker="资料",
        message_count=len(chunks),
        chunk_count=len(chunks),
        meta={"format": "plain", "filename": title},
    )
    session.add(doc)
    await session.flush()
    for content in chunks:
        keywords = re.findall(r"[\u4e00-\u9fa5]{2,4}", content)
        session.add(
            KnowledgeChunk(
                kb_id=kb.id,
                doc_id=doc.id,
                speaker="资料",
                content=content,
                kind="dialogue",
                keywords=keywords[:8],
                quality=0.55,
            )
        )
    stats = dict(kb.stats or {})
    stats["documents"] = int(stats.get("documents", 0)) + 1
    stats["chunks"] = int(stats.get("chunks", 0)) + len(chunks)
    stats["messages"] = int(stats.get("messages", 0)) + len(chunks)
    stats["last_import_at"] = utcnow().isoformat(timespec="seconds")
    kb.stats = stats
    await session.commit()
    return {
        "document_id": doc.id,
        "messages": len(chunks),
        "samples": len(chunks),
        "target_speaker": "资料",
        "source_type": source_type,
        "style_profile": kb.style_profile or {},
    }


@router.get("/knowledge", summary="我的私人知识库")
async def list_private_knowledge(user: UserDep, session: SessionDep):
    rows = (
        await session.execute(
            select(KnowledgeBase).where(KnowledgeBase.owner_id == user.id).order_by(KnowledgeBase.id.desc())
        )
    ).scalars().all()
    out = []
    for kb in rows:
        docs, chunks = await kb_counters(session, kb.id)
        out.append(_private_kb_view(kb, docs, chunks, await _persona_ids_for_kb(session, kb.id)))
    return {"knowledge_bases": out}


@router.post("/knowledge", summary="新建私人知识库")
async def create_private_knowledge(payload: PrivateKbIn, user: UserDep, session: SessionDep):
    if not await has_private_key(session, user.id):
        raise HTTPException(status_code=403, detail="需要管理员分配专属模型 Key 后才能创建私人知识库")
    kb = KnowledgeBase(
        name=payload.name.strip(),
        description=payload.description,
        kind=payload.kind if payload.kind in ("style", "fact") else "fact",
        enabled=True,
        owner_id=user.id,
        style_profile={},
        stats={},
    )
    session.add(kb)
    await session.commit()
    await session.refresh(kb)
    await write_log("info", "knowledge", f"创建私人知识库「{kb.name}」", user_id=user.id)
    return {"knowledge_base": _private_kb_view(kb, 0, 0, [])}


@router.put("/knowledge/{kb_id}", summary="修改私人知识库类型/信息")
async def update_private_knowledge(kb_id: int, payload: PrivateKbUpdate, user: UserDep, session: SessionDep):
    kb = await _own_kb(session, user, kb_id)
    old_kind = kb.kind
    if payload.name is not None and payload.name.strip():
        kb.name = payload.name.strip()[:64]
    if payload.description is not None:
        kb.description = payload.description
    if payload.kind in ("style", "fact"):
        kb.kind = payload.kind
    if payload.enabled is not None:
        kb.enabled = payload.enabled
    await session.commit()
    rebuilt = False
    if kb.kind == "style" and (old_kind != "style" or not (kb.style_profile or {})):
        rebuilt = await rebuild_style_from_documents(session, kb)
        await session.refresh(kb)
    return {
        "ok": True,
        "rebuilt": rebuilt,
        "knowledge_base": _private_kb_view(
            kb,
            *(await kb_counters(session, kb.id)),
            await _persona_ids_for_kb(session, kb.id),
        ),
    }


@router.delete("/knowledge/{kb_id}", summary="删除私人知识库")
async def delete_private_knowledge(kb_id: int, user: UserDep, session: SessionDep):
    kb = await _own_kb(session, user, kb_id)
    await session.execute(delete(KnowledgeChunk).where(KnowledgeChunk.kb_id == kb.id))
    await session.execute(delete(KnowledgeDocument).where(KnowledgeDocument.kb_id == kb.id))
    await session.execute(delete(PersonaKnowledge).where(PersonaKnowledge.kb_id == kb.id))
    await session.delete(kb)
    await session.commit()
    return {"ok": True}


@router.post("/knowledge/{kb_id}/import-text", summary="导入 TXT / 文本")
async def import_private_text(kb_id: int, payload: PrivateKbTextIn, user: UserDep, session: SessionDep):
    kb = await _own_kb(session, user, kb_id)
    return await _ingest_private_text(session, kb, payload.title, payload.text, "txt")


@router.post("/knowledge/{kb_id}/import-image", summary="导入聊天记录长截图（视觉识别）")
async def import_private_image(kb_id: int, payload: PrivateKbImageIn, user: UserDep, session: SessionDep):
    kb = await _own_kb(session, user, kb_id)
    provider = await pick_provider(session, "vision", user_id=user.id)
    if provider.is_demo:
        raise HTTPException(status_code=400, detail="还没有配置视觉模型，请先在管理面板添加 vision 模型")
    try:
        text = await vision_ocr(provider, payload.image_url)
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=f"视觉模型调用失败: {exc}") from exc
    if not text.strip():
        raise HTTPException(status_code=400, detail="视觉模型没有识别到文字")
    result = await _ingest_private_text(session, kb, payload.title, text, "ocr")
    result["ocr_text"] = text[:1000]
    return result


@router.put("/personas/{persona_id}/knowledge", summary="给私人角色卡配置知识库")
async def set_persona_knowledge(persona_id: int, payload: PersonaKbIn, user: UserDep, session: SessionDep):
    persona = await session.get(Persona, persona_id)
    if persona is None or persona.owner_id != user.id:
        raise HTTPException(status_code=404, detail="私人角色卡不存在")
    kb_ids = list(dict.fromkeys(payload.kb_ids or []))
    if kb_ids:
        owned = (
            await session.execute(
                select(KnowledgeBase.id).where(
                    KnowledgeBase.id.in_(kb_ids), KnowledgeBase.owner_id == user.id
                )
            )
        ).scalars().all()
        if len(owned) != len(kb_ids):
            raise HTTPException(status_code=403, detail="只能绑定自己的私人知识库")
    await session.execute(delete(PersonaKnowledge).where(PersonaKnowledge.persona_id == persona.id))
    for kb_id in kb_ids:
        session.add(PersonaKnowledge(persona_id=persona.id, kb_id=kb_id, weight=1.0))
    await session.commit()
    return {"ok": True, "kb_ids": kb_ids}


@router.get("/friends", summary="我的 AI 好友")
async def list_ai_friends(user: UserDep, session: SessionDep):
    rows = (
        await session.execute(select(AiFriend).where(AiFriend.user_id == user.id).order_by(AiFriend.id.asc()))
    ).scalars().all()
    return {"ai_friends": [await _ai_view(session, ai) for ai in rows]}


@router.post("/friends", summary="添加 AI 好友")
async def create_ai_friend(payload: AiFriendIn, user: UserDep, session: SessionDep):
    persona = await session.get(Persona, payload.persona_id)
    if persona is None or not persona.enabled:
        raise HTTPException(status_code=404, detail="角色卡不存在或已停用")
    if not persona.is_public and persona.owner_id not in (None, user.id):
        raise HTTPException(status_code=403, detail="该角色卡未公开")
    exists = (
        await session.execute(
            select(AiFriend).where(AiFriend.user_id == user.id, AiFriend.persona_id == persona.id)
        )
    ).scalars().first()
    if exists:
        return {"ai_friend": await _ai_view(session, exists)}
    app_settings = await get_settings_map(session)
    ai = AiFriend(
        user_id=user.id,
        persona_id=persona.id,
        name=(payload.name or persona.name)[:32],
        avatar=persona.avatar,
        status="active",
        root_enabled=False,
        settings={
            "idle_minutes": int(app_settings.get("default_idle_minutes", 30)),
            "min_delay_minutes": int(app_settings.get("proactive_min_minutes", 60)),
            "max_delay_minutes": int(app_settings.get("proactive_max_minutes", 180)),
            "max_streak": int(app_settings.get("proactive_max_streak", 3)),
            "proactive_enabled": True,
            "proactive_sources": app_settings.get("proactive_sources") or ["recent_chat", "weather", "smalltalk"],
            "usage_cooldown_minutes": int(app_settings.get("usage_reply_cooldown_minutes", 15)),
            "memory_enabled": True,
            "affinity": 0,
            "affinity_enabled": True,
            "split_replies": False,
            "user_segment_delay": 0,
        },
    )
    session.add(ai)
    await session.flush()
    for package, label in DEFAULT_WHITELIST:
        session.add(AppWhitelist(ai_friend_id=ai.id, package_name=package, label=label, enabled=True))
    await session.commit()
    await session.refresh(ai)
    try:
        await engine.create_greeting(session, ai, user, app_settings)
    except Exception as exc:
        await write_log("warning", "ai", f"AI 好友欢迎语失败: {exc}", ai_friend_id=ai.id)
    return {"ai_friend": await _ai_view(session, ai)}


@router.get("/friends/{ai_id}", summary="AI 好友详情")
async def get_ai_friend(ai_id: int, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    data = await _ai_view(session, ai)
    app_settings = await get_settings_map(session)
    data["defaults"] = {
        "idle_minutes": app_settings.get("default_idle_minutes", 30),
        "min_delay_minutes": app_settings.get("proactive_min_minutes", 60),
        "max_delay_minutes": app_settings.get("proactive_max_minutes", 180),
        "max_streak": app_settings.get("proactive_max_streak", 3),
    }
    return {"ai_friend": data}


@router.put("/friends/{ai_id}", summary="修改 AI 好友设置")
async def update_ai_friend(ai_id: int, payload: AiFriendUpdate, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    if payload.name is not None:
        ai.name = payload.name.strip()[:32] or ai.name
    if payload.avatar is not None:
        ai.avatar = payload.avatar
    if payload.status in ("active", "disabled"):
        ai.status = payload.status
    if payload.root_enabled is not None:
        ai.root_enabled = payload.root_enabled
        if payload.root_enabled and ai.status != "active":
            ai.status = "active"
    if payload.settings is not None:
        ai.settings = _sanitize_settings(payload.settings, ai.settings or {})
    await session.commit()
    await write_log(
        "info",
        "ai",
        f"更新 AI 好友「{ai.name}」设置（root={'开' if ai.root_enabled else '关'}）",
        user_id=user.id,
        ai_friend_id=ai.id,
    )
    return {"ai_friend": await _ai_view(session, ai)}


@router.delete("/friends/{ai_id}", summary="删除 AI 好友")
async def delete_ai_friend(ai_id: int, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    conv_ids = (
        await session.execute(select(Conversation.id).where(Conversation.ai_friend_id == ai.id))
    ).scalars().all()
    if conv_ids:
        await session.execute(delete(Message).where(Message.conversation_id.in_(conv_ids)))
        await session.execute(delete(ConversationMember).where(ConversationMember.conversation_id.in_(conv_ids)))
        await session.execute(delete(Conversation).where(Conversation.id.in_(conv_ids)))
    await session.execute(delete(Memory).where(Memory.ai_friend_id == ai.id))
    await session.execute(delete(UsageEvent).where(UsageEvent.ai_friend_id == ai.id))
    await session.execute(delete(AppWhitelist).where(AppWhitelist.ai_friend_id == ai.id))
    await session.execute(delete(ProactiveState).where(ProactiveState.ai_friend_id == ai.id))
    await session.execute(delete(ConversationAiMember).where(ConversationAiMember.ai_friend_id == ai.id))
    await session.delete(ai)
    await session.commit()
    return {"ok": True}


@router.get("/friends/{ai_id}/conversation", summary="AI 会话")
async def ai_conversation(ai_id: int, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    conv = await engine.open_conversation(session, ai, user)
    rows = (
        await session.execute(
            select(Message).where(Message.conversation_id == conv.id).order_by(Message.id.desc()).limit(50)
        )
    ).scalars().all()
    return {
        "conversation_id": conv.id,
        "messages": [serialize_message(m) for m in reversed(rows)],
        "ai_friend": await _ai_view(session, ai, with_whitelist=False),
    }


@router.post("/friends/{ai_id}/chat", summary="和 AI 好友聊天")
async def chat_with_ai(ai_id: int, payload: ChatIn, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    if ai.status != "active":
        raise HTTPException(status_code=400, detail="该 AI 好友已被停用")
    conv = await engine.open_conversation(session, ai, user)
    text = payload.content.strip()
    message = Message(
        conversation_id=conv.id,
        sender_type="user",
        sender_id=user.id,
        sender_name=user.nickname,
        content=text,
    )
    session.add(message)
    await session.flush()
    conv.last_message_at = utcnow()
    conv.last_message_preview = truncate(text, 120)
    await session.commit()
    await session.refresh(message)

    state = await engine.ensure_state(session, ai, user, conv.id)
    state.phase = "active"
    state.streak = 0
    state.next_proactive_at = None
    state.last_user_at = utcnow()
    await session.commit()

    reply = await engine.respond(session, ai_friend=ai, user=user, conversation=conv, user_message=message)
    return {
        "message": serialize_message(message),
        "reply": serialize_message(reply),
        "conversation_id": conv.id,
    }


@router.get("/friends/{ai_id}/messages", summary="AI 会话历史")
async def ai_messages(ai_id: int, user: UserDep, session: SessionDep, before_id: int | None = None, limit: int = 30):
    ai = await _get_ai(session, user, ai_id)
    conv = await engine.ensure_conversation(session, ai, user)
    stmt = select(Message).where(Message.conversation_id == conv.id)
    if before_id:
        stmt = stmt.where(Message.id < before_id)
    rows = (
        await session.execute(stmt.order_by(Message.id.desc()).limit(max(1, min(limit, 100))))
    ).scalars().all()
    return {"messages": [serialize_message(m) for m in reversed(rows)], "conversation_id": conv.id}


@router.get("/friends/{ai_id}/whitelist", summary="白名单应用")
async def list_whitelist(ai_id: int, user: UserDep, session: SessionDep):
    await _get_ai(session, user, ai_id)
    rows = (
        await session.execute(
            select(AppWhitelist).where(AppWhitelist.ai_friend_id == ai_id).order_by(AppWhitelist.id.asc())
        )
    ).scalars().all()
    return {"whitelist": [whitelist_item(w) for w in rows]}


@router.post("/friends/{ai_id}/whitelist", summary="添加白名单应用")
async def add_whitelist(ai_id: int, payload: WhitelistIn, user: UserDep, session: SessionDep):
    await _get_ai(session, user, ai_id)
    exists = (
        await session.execute(
            select(AppWhitelist).where(
                AppWhitelist.ai_friend_id == ai_id, AppWhitelist.package_name == payload.package_name.strip()
            )
        )
    ).scalars().first()
    if exists:
        exists.label = payload.label or exists.label
        exists.note = payload.note or exists.note
        exists.enabled = payload.enabled
        await session.commit()
        return {"whitelist": whitelist_item(exists)}
    item = AppWhitelist(
        ai_friend_id=ai_id,
        package_name=payload.package_name.strip(),
        label=(payload.label or payload.package_name).strip()[:64],
        note=payload.note,
        enabled=payload.enabled,
    )
    session.add(item)
    await session.commit()
    await session.refresh(item)
    return {"whitelist": whitelist_item(item)}


@router.put("/friends/{ai_id}/whitelist/{item_id}", summary="修改白名单应用")
async def update_whitelist(ai_id: int, item_id: int, payload: WhitelistPatch, user: UserDep, session: SessionDep):
    await _get_ai(session, user, ai_id)
    item = await session.get(AppWhitelist, item_id)
    if item is None or item.ai_friend_id != ai_id:
        raise HTTPException(status_code=404, detail="白名单项不存在")
    if payload.label is not None:
        item.label = payload.label
    if payload.note is not None:
        item.note = payload.note
    if payload.enabled is not None:
        item.enabled = payload.enabled
    await session.commit()
    return {"whitelist": whitelist_item(item)}


@router.delete("/friends/{ai_id}/whitelist/{item_id}", summary="删除白名单应用")
async def delete_whitelist(ai_id: int, item_id: int, user: UserDep, session: SessionDep):
    await _get_ai(session, user, ai_id)
    item = await session.get(AppWhitelist, item_id)
    if item is None or item.ai_friend_id != ai_id:
        raise HTTPException(status_code=404, detail="白名单项不存在")
    await session.delete(item)
    await session.commit()
    return {"ok": True}


@router.post("/friends/{ai_id}/usage-events", summary="上报应用使用事件（root 权限）")
async def report_usage(ai_id: int, payload: UsageIn, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    app_settings = await get_settings_map(session)
    results = await engine.process_usage(
        session, ai_friend=ai, user=user, events=payload.events, app_settings=app_settings
    )
    return {"root_enabled": ai.root_enabled, "results": results}


@router.get("/friends/{ai_id}/usage-events", summary="最近应用事件")
async def list_usage(ai_id: int, user: UserDep, session: SessionDep, limit: int = 50):
    await _get_ai(session, user, ai_id)
    rows = (
        await session.execute(
            select(UsageEvent)
            .where(UsageEvent.ai_friend_id == ai_id)
            .order_by(UsageEvent.id.desc())
            .limit(max(1, min(limit, 200)))
        )
    ).scalars().all()
    return {"events": [usage_event(e) for e in rows]}


@router.get("/friends/{ai_id}/memories", summary="记忆列表")
async def list_memories(ai_id: int, user: UserDep, session: SessionDep, kind: str | None = None):
    await _get_ai(session, user, ai_id)
    now = utcnow()
    stmt = select(Memory).where(Memory.ai_friend_id == ai_id, Memory.user_id == user.id)
    if kind in ("long", "short"):
        stmt = stmt.where(Memory.kind == kind)
    else:
        stmt = stmt.where((Memory.kind == "long") | (Memory.expires_at.is_(None)) | (Memory.expires_at > now))
    rows = (await session.execute(stmt.order_by(Memory.id.desc()).limit(200))).scalars().all()
    return {"memories": [memory_item(m) for m in rows]}


@router.delete("/friends/{ai_id}/memories/{memory_id}", summary="删除一条记忆")
async def delete_memory(ai_id: int, memory_id: int, user: UserDep, session: SessionDep):
    await _get_ai(session, user, ai_id)
    mem = await session.get(Memory, memory_id)
    if mem is None or mem.ai_friend_id != ai_id:
        raise HTTPException(status_code=404, detail="记忆不存在")
    await session.delete(mem)
    await session.commit()
    return {"ok": True}


@router.get("/friends/{ai_id}/proactive", summary="主动消息状态")
async def proactive_state(ai_id: int, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    state = await engine.ensure_state(session, ai, user)
    return {
        "phase": state.phase,
        "streak": state.streak,
        "total_proactive": state.total_proactive,
        "last_user_at": state.last_user_at.isoformat() if state.last_user_at else None,
        "last_ai_at": state.last_ai_at.isoformat() if state.last_ai_at else None,
        "next_proactive_at": state.next_proactive_at.isoformat() if state.next_proactive_at else None,
    }


@router.post("/friends/{ai_id}/proactive/trigger", summary="立即触发一次主动消息")
async def trigger_proactive(ai_id: int, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    conv = await engine.open_conversation(session, ai, user)
    state = await engine.ensure_state(session, ai, user, conv.id)
    msg = await engine.proactive(session, state, force=True)
    if msg is None:
        raise HTTPException(status_code=400, detail="主动消息未生成")
    return {"message": serialize_message(msg)}


@router.post("/friends/{ai_id}/reset", summary="重置会话（保留长期记忆）")
async def reset_conversation(ai_id: int, payload: ResetIn, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    conv = await engine.ensure_conversation(session, ai, user)
    if payload.clear_messages:
        await session.execute(delete(Message).where(Message.conversation_id == conv.id))
        conv.last_message_at = utcnow()
        conv.last_message_preview = None
    state = await engine.ensure_state(session, ai, user, conv.id)
    state.phase = "active"
    state.streak = 0
    state.next_proactive_at = None
    state.last_user_at = utcnow()
    await session.commit()
    greeting = None
    if payload.greeting:
        app_settings = await get_settings_map(session)
        greeting_msg = await engine.create_greeting(session, ai, user, app_settings)
        greeting = serialize_message(greeting_msg) if greeting_msg else None
    return {"ok": True, "greeting": greeting}


@router.post("/friends/{ai_id}/image", summary="让 AI 生成一张图片")
async def make_image(ai_id: int, payload: ImageIn, user: UserDep, session: SessionDep):
    ai = await _get_ai(session, user, ai_id)
    provider = await pick_provider(session, "image", user_id=user.id)
    try:
        result = await generate_image(provider, payload.prompt, size=payload.size or "1024x1024")
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=f"图像模型调用失败: {exc}")
    message = None
    if payload.post:
        conv = await engine.open_conversation(session, ai, user)
        message = await engine.store_ai_message(
            session,
            conversation=conv,
            ai_friend=ai,
            content=result.get("url") or "",
            meta={"prompt": payload.prompt, "image": True, "demo": result.get("demo", False)},
            content_type="image",
        )
    return {"url": result.get("url"), "demo": result.get("demo", False), "message": serialize_message(message) if message else None}
