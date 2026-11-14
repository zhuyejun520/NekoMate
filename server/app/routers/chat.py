from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import func, or_, select

from ..ai.engine import CompanionEngine
from ..ai.providers import ProviderError, pick_provider, pick_stt_provider, transcribe_audio, vision_describe
from ..config import settings
from ..database import SessionLocal
from ..deps import SessionDep, UserDep
from ..models import (
    AiFriend,
    Conversation,
    ConversationAiMember,
    ConversationMember,
    ConversationPref,
    Friendship,
    Message,
    Persona,
    ProactiveState,
    User,
    UserBlock,
)
from ..serializers import conversation as serialize_conversation
from ..serializers import message as serialize_message
from ..serializers import user_brief
from ..services.logger import write_log
from ..services.media import delete_media_if_unused, media_name
from ..services.money import expire_money_messages
from ..services.push import manager as ws_manager
from ..utils import truncate, utcnow
from .friends import ensure_direct_conversation

router = APIRouter(prefix="/api/v1/conversations", tags=["会话"])
engine = CompanionEngine()
_ai_debounce: dict[int, tuple[asyncio.Task, int]] = {}
_ai_debounce_seq = 0
_ai_composing: dict[int, bool] = {}


async def _delayed_ai_reply(conv_id: int, ai_id: int, user_id: int, delay: float, seq: int) -> None:
    try:
        await asyncio.sleep(max(0.2, delay))
        current = _ai_debounce.get(conv_id)
        if not current or current[1] != seq:
            return
        async with SessionLocal() as session:
            conv = await session.get(Conversation, conv_id)
            ai = await session.get(AiFriend, ai_id)
            user = await session.get(User, user_id)
            if conv is None or ai is None or user is None:
                return
            rows = (
                await session.execute(
                    select(Message)
                    .where(Message.conversation_id == conv_id)
                    .order_by(Message.id.desc())
                    .limit(30)
                )
            ).scalars().all()
            rows = list(reversed(rows))
            last_ai = -1
            for i, msg in enumerate(rows):
                if msg.sender_type == "ai":
                    last_ai = i
            pending = [m for m in rows[last_ai + 1:] if m.sender_type == "user"]
            if not pending:
                return
            merged = "\n".join(m.content for m in pending if m.content)
            last_user = pending[-1]
            await engine.respond(
                session,
                ai_friend=ai,
                user=user,
                conversation=conv,
                user_message=last_user,
                user_text=merged,
                mode="chat",
            )
    except Exception as exc:
        await write_log("error", "chat", f"分段等待回复失败: {exc}", conversation_id=conv_id)
    finally:
        current = _ai_debounce.get(conv_id)
        if current and current[1] == seq:
            _ai_debounce.pop(conv_id, None)


def _schedule_ai_reply(conv_id: int, ai_id: int, user_id: int, delay: float) -> None:
    global _ai_debounce_seq
    if _ai_composing.get(conv_id):
        return
    previous = _ai_debounce.get(conv_id)
    if previous:
        previous[0].cancel()
    _ai_debounce_seq += 1
    seq = _ai_debounce_seq
    task = asyncio.create_task(_delayed_ai_reply(conv_id, ai_id, user_id, delay, seq))
    _ai_debounce[conv_id] = (task, seq)


class DirectIn(BaseModel):
    user_id: int


class GroupIn(BaseModel):
    title: str = Field(min_length=1, max_length=64)
    member_ids: list[int] = Field(default_factory=list)
    ai_friend_ids: list[int] = Field(default_factory=list)


class MessageIn(BaseModel):
    content: str = Field(default="", max_length=4000)
    content_type: str = "text"
    image_url: str | None = None
    sticker_url: str | None = None
    audio_url: str | None = None
    duration: int | None = None
    words: str | None = Field(default=None, max_length=32)


class ComposingIn(BaseModel):
    active: bool


class ConversationSettingsIn(BaseModel):
    muted: bool | None = None
    pinned: bool | None = None


class MemberIn(BaseModel):
    user_id: int | None = None
    user_ids: list[int] = Field(default_factory=list)
    ai_friend_ids: list[int] = Field(default_factory=list)


async def _membership(session, conversation_id: int, user_id: int) -> ConversationMember | None:
    return (
        await session.execute(
            select(ConversationMember).where(
                ConversationMember.conversation_id == conversation_id, ConversationMember.user_id == user_id
            )
        )
    ).scalars().first()


