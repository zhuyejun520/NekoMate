from __future__ import annotations

from datetime import timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import Message, User
from ..utils import utcnow

MONEY_TYPES = ("transfer", "redpacket")
OPEN_STATUSES = ("pending", "open")


def _add_coins(user: User, amount: int) -> None:
    extra = dict(user.extra or {})
    extra["coins"] = int(extra.get("coins", 200)) + amount
    user.extra = extra


async def refund_money_message(session: AsyncSession, message: Message) -> bool:
    meta = dict(message.meta or {})
    if str(meta.get("status")) not in OPEN_STATUSES and str(meta.get("status")) != "accepted":
        return False
    sender = await session.get(User, message.sender_id)
    amount = int(meta.get("amount") or 0)
    if sender is None or amount <= 0:
        return False
    _add_coins(sender, amount)
    meta["status"] = "refunded"
    meta["refunded_at"] = utcnow().isoformat(timespec="seconds")
    message.meta = meta
    return True


async def expire_money_messages(session: AsyncSession) -> int:
    cutoff = utcnow() - timedelta(hours=24)
    rows = (
        await session.execute(
            select(Message).where(
                Message.content_type.in_(MONEY_TYPES),
                Message.created_at < cutoff,
            )
        )
    ).scalars().all()
    changed = 0
    for message in rows:
        meta = message.meta or {}
        if str(meta.get("status")) not in OPEN_STATUSES:
            continue
        if await refund_money_message(session, message):
            changed += 1
    if changed:
        await session.commit()
    return changed
