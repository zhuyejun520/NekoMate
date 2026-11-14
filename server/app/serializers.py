from __future__ import annotations

from typing import Any

from .models import (
    AiFriend,
    AppRelease,
    AppWhitelist,
    Conversation,
    FutureTask,
    KnowledgeBase,
    KnowledgeChunk,
    KnowledgeDocument,
    Memory,
    Message,
    ModelProvider,
    Moment,
    Persona,
    PluginRecord,
    ProactiveState,
    SystemLog,
    UsageEvent,
    User,
)
from .utils import iso, mask_secret


def user_brief(user: User | None) -> dict[str, Any]:
    if user is None:
        return {"id": 0, "username": "", "nickname": "已注销用户", "avatar": None}
    extra = user.extra or {}
    return {
        "id": user.id,
        "username": user.username,
        "nickname": user.nickname,
        "avatar": user.avatar,
        "bio": user.bio,
        "city": user.city,
        "gender": extra.get("gender", "secret"),
        "tags": extra.get("tags", []),
        "avatar_frame": extra.get("avatar_frame", ""),
    }


def user_full(user: User, stats: dict[str, Any] | None = None) -> dict[str, Any]:
    data = user_brief(user)
    data.update(
        {
            "role": user.role,
            "status": user.status,
            "must_change_password": user.must_change_password,
            "last_seen_at": iso(user.last_seen_at),
            "created_at": iso(user.created_at),
            "extra": user.extra or {},
        }
    )
    if stats:
        data.update(stats)
    return data


def message(m: Message) -> dict[str, Any]:
    return {
        "id": m.id,
        "conversation_id": m.conversation_id,
        "sender_type": m.sender_type,
        "sender_id": m.sender_id,
        "sender_name": m.sender_name,
        "content": m.content,
        "content_type": m.content_type,
        "meta": m.meta or {},
        "created_at": iso(m.created_at),
    }


def conversation(c: Conversation, *, extra: dict[str, Any] | None = None) -> dict[str, Any]:
    data = {
        "id": c.id,
        "type": c.type,
        "title": c.title,
        "avatar": c.avatar,
        "owner_id": c.owner_id,
        "ai_friend_id": c.ai_friend_id,
        "last_message_at": iso(c.last_message_at),
        "last_message_preview": c.last_message_preview,
        "meta": c.meta or {},
        "created_at": iso(c.created_at),
    }
    if extra:
        data.update(extra)
    return data


def persona(p: Persona, *, kb_ids: list[int] | None = None, extra: dict[str, Any] | None = None) -> dict[str, Any]:
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
        "extra": p.extra or {},
        "created_at": iso(p.created_at),
        "updated_at": iso(p.updated_at),
    }
    if kb_ids is not None:
        data["kb_ids"] = kb_ids
    if extra:
        data.update(extra)
    return data


def ai_friend(a: AiFriend, *, persona_obj: Persona | None = None, state: ProactiveState | None = None,
              conversation_id: int | None = None, whitelist: list[AppWhitelist] | None = None) -> dict[str, Any]:
    data = {
        "id": a.id,
        "user_id": a.user_id,
        "persona_id": a.persona_id,
        "name": a.name,
        "avatar": a.avatar,
        "status": a.status,
        "root_enabled": a.root_enabled,
        "settings": a.settings or {},
        "conversation_id": conversation_id,
        "created_at": iso(a.created_at),
        "persona": persona(persona_obj) if persona_obj else None,
        "proactive": {
            "phase": state.phase if state else "active",
            "streak": state.streak if state else 0,
            "total_proactive": state.total_proactive if state else 0,
            "last_user_at": iso(state.last_user_at) if state else None,
            "last_ai_at": iso(state.last_ai_at) if state else None,
            "next_proactive_at": iso(state.next_proactive_at) if state else None,
        },
    }
    if whitelist is not None:
        data["whitelist"] = [whitelist_item(w) for w in whitelist]
    return data


def whitelist_item(w: AppWhitelist) -> dict[str, Any]:
    return {
        "id": w.id,
        "ai_friend_id": w.ai_friend_id,
        "package_name": w.package_name,
        "label": w.label,
        "note": w.note,
        "enabled": w.enabled,
        "created_at": iso(w.created_at),
    }


def provider(p: ModelProvider, *, reveal: bool = False) -> dict[str, Any]:
    return {
        "id": p.id,
        "name": p.name,
        "kind": p.kind,
        "base_url": p.base_url,
        "api_key": p.api_key if reveal else mask_secret(p.api_key),
        "has_key": bool(p.api_key),
        "model": p.model,
        "enabled": p.enabled,
        "priority": p.priority,
        "owner_user_id": p.owner_user_id,
        "extra": p.extra or {},
        "status": p.status,
        "last_error": p.last_error,
        "last_checked_at": iso(p.last_checked_at),
        "created_at": iso(p.created_at),
    }