async def _pref(session, conversation_id: int, user_id: int, create: bool = False) -> ConversationPref | None:
    pref = (
        await session.execute(
            select(ConversationPref).where(
                ConversationPref.conversation_id == conversation_id, ConversationPref.user_id == user_id
            )
        )
    ).scalars().first()
    if pref is None and create:
        pref = ConversationPref(conversation_id=conversation_id, user_id=user_id)
        session.add(pref)
        await session.flush()
    return pref


async def _direct_other(session, conversation_id: int, user_id: int) -> int | None:
    ids = await _member_ids(session, conversation_id)
    return next((mid for mid in ids if mid != user_id), None)


async def _blocked(session, user_a: int, user_b: int) -> bool:
    row = (
        await session.execute(
            select(UserBlock.id).where(
                or_(
                    (UserBlock.blocker_id == user_a) & (UserBlock.blocked_id == user_b),
                    (UserBlock.blocker_id == user_b) & (UserBlock.blocked_id == user_a),
                )
            )
        )
    ).scalars().first()
    return row is not None


async def _member_ids(session, conversation_id: int) -> list[int]:
    return list(
        (
            await session.execute(
                select(ConversationMember.user_id).where(ConversationMember.conversation_id == conversation_id)
            )
        ).scalars().all()
    )


async def _ai_member_rows(session, conversation_id: int) -> list[ConversationAiMember]:
    return list(
        (
            await session.execute(
                select(ConversationAiMember).where(ConversationAiMember.conversation_id == conversation_id)
            )
        ).scalars().all()
    )


async def _ai_member_views(session, conversation_id: int) -> list[dict]:
    out: list[dict] = []
    for row in await _ai_member_rows(session, conversation_id):
        ai = await session.get(AiFriend, row.ai_friend_id)
        if ai is None:
            continue
        persona = await session.get(Persona, ai.persona_id)
        out.append(
            {
                "id": ai.id,
                "kind": "ai",
                "name": ai.name,
                "nickname": ai.name,
                "avatar": ai.avatar or (persona.avatar if persona else None),
                "persona_id": ai.persona_id,
                "persona_name": persona.name if persona else ai.name,
                "owner_id": ai.user_id,
                "added_by": row.added_by,
            }
        )
    return out


async def conversation_view(session, conv: Conversation, viewer: User) -> dict:
    await expire_money_messages(session)
    member_ids = await _member_ids(session, conv.id)
    pref = await _pref(session, conv.id, viewer.id)
    cleared = pref.cleared_before_id if pref else 0
    last = (
        await session.execute(
            select(Message)
            .where(Message.conversation_id == conv.id, Message.id > cleared)
            .order_by(Message.id.desc())
            .limit(1)
        )
    ).scalars().first()
    member = await _membership(session, conv.id, viewer.id)
    last_read = max(member.last_read_message_id if member else 0, cleared)
    unread = (
        await session.execute(
            select(func.count(Message.id)).where(
                Message.conversation_id == conv.id,
                Message.id > (last_read or 0),
                or_(Message.sender_type == "ai", Message.sender_id != viewer.id),
            )
        )
    ).scalar() or 0

    target = None
    title = conv.title
    avatar = conv.avatar
    if conv.type == "direct":
        other_id = next((mid for mid in member_ids if mid != viewer.id), None)
        other = await session.get(User, other_id) if other_id else None
        if other:
            fr_remark = None
            target = user_brief(other)
            title = target["nickname"]
            avatar = other.avatar
    elif conv.type == "ai":
        ai = await session.get(AiFriend, conv.ai_friend_id) if conv.ai_friend_id else None
        if ai:
            persona = await session.get(Persona, ai.persona_id)
            target = {
                "id": ai.id,
                "kind": "ai",
                "nickname": ai.name,
                "avatar": ai.avatar or persona.avatar if persona else ai.avatar,
                "persona_id": ai.persona_id,
                "persona_name": persona.name if persona else ai.name,
                "root_enabled": ai.root_enabled,
            }
            title = ai.name
            avatar = ai.avatar
    extra = {
        "title": title,
        "avatar": avatar,
        "target": target,
        "last_message": serialize_message(last) if last else None,
        "last_message_preview": last.content if last else "",
        "unread": int(unread),
        "muted": bool(pref.muted) if pref else False,
        "pinned": bool(pref.pinned) if pref else False,
        "cleared_before_id": int(cleared),
        "member_ids": member_ids,
        "members_count": len(member_ids),
        "ai_members": [],
        "ai_member_ids": [],
    }
    if conv.type == "group":
        ai_members = await _ai_member_views(session, conv.id)
        extra["ai_members"] = ai_members
        extra["ai_member_ids"] = [m["id"] for m in ai_members]
        user_members = []
        for uid in member_ids:
            member_user = await session.get(User, uid)
            if member_user:
                user_members.append(user_brief(member_user))
        extra["user_members"] = user_members
        extra["members_count"] = len(member_ids) + len(ai_members)
    return serialize_conversation(conv, extra=extra)


