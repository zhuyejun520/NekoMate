from __future__ import annotations

import asyncio
import random

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select

from ..ai.providers import ProviderError, chat, pick_provider, vision_describe
from ..database import SessionLocal
from ..deps import SessionDep, UserDep
from ..models import (
    AiFriend,
    Friendship,
    Moment,
    MomentAiReaction,
    MomentComment,
    MomentLike,
    Persona,
    User,
)
from ..serializers import moment_item, user_brief
from ..services.logger import write_log
from ..services.media import delete_media_if_unused
from ..services.push import manager as ws_manager

router = APIRouter(prefix="/api/v1/moments", tags=["日常空间"])


class MomentIn(BaseModel):
    content: str = Field(min_length=1, max_length=30000)
    images: list[str] = Field(default_factory=list)
    visibility: str = "friends"


class CommentIn(BaseModel):
    content: str = Field(min_length=1, max_length=500)


async def _friend_ids(session, user_id: int) -> list[int]:
    rows = (
        await session.execute(select(Friendship.friend_id).where(Friendship.user_id == user_id))
    ).scalars().all()
    return list(rows) + [user_id]


async def _ai_react_to_moment(moment_id: int, owner_id: int) -> None:
    """AI friends may like/comment on the owner's moment; affinity raises the chance."""
    try:
        async with SessionLocal() as session:
            moment = await session.get(Moment, moment_id)
            owner = await session.get(User, owner_id)
            if moment is None or owner is None:
                return
            ai_rows = (
                await session.execute(
                    select(AiFriend).where(AiFriend.user_id == owner_id, AiFriend.status == "active")
                )
            ).scalars().all()
            if not ai_rows:
                return
            random.shuffle(ai_rows)
            vision = await pick_provider(session, "vision", user_id=owner_id)
            image_notes = []
            if vision is not None and not vision.is_demo:
                for image_url in (moment.images or [])[:3]:
                    try:
                        desc = await vision_describe(vision, str(image_url), "")
                        if desc:
                            image_notes.append(desc[:500])
                    except Exception:
                        pass
            reacted = 0
            for ai in ai_rows:
                if reacted >= 2:
                    break
                exists = (
                    await session.execute(
                        select(MomentAiReaction.id).where(
                            MomentAiReaction.moment_id == moment.id,
                            MomentAiReaction.ai_friend_id == ai.id,
                        )
                    )
                ).scalars().first()
                if exists:
                    continue
                settings = ai.settings or {}
                affinity = max(0, min(100, int(settings.get("affinity", 0) or 0)))
                chance = min(0.78, 0.10 + affinity * 0.0068)
                if random.random() > chance:
                    continue
                persona = await session.get(Persona, ai.persona_id)
                text = (moment.content or "").replace("<!--md-->", "").strip()
                comment = ""
                chat_provider = await pick_provider(session, "chat", user_id=owner_id)
                if chat_provider is not None and not chat_provider.is_demo:
                    persona_desc = ""
                    if persona is not None:
                        persona_desc = (
                            f"角色名：{persona.name}\n一句话人设：{persona.tagline or ''}\n"
                            f"性格：{persona.personality or ''}\n说话方式：{persona.speaking_style or ''}\n"
                            f"身份背景：{(persona.description or '')[:800]}"
                        )
                    image_text = "\n".join(f"- {x}" for x in image_notes) if image_notes else "（没有可识别的图片内容）"
                    messages = [
                        {
                            "role": "system",
                            "content": (
                                f"你是用户的 AI 好友「{ai.name}」，不是助手。\n{persona_desc}\n"
                                "现在你看到用户发的动态，请以角色身份写一条自然、简短的评论（1-2 句，最多 80 字），"
                                "不要解释你是 AI，不要输出 Markdown，不要每次都夸。"
                            ),
                        },
                        {
                            "role": "user",
                            "content": f"动态文字：{text or '（只有图片）'}\n图片内容：{image_text}\n请评论：",
                        },
                    ]
                    try:
                        comment = await chat(chat_provider, messages, temperature=0.85, max_tokens=160)
                    except (ProviderError, Exception):
                        comment = ""
                comment = (comment or "").strip().strip('"')[:200]
                if not comment:
                    comment = "看到这条动态啦，给你点个赞。"
                reaction = MomentAiReaction(
                    moment_id=moment.id,
                    ai_friend_id=ai.id,
                    comment_text=comment,
                    liked=True,
                )
                session.add(reaction)
                await session.flush()
                await session.commit()
                await ws_manager.send_user(
                    owner_id,
                    {"type": "moment_reaction", "moment_id": moment.id, "ai_friend_id": ai.id},
                )
                await write_log(
                    "debug",
                    "moment",
                    f"AI「{ai.name}」评论动态 {moment.id}，好感度 {affinity}，概率 {chance:.2f}",
                    user_id=owner_id,
                )
                reacted += 1
    except Exception as exc:
        await write_log("warning", "moment", f"AI 动态互动失败: {exc}", user_id=owner_id)