def knowledge_base(kb: KnowledgeBase, *, docs: int = 0, chunks: int = 0, personas: list[int] | None = None,
                   extra: dict[str, Any] | None = None) -> dict[str, Any]:
    data = {
        "id": kb.id,
        "name": kb.name,
        "description": kb.description,
        "kind": kb.kind,
        "enabled": kb.enabled,
        "style_profile": kb.style_profile or {},
        "stats": kb.stats or {},
        "docs_count": docs,
        "chunks_count": chunks,
        "persona_ids": personas or [],
        "created_at": iso(kb.created_at),
        "updated_at": iso(kb.updated_at),
    }
    if extra:
        data.update(extra)
    return data


def knowledge_doc(d: KnowledgeDocument) -> dict[str, Any]:
    return {
        "id": d.id,
        "kb_id": d.kb_id,
        "title": d.title,
        "source_type": d.source_type,
        "target_speaker": d.target_speaker,
        "message_count": d.message_count,
        "chunk_count": d.chunk_count,
        "meta": d.meta or {},
        "created_at": iso(d.created_at),
    }


def knowledge_chunk(c: KnowledgeChunk) -> dict[str, Any]:
    return {
        "id": c.id,
        "kb_id": c.kb_id,
        "doc_id": c.doc_id,
        "speaker": c.speaker,
        "content": c.content,
        "kind": c.kind,
        "keywords": c.keywords or [],
        "quality": c.quality,
        "created_at": iso(c.created_at),
    }


def log_item(l: SystemLog) -> dict[str, Any]:
    return {
        "id": l.id,
        "level": l.level,
        "category": l.category,
        "message": l.message,
        "meta": l.meta or {},
        "created_at": iso(l.created_at),
    }


def plugin_record(p: PluginRecord) -> dict[str, Any]:
    return {
        "id": p.id,
        "slug": p.slug,
        "name": p.name,
        "version": p.version,
        "author": p.author,
        "description": p.description,
        "path": p.path,
        "enabled": p.enabled,
        "config": p.config or {},
        "config_schema": p.config_schema or {},
        "commands": p.commands or [],
        "status": p.status,
        "error": p.error,
        "source": p.source,
        "created_at": iso(p.created_at),
    }


def future_task(t: FutureTask, *, runs: list[dict[str, Any]] | None = None) -> dict[str, Any]:
    data = {
        "id": t.id,
        "name": t.name,
        "type": t.type,
        "schedule": t.schedule or {},
        "payload": t.payload or {},
        "user_id": t.user_id,
        "ai_friend_id": t.ai_friend_id,
        "enabled": t.enabled,
        "last_run_at": iso(t.last_run_at),
        "next_run_at": iso(t.next_run_at),
        "run_count": t.run_count,
        "created_at": iso(t.created_at),
    }
    if runs is not None:
        data["runs"] = runs
    return data


def memory_item(m: Memory) -> dict[str, Any]:
    return {
        "id": m.id,
        "kind": m.kind,
        "content": m.content,
        "importance": m.importance,
        "keywords": m.keywords or [],
        "source": m.source,
        "expires_at": iso(m.expires_at),
        "created_at": iso(m.created_at),
    }


def usage_event(e: UsageEvent) -> dict[str, Any]:
    return {
        "id": e.id,
        "package_name": e.package_name,
        "label": e.label,
        "event_type": e.event_type,
        "happened_at": iso(e.happened_at),
        "handled": e.handled,
        "response_text": e.response_text,
        "created_at": iso(e.created_at),
    }


def moment_item(m: Moment, *, author: dict[str, Any] | None, likes: int = 0, liked: bool = False,
                comments: list[dict[str, Any]] | None = None, persona_name: str | None = None) -> dict[str, Any]:
    return {
        "id": m.id,
        "user_id": m.user_id,
        "author": author,
        "content": m.content,
        "images": m.images or [],
        "visibility": m.visibility,
        "likes": likes,
        "liked": liked,
        "comments": comments or [],
        "ai_persona_name": persona_name,
        "created_at": iso(m.created_at),
    }


def app_release(r: AppRelease, *, download_url: str | None = None) -> dict[str, Any]:
    data = {
        "id": r.id,
        "platform": r.platform,
        "version_code": r.version_code,
        "version_name": r.version_name,
        "changelog": r.changelog or "",
        "file_name": r.file_name,
        "file_size": r.file_size,
        "sha256": r.sha256,
        "force_update": r.force_update,
        "created_at": iso(r.created_at),
    }
    if download_url is not None:
        data["download_url"] = download_url
    return data