async def _touch_conversation(session, conv: Conversation, message: Message) -> None:
    conv.last_message_at = message.created_at or utcnow()
    conv.last_message_preview = truncate(message.content, 120)


async def _push_message(session, conv: Conversation, message: Message, exclude_user: int | None = None) -> None:
    payload = {"type": "message", "conversation_id": conv.id, "message": serialize_message(message)}
    for uid in await _member_ids(session, conv.id):
        if exclude_user is not None and uid == exclude_user:
            continue
        await ws_manager.send_user(uid, payload)


async def _group_ai_replies(conversation_id: int, trigger_id: int) -> None:
    """Generate replies from AI members without blocking the sender's request."""
    from ..database import SessionLocal

    try:
        async with SessionLocal() as session:
            group = await session.get(Conversation, conversation_id)
            trigger = await session.get(Message, trigger_id)
            if group is None or trigger is None or group.type != "group":
                return
            for row in await _ai_member_rows(session, conversation_id):
                ai = await session.get(AiFriend, row.ai_friend_id)
                if ai is None or ai.status != "active":
                    continue
                try:
                    await engine.group_reply(session, ai_friend=ai, group=group, trigger=trigger)
                except Exception as exc:
                    await write_log(
                        "error",
                        "ai",
                        f"群聊 AI 回复失败: {exc}",
                        ai_friend_id=ai.id,
                        conversation_id=conversation_id,
                    )
    except Exception as exc:
        await write_log("error", "ai", f"群聊 AI 任务失败: {exc}", conversation_id=conversation_id)


@router.get("", summary="会话列表")
async def list_conversations(user: UserDep, session: SessionDep):
    conv_ids = (
        await session.execute(select(ConversationMember.conversation_id).where(ConversationMember.user_id == user.id))
    ).scalars().all()
    if not conv_ids:
        return {"conversations": []}
    rows = (
        await session.execute(
            select(Conversation).where(Conversation.id.in_(conv_ids)).order_by(Conversation.last_message_at.desc().nullslast())
        )
    ).scalars().all()
    items = [await conversation_view(session, conv, user) for conv in rows]
    items.sort(key=lambda x: x.get("last_message_at") or "", reverse=True)
    return {"conversations": items}


@router.post("/direct", summary="创建/获取私聊")
async def create_direct(payload: DirectIn, user: UserDep, session: SessionDep):
    target = await session.get(User, payload.user_id)
    if target is None or target.status != "active":
        raise HTTPException(status_code=404, detail="用户不存在")
    if target.id == user.id:
        raise HTTPException(status_code=400, detail="不能和自己聊天")
    conv = await ensure_direct_conversation(session, user.id, target.id)
    return {"conversation": await conversation_view(session, conv, user)}