async def _pack(session, moments: list[Moment], viewer: User) -> list[dict]:
    out = []
    for m in moments:
        author = await session.get(User, m.user_id)
        likes = (
            await session.execute(select(func.count(MomentLike.id)).where(MomentLike.moment_id == m.id))
        ).scalar() or 0
        ai_likes = (
            await session.execute(
                select(func.count(MomentAiReaction.id)).where(
                    MomentAiReaction.moment_id == m.id, MomentAiReaction.liked.is_(True)
                )
            )
        ).scalar() or 0
        likes = int(likes) + int(ai_likes)
        liked = (
            await session.execute(
                select(MomentLike.id).where(MomentLike.moment_id == m.id, MomentLike.user_id == viewer.id)
            )
        ).scalars().first() is not None
        comment_rows = (
            await session.execute(
                select(MomentComment).where(MomentComment.moment_id == m.id).order_by(MomentComment.id.asc()).limit(50)
            )
        ).scalars().all()
        comments = []
        for c in comment_rows:
            cu = await session.get(User, c.user_id)
            comments.append(
                {
                    "id": c.id,
                    "user": user_brief(cu),
                    "content": c.content,
                    "created_at": c.created_at.isoformat() if c.created_at else None,
                }
            )
        reactions = (
            await session.execute(
                select(MomentAiReaction)
                .where(MomentAiReaction.moment_id == m.id, MomentAiReaction.comment_text.is_not(None))
                .order_by(MomentAiReaction.id.asc())
                .limit(20)
            )
        ).scalars().all()
        for r in reactions:
            ai = await session.get(AiFriend, r.ai_friend_id)
            if ai is None or not (r.comment_text or "").strip():
                continue
            comments.append(
                {
                    "id": -r.id,
                    "user": {
                        "id": -ai.id,
                        "username": f"ai_{ai.id}",
                        "nickname": ai.name,
                        "avatar": ai.avatar,
                        "bio": "",
                        "city": None,
                        "gender": "secret",
                        "tags": [],
                        "is_ai": True,
                    },
                    "ai_friend_id": ai.id,
                    "is_ai": True,
                    "content": r.comment_text,
                    "created_at": r.created_at.isoformat() if r.created_at else None,
                }
            )
        comments.sort(key=lambda x: x.get("created_at") or "")
        persona_name = None
        if m.ai_persona_id:
            persona = await session.get(Persona, m.ai_persona_id)
            persona_name = persona.name if persona else None
        out.append(
            moment_item(
                m,
                author=user_brief(author),
                likes=int(likes),
                liked=liked,
                comments=comments,
                persona_name=persona_name,
            )
        )
    return out


@router.get("", summary="动态流")
async def timeline(user: UserDep, session: SessionDep, scope: str = "friends", user_id: int | None = None, limit: int = 30):
    limit = max(1, min(limit, 100))
    if scope == "user" and user_id:
        if user_id != user.id and user_id not in await _friend_ids(session, user.id):
            raise HTTPException(status_code=403, detail="只能查看好友的动态")
        stmt = select(Moment).where(Moment.user_id == user_id)
        if user_id != user.id:
            stmt = stmt.where(Moment.visibility.in_(("friends", "public")))
        rows = (
            await session.execute(stmt.order_by(Moment.id.desc()).limit(limit))
        ).scalars().all()
    elif scope == "mine":
        rows = (
            await session.execute(
                select(Moment).where(Moment.user_id == user.id).order_by(Moment.id.desc()).limit(limit)
            )
        ).scalars().all()
    else:
        ids = await _friend_ids(session, user.id)
        rows = (
            await session.execute(
                select(Moment)
                .where(
                    ((Moment.user_id.in_(ids)) & (Moment.visibility.in_(("friends", "public"))))
                    | (Moment.user_id == user.id)
                )
                .order_by(Moment.id.desc())
                .limit(limit)
            )
        ).scalars().all()
    return {"moments": await _pack(session, list(rows), user)}


