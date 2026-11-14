from __future__ import annotations

from urllib.parse import unquote, urlparse

from sqlalchemy import String, cast, select

from ..config import settings
from ..models import AiFriend, Conversation, Message, Moment, Persona, User


def media_name(url: str | None) -> str | None:
    if not url:
        return None
    path = urlparse(url).path
    if "/media/" not in path:
        return None
    name = unquote(path.split("/media/", 1)[1]).strip()
    if not name or "/" in name or "\\" in name or name in (".", ".."):
        return None
    return name


async def delete_media_if_unused(
    session,
    url: str | None,
    *,
    exclude_message_id: int = 0,
    exclude_moment_id: int = 0,
) -> bool:
    """Delete an uploaded media file only when no message/moment/avatar still references it."""
    name = media_name(url)
    if not name:
        return False
    target = (settings.uploads_dir / name).resolve()
    uploads = settings.uploads_dir.resolve()
    if target.parent != uploads or not target.is_file():
        return False
    pattern = f"%{name}%"

    message = (
        await session.execute(
            select(Message.id)
            .where(
                Message.id != exclude_message_id,
                Message.content_type.in_(("image", "sticker", "audio")),
                cast(Message.meta, String).like(pattern),
            )
            .limit(1)
        )
    ).first()
    if message:
        return False

    moment = (
        await session.execute(
            select(Moment.id)
            .where(
                Moment.id != exclude_moment_id,
                cast(Moment.images, String).like(pattern),
            )
            .limit(1)
        )
    ).first()
    if moment:
        return False

    avatar = (
        await session.execute(
            select(Persona.id)
            .where(Persona.avatar.is_not(None), cast(Persona.avatar, String).like(pattern))
            .limit(1)
        )
    ).first()
    if avatar:
        return False
    user_avatar = (
        await session.execute(
            select(User.id)
            .where(User.avatar.is_not(None), cast(User.avatar, String).like(pattern))
            .limit(1)
        )
    ).first()
    if user_avatar:
        return False
    ai_avatar = (
        await session.execute(
            select(AiFriend.id)
            .where(AiFriend.avatar.is_not(None), cast(AiFriend.avatar, String).like(pattern))
            .limit(1)
        )
    ).first()
    if ai_avatar:
        return False
    conv_avatar = (
        await session.execute(
            select(Conversation.id)
            .where(Conversation.avatar.is_not(None), cast(Conversation.avatar, String).like(pattern))
            .limit(1)
        )
    ).first()
    if conv_avatar:
        return False

    try:
        target.unlink(missing_ok=True)
        return True
    except OSError:
        return False