@router.post("/group", summary="创建群聊")
async def create_group(payload: GroupIn, user: UserDep, session: SessionDep):
    conv = Conversation(type="group", title=payload.title.strip(), owner_id=user.id, meta={"created_by": user.id})
    session.add(conv)
    await session.flush()
    session.add(ConversationMember(conversation_id=conv.id, user_id=user.id, role="owner"))
    added: set[int] = {user.id}
    for mid in payload.member_ids:
        if mid in added:
            continue
        member_user = await session.get(User, mid)
        if member_user is None:
            continue
        session.add(ConversationMember(conversation_id=conv.id, user_id=mid, role="member"))
        added.add(mid)
    ai_added: list[str] = []
    for aid in payload.ai_friend_ids:
        ai = await session.get(AiFriend, aid)
        if ai is None or ai.user_id != user.id:
            continue
        exists = (
            await session.execute(
                select(ConversationAiMember).where(
                    ConversationAiMember.conversation_id == conv.id,
                    ConversationAiMember.ai_friend_id == ai.id,
                )
            )
        ).scalars().first()
        if exists:
            continue
        session.add(ConversationAiMember(conversation_id=conv.id, ai_friend_id=ai.id, added_by=user.id))
        ai_added.append(ai.name)
    content = f"{user.nickname} 创建了群聊「{conv.title}」"
    if ai_added:
        content += "，并邀请 AI「" + "、".join(ai_added) + "」加入"
    system_msg = Message(
        conversation_id=conv.id,
        sender_type="system",
        sender_id=None,
        sender_name="系统",
        content=content,
        content_type="system",
    )
    session.add(system_msg)
    conv.last_message_at = utcnow()
    conv.last_message_preview = system_msg.content
    await session.commit()
    await session.refresh(conv)
    for mid in added:
        if mid != user.id:
            await ws_manager.send_user(mid, {"type": "conversation_created", "conversation_id": conv.id})
    return {"conversation": await conversation_view(session, conv, user)}