@router.post("", summary="发布动态")
async def create_moment(payload: MomentIn, user: UserDep, session: SessionDep):
    visibility = payload.visibility if payload.visibility in ("friends", "private", "public") else "friends"
    moment = Moment(
        user_id=user.id,
        content=payload.content.strip(),
        images=payload.images[:9],
        visibility=visibility,
    )
    session.add(moment)
    await session.commit()
    await session.refresh(moment)
    for fid in await _friend_ids(session, user.id):
        if fid != user.id:
            await ws_manager.send_user(fid, {"type": "moment_created", "moment_id": moment.id})
    asyncio.create_task(_ai_react_to_moment(moment.id, user.id))
    return {"moment": (await _pack(session, [moment], user))[0]}


@router.delete("/{moment_id}", summary="删除自己的动态")
async def delete_moment(moment_id: int, user: UserDep, session: SessionDep):
    moment = await session.get(Moment, moment_id)
    if moment is None:
        raise HTTPException(status_code=404, detail="动态不存在")
    if moment.user_id != user.id and user.role != "admin":
        raise HTTPException(status_code=403, detail="只能删除自己的动态")
    images = list(moment.images or [])
    await session.execute(delete(MomentLike).where(MomentLike.moment_id == moment_id))
    await session.execute(delete(MomentComment).where(MomentComment.moment_id == moment_id))
    await session.execute(delete(MomentAiReaction).where(MomentAiReaction.moment_id == moment_id))
    await session.delete(moment)
    await session.commit()
    for image in images:
        await delete_media_if_unused(session, image, exclude_moment_id=moment_id)
    for fid in await _friend_ids(session, user.id):
        if fid != user.id:
            await ws_manager.send_user(fid, {"type": "moment_deleted", "moment_id": moment_id})
    return {"ok": True}


@router.get("/{moment_id}", summary="动态详情")
async def get_moment(moment_id: int, user: UserDep, session: SessionDep):
    moment = await session.get(Moment, moment_id)
    if moment is None:
        raise HTTPException(status_code=404, detail="动态不存在")
    if moment.user_id != user.id and moment.visibility == "private":
        raise HTTPException(status_code=403, detail="无权查看")
    return {"moment": (await _pack(session, [moment], user))[0]}


@router.post("/{moment_id}/like", summary="点赞/取消点赞")
async def toggle_like(moment_id: int, user: UserDep, session: SessionDep):
    moment = await session.get(Moment, moment_id)
    if moment is None:
        raise HTTPException(status_code=404, detail="动态不存在")
    existing = (
        await session.execute(
            select(MomentLike).where(MomentLike.moment_id == moment_id, MomentLike.user_id == user.id)
        )
    ).scalars().first()
    if existing:
        await session.delete(existing)
        liked = False
    else:
        session.add(MomentLike(moment_id=moment_id, user_id=user.id))
        liked = True
    await session.commit()
    if moment.user_id != user.id and liked:
        await ws_manager.send_user(moment.user_id, {"type": "moment_liked", "moment_id": moment_id, "user": user_brief(user)})
    return {"liked": liked}


@router.post("/{moment_id}/comments", summary="评论")
async def add_comment(moment_id: int, payload: CommentIn, user: UserDep, session: SessionDep):
    moment = await session.get(Moment, moment_id)
    if moment is None:
        raise HTTPException(status_code=404, detail="动态不存在")
    comment = MomentComment(moment_id=moment_id, user_id=user.id, content=payload.content.strip())
    session.add(comment)
    await session.commit()
    await session.refresh(comment)
    if moment.user_id != user.id:
        await ws_manager.send_user(
            moment.user_id,
            {
                "type": "moment_comment",
                "moment_id": moment_id,
                "comment": {"id": comment.id, "user": user_brief(user), "content": comment.content},
            },
        )
    return {"comment": {"id": comment.id, "user": user_brief(user), "content": comment.content}}


@router.delete("/{moment_id}", summary="删除动态")
async def delete_moment(moment_id: int, user: UserDep, session: SessionDep):
    moment = await session.get(Moment, moment_id)
    if moment is None or moment.user_id != user.id:
        raise HTTPException(status_code=404, detail="动态不存在")
    images = list(moment.images or [])
    await session.execute(delete(MomentAiReaction).where(MomentAiReaction.moment_id == moment_id))
    await session.delete(moment)
    await session.commit()
    for image in images:
        await delete_media_if_unused(session, image, exclude_moment_id=moment_id)
    return {"ok": True}