@router.get("/{conversation_id}", summary="会话详情")
async def get_conversation(conversation_id: int, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    if conv is None or await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    data = await conversation_view(session, conv, user)
    members = []
    for mid in data["member_ids"]:
        m = await session.get(User, mid)
        if m:
            items = user_brief(m)
            items["role"] = "owner" if m.id == conv.owner_id else "member"
            members.append(items)
    data["members"] = members
    return {"conversation": data}


@router.get("/{conversation_id}/messages", summary="拉取消息")
async def list_messages(conversation_id: int, user: UserDep, session: SessionDep, before_id: int | None = None, limit: int = 30):
    if await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    await expire_money_messages(session)
    limit = max(1, min(limit, 100))
    pref = await _pref(session, conversation_id, user.id)
    cleared = pref.cleared_before_id if pref else 0
    stmt = select(Message).where(Message.conversation_id == conversation_id, Message.id > cleared)
    if before_id:
        stmt = stmt.where(Message.id < before_id)
    rows = (await session.execute(stmt.order_by(Message.id.desc()).limit(limit))).scalars().all()
    rows = list(reversed(rows))
    return {"messages": [serialize_message(m) for m in rows], "has_more": len(rows) >= limit}


@router.get("/{conversation_id}/settings", summary="聊天设置")
async def get_conversation_settings(conversation_id: int, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    member = await _membership(session, conversation_id, user.id)
    if conv is None or member is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    pref = await _pref(session, conversation_id, user.id)
    peer = None
    peer_id = None
    remark = None
    blocked = False
    if conv.type == "direct":
        peer_id = await _direct_other(session, conversation_id, user.id)
        if peer_id:
            peer = await session.get(User, peer_id)
            fr = (
                await session.execute(
                    select(Friendship).where(Friendship.user_id == user.id, Friendship.friend_id == peer_id)
                )
            ).scalars().first()
            remark = fr.remark if fr else None
            blocked = await _blocked(session, user.id, peer_id)
    return {
        "conversation_id": conversation_id,
        "peer": user_brief(peer),
        "peer_id": peer_id,
        "remark": remark,
        "muted": bool(pref.muted) if pref else False,
        "pinned": bool(pref.pinned) if pref else False,
        "blocked": blocked,
    }


@router.put("/{conversation_id}/settings", summary="更新聊天设置")
async def update_conversation_settings(
    conversation_id: int, payload: ConversationSettingsIn, user: UserDep, session: SessionDep
):
    if await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    pref = await _pref(session, conversation_id, user.id, create=True)
    if payload.muted is not None:
        pref.muted = payload.muted
    if payload.pinned is not None:
        pref.pinned = payload.pinned
    await session.commit()
    return {"ok": True, "muted": pref.muted, "pinned": pref.pinned}


@router.post("/{conversation_id}/clear", summary="单方面删除聊天记录")
async def clear_conversation(conversation_id: int, user: UserDep, session: SessionDep):
    member = await _membership(session, conversation_id, user.id)
    if member is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    latest = (
        await session.execute(
            select(func.max(Message.id)).where(Message.conversation_id == conversation_id)
        )
    ).scalar() or 0
    pref = await _pref(session, conversation_id, user.id, create=True)
    pref.cleared_before_id = int(latest)
    member.last_read_message_id = int(latest)
    await session.commit()
    return {"ok": True, "cleared_before_id": int(latest)}


@router.post("/{conversation_id}/messages/{message_id}/recall", summary="撤回自己发送的消息")
async def recall_message(conversation_id: int, message_id: int, user: UserDep, session: SessionDep):
    if await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    message = await session.get(Message, message_id)
    if message is None or message.conversation_id != conversation_id:
        raise HTTPException(status_code=404, detail="消息不存在")
    if message.sender_type != "user" or message.sender_id != user.id:
        raise HTTPException(status_code=403, detail="只能撤回自己发送的消息")
    if message.content_type != "recalled":
        original_type = message.content_type
        original_url = (message.meta or {}).get("url", "") if original_type in ("image", "audio") else ""
        message.content = "[消息已撤回]"
        message.content_type = "recalled"
        message.meta = {"recalled": True, "original_type": original_type}
        conv = await session.get(Conversation, conversation_id)
        latest = (
            await session.execute(
                select(func.max(Message.id)).where(Message.conversation_id == conversation_id)
            )
        ).scalar() or 0
        if conv is not None and int(latest) == message_id:
            conv.last_message_preview = "[消息已撤回]"
        await session.commit()
        if original_url:
            await delete_media_if_unused(session, original_url, exclude_message_id=message.id)
        await session.refresh(message)
        if conv is not None:
            await _push_message(session, conv, message, exclude_user=user.id)
    return {"message": serialize_message(message)}


@router.post("/{conversation_id}/messages", summary="发送消息")
async def send_message(conversation_id: int, payload: MessageIn, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    if conv is None or await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if conv.type == "direct":
        peer_id = await _direct_other(session, conversation_id, user.id)
        if peer_id and await _blocked(session, user.id, peer_id):
            raise HTTPException(status_code=403, detail="你们之间已经拉黑，不能发送消息")
    text = payload.content.strip()
    image_url = (payload.image_url or "").strip()
    sticker_url = (payload.sticker_url or "").strip()
    audio_url = (payload.audio_url or "").strip()
    sticker_words = (payload.words or "").strip()
    is_image = payload.content_type == "image"
    is_sticker = payload.content_type == "sticker"
    is_audio = payload.content_type == "audio"
    if is_image and not image_url:
        raise HTTPException(status_code=400, detail="图片地址不能为空")
    if is_audio and not audio_url:
        raise HTTPException(status_code=400, detail="语音地址不能为空")
    if is_sticker:
        if not sticker_url or not sticker_words:
            raise HTTPException(status_code=400, detail="表情包地址和 words 不能为空")
        sticker_words = sticker_words[:32]
        text = f"[{sticker_words}]"
    if not text and not is_image and not is_sticker and not is_audio:
        raise HTTPException(status_code=400, detail="消息不能为空")
    if is_image and not text:
        text = "[图片]"
    if is_audio and not text:
        text = "[语音]"
    if is_sticker:
        meta = {"url": sticker_url, "words": sticker_words}
        content_type = "sticker"
    elif is_image:
        meta = {"url": image_url}
        content_type = "image"
    elif is_audio:
        duration = max(0, min(600, int(payload.duration or 0)))
        meta = {"url": audio_url, "duration": duration}
        content_type = "audio"
    else:
        meta = {}
        content_type = payload.content_type or "text"
    meta["sender_avatar"] = user.avatar or ""
    meta["sender_frame"] = (user.extra or {}).get("avatar_frame", "")

    message = Message(
        conversation_id=conv.id,
        sender_type="user",
        sender_id=user.id,
        sender_name=user.nickname,
        content=text,
        content_type=content_type,
        meta=meta,
    )
    session.add(message)
    await session.flush()
    await _touch_conversation(session, conv, message)
    await session.commit()
    await session.refresh(message)

    if conv.type == "ai":
        ai = await session.get(AiFriend, conv.ai_friend_id) if conv.ai_friend_id else None
        if ai is None or ai.user_id != user.id:
            raise HTTPException(status_code=404, detail="AI 好友不存在")
        state = await engine.ensure_state(session, ai, user, conv.id)
        state.phase = "active"
        state.streak = 0
        state.next_proactive_at = None
        state.last_user_at = message.created_at or utcnow()
        await session.commit()
        extra_context = ""
        if is_sticker:
            extra_context = (
                f"用户发来一个表情包，表情含义是「{sticker_words}」。"
                "请按这个表情的情绪和梗自然回应，不要描述图片，也不要提及识图。"
            )
        elif is_image:
            provider = await pick_provider(session, "vision", user_id=user.id)
            if not provider.is_demo:
                try:
                    desc = await vision_describe(provider, image_url, "" if text == "[图片]" else text)
                except ProviderError:
                    desc = ""
                except Exception:
                    desc = ""
                if desc:
                    message.meta = {**(message.meta or {}), "vision": desc}
                    await session.commit()
                    extra_context = f"用户发来一张图片。图像识别结果：{desc}"
            if not extra_context:
                extra_context = "用户发来一张图片，但图像识别暂时不可用。请自然回应，不要假装看到了具体画面。"
        elif is_audio:
            transcript = ""
            name = media_name(audio_url)
            if name:
                path = (settings.uploads_dir / name).resolve()
                if path.parent == settings.uploads_dir.resolve() and path.is_file():
                    try:
                        provider = await pick_stt_provider(session, user_id=user.id)
                        mime = "audio/wav" if path.suffix.lower() == ".wav" else "audio/mp4"
                        transcript = await transcribe_audio(provider, path, mime)
                    except Exception:
                        transcript = ""
            if transcript:
                extra_context = f"用户发来一条语音，语音转写内容：{transcript}"
            else:
                extra_context = "用户发来一条语音，但语音转写暂时不可用。请自然回应，不要假装听到了具体内容。"
        delay = int((ai.settings or {}).get("user_segment_delay") or 0)
        if delay > 0:
            _schedule_ai_reply(conv.id, ai.id, user.id, float(delay))
            return {
                "message": serialize_message(message),
                "reply": None,
                "pending": True,
                "wait_seconds": delay,
            }
        reply = await engine.respond(
            session,
            ai_friend=ai,
            user=user,
            conversation=conv,
            user_message=message,
            mode="chat",
            extra_context=extra_context,
        )
        return {"message": serialize_message(message), "reply": serialize_message(reply)}
    await _push_message(session, conv, message, exclude_user=user.id)
    if conv.type == "group":
        asyncio.create_task(_group_ai_replies(conv.id, message.id))
        await write_log("debug", "chat", f"群聊 {conv.id} 新消息", user_id=user.id, conversation_id=conv.id)
    return {"message": serialize_message(message), "reply": None}


@router.post("/{conversation_id}/composing", summary="用户是否正在输入")
async def set_composing(conversation_id: int, payload: ComposingIn, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    if conv is None or await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if conv.type != "ai":
        return {"ok": True, "ignored": True}
    ai = await session.get(AiFriend, conv.ai_friend_id) if conv.ai_friend_id else None
    if ai is None or ai.user_id != user.id:
        raise HTTPException(status_code=404, detail="AI 好友不存在")
    if payload.active:
        _ai_composing[conv.id] = True
        current = _ai_debounce.pop(conv.id, None)
        if current:
            current[0].cancel()
    else:
        _ai_composing[conv.id] = False
        delay = int((ai.settings or {}).get("user_segment_delay") or 0)
        if delay > 0:
            _schedule_ai_reply(conv.id, ai.id, user.id, float(delay))
    return {"ok": True, "active": payload.active}


@router.post("/{conversation_id}/read", summary="标记已读")
async def mark_read(conversation_id: int, user: UserDep, session: SessionDep):
    member = await _membership(session, conversation_id, user.id)
    if member is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    latest = (
        await session.execute(
            select(func.max(Message.id)).where(Message.conversation_id == conversation_id)
        )
    ).scalar() or 0
    member.last_read_message_id = int(latest)
    await session.commit()
    return {"ok": True, "last_read_message_id": int(latest)}


@router.post("/{conversation_id}/members", summary="邀请好友 / AI 好友进群")
async def add_member(conversation_id: int, payload: MemberIn, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    if conv is None or conv.type != "group":
        raise HTTPException(status_code=404, detail="群聊不存在")
    if await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=403, detail="你不在该群聊中")

    user_ids = list(dict.fromkeys([*(payload.user_ids or []), *([payload.user_id] if payload.user_id else [])]))
    ai_ids = list(dict.fromkeys(payload.ai_friend_ids or []))
    added_users: list[dict] = []
    added_ai: list[dict] = []

    for mid in user_ids:
        if mid == user.id or await _membership(session, conversation_id, mid) is not None:
            continue
        invitee = await session.get(User, mid)
        if invitee is None or invitee.status != "active":
            continue
        session.add(ConversationMember(conversation_id=conv.id, user_id=mid, role="member"))
        added_users.append(user_brief(invitee))

    for aid in ai_ids:
        ai = await session.get(AiFriend, aid)
        if ai is None or ai.user_id != user.id or ai.status != "active":
            continue
        exists = (
            await session.execute(
                select(ConversationAiMember).where(
                    ConversationAiMember.conversation_id == conv.id,
                    ConversationAiMember.ai_friend_id == ai.id,
                )
            )
        ).scalars().first()
        if exists:
            continue
        session.add(ConversationAiMember(conversation_id=conv.id, ai_friend_id=ai.id, added_by=user.id))
        added_ai.append({"id": ai.id, "name": ai.name, "avatar": ai.avatar})

    if not added_users and not added_ai:
        return {"ok": True, "already": True, "added_users": [], "added_ai": []}

    parts: list[str] = []
    if added_users:
        parts.append("邀请 " + "、".join(u.get("nickname") or u.get("username") or "好友" for u in added_users) + " 加入群聊")
    if added_ai:
        parts.append("邀请 AI「" + "、".join(a["name"] for a in added_ai) + "」加入群聊")
    system_msg = Message(
        conversation_id=conv.id,
        sender_type="system",
        sender_name="系统",
        content=f"{user.nickname} " + "；".join(parts),
        content_type="system",
    )
    session.add(system_msg)
    conv.last_message_at = utcnow()
    conv.last_message_preview = system_msg.content
    await session.commit()
    for item in added_users:
        await ws_manager.send_user(item["id"], {"type": "conversation_created", "conversation_id": conv.id})
    await _push_message(session, conv, system_msg, exclude_user=user.id)
    return {
        "ok": True,
        "added_users": added_users,
        "added_ai": added_ai,
        "conversation": await conversation_view(session, conv, user),
    }


@router.delete("/{conversation_id}/ai-members/{ai_id}", summary="移除群里的 AI 好友")
async def remove_ai_member(conversation_id: int, ai_id: int, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    if conv is None or conv.type != "group":
        raise HTTPException(status_code=404, detail="群聊不存在")
    if await _membership(session, conversation_id, user.id) is None:
        raise HTTPException(status_code=403, detail="你不在该群聊中")
    row = (
        await session.execute(
            select(ConversationAiMember).where(
                ConversationAiMember.conversation_id == conversation_id,
                ConversationAiMember.ai_friend_id == ai_id,
            )
        )
    ).scalars().first()
    if row is None:
        raise HTTPException(status_code=404, detail="AI 好友不在该群聊中")
    ai = await session.get(AiFriend, ai_id)
    if conv.owner_id != user.id and (ai is None or ai.user_id != user.id):
        raise HTTPException(status_code=403, detail="只有群主或 AI 主人可以移除")
    await session.delete(row)
    system_msg = Message(
        conversation_id=conv.id,
        sender_type="system",
        sender_name="系统",
        content=f"{user.nickname} 将 AI「{ai.name if ai else ai_id}」移出了群聊",
        content_type="system",
    )
    session.add(system_msg)
    conv.last_message_at = utcnow()
    conv.last_message_preview = system_msg.content
    await session.commit()
    return {"ok": True}


@router.delete("/{conversation_id}/members/{member_id}", summary="移出成员/退出群聊")
async def remove_member(conversation_id: int, member_id: int, user: UserDep, session: SessionDep):
    conv = await session.get(Conversation, conversation_id)
    if conv is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    me = await _membership(session, conversation_id, user.id)
    if me is None:
        raise HTTPException(status_code=403, detail="你不在该会话中")
    if member_id != user.id and conv.owner_id != user.id:
        raise HTTPException(status_code=403, detail="只有群主可以移出成员")
    member = await _membership(session, conversation_id, member_id)
    if member is None:
        raise HTTPException(status_code=404, detail="成员不存在")
    await session.delete(member)
    system_msg = Message(
        conversation_id=conv.id,
        sender_type="system",
        sender_name="系统",
        content=f"{user.nickname} {'退出了群聊' if member_id == user.id else '被移出群聊'}",
        content_type="system",
    )
    session.add(system_msg)
    conv.last_message_at = utcnow()
    conv.last_message_preview = system_msg.content
    await session.commit()
    return {"ok": True}
