from __future__ import annotations

import random
from datetime import date, timedelta
from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import delete, select

from ..ai.engine import CompanionEngine
from ..ai.providers import analyze_json, pick_provider
from ..deps import SessionDep, UserDep
from ..models import (
    AiFriend,
    Conversation,
    ConversationMember,
    Message,
    Persona,
    StoreItem,
    StorePurchase,
    StickerPack,
    StickerPackItem,
    User,
)
from ..serializers import message as serialize_message
from ..services.logger import write_log
from ..services.money import MONEY_TYPES, refund_money_message
from ..utils import utcnow

router = APIRouter(prefix="/api/v1/store", tags=["商店"])
engine = CompanionEngine()

KINDS = ("theme", "font", "bubble", "avatar_frame", "sticker_pack")


class PurchaseIn(BaseModel):
    item_id: int


class PackIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    logo: str | None = None


class PackItemIn(BaseModel):
    url: str = Field(min_length=1, max_length=2000)


class PackUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    logo: str | None = None


class MoneyIn(BaseModel):
    conversation_id: int
    amount: int = Field(ge=1, le=1000000)
    note: str = Field(default="", max_length=80)


class AvatarFrameIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    price: int = Field(default=0, ge=0, le=1000000)
    image: str = Field(min_length=1, max_length=2000)
    enabled: bool = True


class AvatarFrameUpdate(BaseModel):
    name: str | None = Field(default=None, max_length=64)
    price: int | None = Field(default=None, ge=0, le=1000000)
    image: str | None = None
    enabled: bool | None = None


class WordsIn(BaseModel):
    words: str = Field(min_length=1, max_length=64)


def _is_admin(user: User) -> bool:
    return user.role in ("admin", "super_admin")


def _item_view(item: StoreItem) -> dict[str, Any]:
    return {
        "id": item.id,
        "kind": item.kind,
        "name": item.name,
        "description": item.description,
        "price": item.price,
        "cover_url": item.cover_url,
        "data": item.data or {},
    }


DEFAULT_ITEM_NAMES = {
    ("theme", "雾紫"),
    ("font", "系统黑体"),
    ("bubble", "经典紫"),
    ("avatar_frame", "无边框"),
}


def _is_default_item(item: StoreItem) -> bool:
    return (item.kind, item.name) in DEFAULT_ITEM_NAMES


def _is_deleted_item(item: StoreItem) -> bool:
    return bool((item.data or {}).get("deleted"))


def _pack_view(pack: StickerPack, items: list[StickerPackItem] | None = None) -> dict[str, Any]:
    data = {
        "id": pack.id,
        "name": pack.name,
        "logo": pack.logo,
        "published": pack.published,
        "created_at": pack.created_at.isoformat() if pack.created_at else None,
    }
    if items is not None:
        data["items"] = [
            {"id": i.id, "url": i.url, "words": i.words}
            for i in items
        ]
    return data


async def _coins(user: User, session: SessionDep) -> int:
    if _is_admin(user):
        return 999999999
    extra = dict(user.extra or {})
    if "coins" not in extra:
        extra["coins"] = 200
        user.extra = extra
        await session.commit()
    return int(extra.get("coins") or 0)


async def _seed_store(session: SessionDep) -> None:
    existing = {(row[0], row[1]) for row in (await session.execute(select(StoreItem.kind, StoreItem.name))).all()}
    defaults = [
        {
            "kind": "theme", "name": "雾紫", "description": "默认的夜雾紫主题", "price": 0,
            "cover_url": "🌙", "sort": 10,
            "data": {"bg": "#F5F4FA", "panel": "#FFFFFF", "panel2": "#F0EDF9", "text": "#1D1A2B",
                     "muted": "#6C6784", "muted2": "#9892AC", "accent": "#7A5CFF", "accent2": "#E85D9E",
                     "border": "#E4E0F0"},
        },
        {
            "kind": "theme", "name": "海盐蓝", "description": "清爽的蓝白色主题", "price": 80,
            "cover_url": "🌊", "sort": 20,
            "data": {"bg": "#F3F8FC", "panel": "#FFFFFF", "panel2": "#E7F1F8", "text": "#142331",
                     "muted": "#597184", "muted2": "#8CA3B3", "accent": "#2D8CFF", "accent2": "#39C6C0",
                     "border": "#D6E5EF"},
        },
        {
            "kind": "theme", "name": "橘子橙", "description": "切开的橙子与暖橙色光晕", "price": 120,
            "cover_url": "🍊", "sort": 30,
            "data": {"pattern": "orange", "bg": "#FFF6EA", "panel": "#FFFFFF", "panel2": "#FFE8CC",
                     "text": "#3A220C", "muted": "#8A6648", "muted2": "#B89470",
                     "accent": "#FF8A1F", "accent2": "#FFC24B", "border": "#F3D8B8"},
        },
        {
            "kind": "theme", "name": "柠檬黄", "description": "切开的柠檬与清爽黄色光", "price": 120,
            "cover_url": "🍋", "sort": 40,
            "data": {"pattern": "lemon", "bg": "#FFFDEB", "panel": "#FFFFFF", "panel2": "#FFF6BF",
                     "text": "#3A3508", "muted": "#7C7442", "muted2": "#A69B62",
                     "accent": "#E6C400", "accent2": "#B7D94A", "border": "#EDE39A"},
        },
        {
            "kind": "theme", "name": "枫叶红", "description": "飘落的红色枫叶", "price": 120,
            "cover_url": "🍁", "sort": 50,
            "data": {"pattern": "maple", "bg": "#FFF3EF", "panel": "#FFFFFF", "panel2": "#FFE2DA",
                     "text": "#3B1711", "muted": "#8C554B", "muted2": "#B77E72",
                     "accent": "#D64545", "accent2": "#F08A5D", "border": "#F2CFC5"},
        },
        {
            "kind": "theme", "name": "樱雾粉", "description": "淡粉樱花与雾面晨光", "price": 120,
            "cover_url": "🌸", "sort": 60,
            "data": {"pattern": "sakura", "bg": "#FFF5F8", "panel": "#FFFFFF", "panel2": "#FFE8F0",
                     "text": "#3C1E2A", "muted": "#8A5A6C", "muted2": "#B58A9A",
                     "accent": "#F26A9A", "accent2": "#FFB3CB", "border": "#F1CEDB"},
        },
        {
            "kind": "theme", "name": "墨夜青", "description": "深青夜色与流动冷光", "price": 160,
            "cover_url": "🌌", "sort": 70,
            "data": {"pattern": "night", "bg": "#0E1B22", "panel": "#162831", "panel2": "#1C3540",
                     "text": "#EAF5F7", "muted": "#9DBDC4", "muted2": "#7197A0",
                     "accent": "#35C6C0", "accent2": "#6E8BFF", "border": "#28505A"},
        },
        {
            "kind": "font", "name": "系统黑体", "description": "默认无衬线字体", "price": 0,
            "cover_url": "Aa", "sort": 10, "data": {"font": "sans"},
        },
        {
            "kind": "font", "name": "衬线阅读", "description": "更有书信感的衬线字体", "price": 60,
            "cover_url": "Serif", "sort": 20, "data": {"font": "serif"},
        },
        {
            "kind": "font", "name": "等宽风格", "description": "适合代码感的等宽字体", "price": 60,
            "cover_url": "Mono", "sort": 30, "data": {"font": "mono"},
        },
        {
            "kind": "bubble", "name": "经典紫", "description": "默认聊天气泡", "price": 0,
            "cover_url": "💬", "sort": 10,
            "data": {"mine": "#7A5CFF", "other": "#FFFFFF", "text_mine": "#FFFFFF",
                     "text_other": "#1D1A2B", "radius": 18},
        },
        {
            "kind": "bubble", "name": "青柠苏打", "description": "清亮的青绿色气泡", "price": 70,
            "cover_url": "🍋", "sort": 20,
            "data": {"mine": "#34C7A0", "other": "#FFFFFF", "text_mine": "#FFFFFF",
                     "text_other": "#17352D", "radius": 20},
        },
        {
            "kind": "avatar_frame", "name": "无边框", "description": "默认头像", "price": 0,
            "cover_url": "○", "sort": 10, "data": {"color": ""},
        },
        {
            "kind": "avatar_frame", "name": "日落金", "description": "暖金色头像边框", "price": 50,
            "cover_url": "☀", "sort": 20, "data": {"color": "#FFD166"},
        },
        {
            "kind": "avatar_frame", "name": "霓虹紫", "description": "高亮紫色头像边框", "price": 50,
            "cover_url": "✦", "sort": 30, "data": {"color": "#B16CFF"},
        },
    ]
    for item in defaults:
        if (item["kind"], item["name"]) in existing:
            continue
        session.add(StoreItem(**item))
    await session.commit()


@router.get("", summary="商店商品")
async def store_items(user: UserDep, session: SessionDep):
    await _seed_store(session)
    rows = (
        await session.execute(
            select(StoreItem).where(StoreItem.enabled.is_(True)).order_by(StoreItem.sort.asc(), StoreItem.id.asc())
        )
    ).scalars().all()
    purchased = set(
        (
            await session.execute(select(StorePurchase.item_id).where(StorePurchase.user_id == user.id))
        ).scalars().all()
    )
    return {
        "coins": await _coins(user, session),
        "items": [
            _item_view(i)
            for i in rows
            if i.id not in purchased and not _is_default_item(i)
        ],
    }


@router.get("/inventory", summary="我的商店物品与表情包")
async def inventory(user: UserDep, session: SessionDep):
    await _seed_store(session)
    purchases = (
        await session.execute(select(StorePurchase).where(StorePurchase.user_id == user.id))
    ).scalars().all()
    owned_ids = [p.item_id for p in purchases]
    rows = (
        await session.execute(select(StoreItem).order_by(StoreItem.sort.asc(), StoreItem.id.asc()))
    ).scalars().all()
    owned = []
    seen: set[int] = set()
    for item in rows:
        if item.id in seen:
            continue
        if _is_deleted_item(item):
            continue
        if _is_default_item(item) or item.id in owned_ids:
            owned.append(item)
            seen.add(item.id)
    items = [_item_view(i) for i in owned]
    sticker_packs = []
    for item in items:
        if item["kind"] != "sticker_pack":
            continue
        pack_id = int((item.get("data") or {}).get("pack_id") or 0)
        if not pack_id:
            continue
        pack = await session.get(StickerPack, pack_id)
        if pack is None or not pack.published:
            continue
        pack_items = (
            await session.execute(
                select(StickerPackItem).where(StickerPackItem.pack_id == pack.id).order_by(StickerPackItem.id.asc())
            )
        ).scalars().all()
        if pack_items:
            sticker_packs.append(_pack_view(pack, list(pack_items)))
    return {"coins": await _coins(user, session), "items": items, "sticker_packs": sticker_packs}


@router.post("/purchase", summary="购买商店物品")
async def purchase(payload: PurchaseIn, user: UserDep, session: SessionDep):
    item = await session.get(StoreItem, payload.item_id)
    if item is None or not item.enabled:
        raise HTTPException(status_code=404, detail="商品不存在")
    exists = (
        await session.execute(
            select(StorePurchase).where(
                StorePurchase.user_id == user.id, StorePurchase.item_id == item.id
            )
        )
    ).scalars().first()
    if exists:
        return {"ok": True, "already": True, "coins": await _coins(user, session), "item": _item_view(item)}
    coins = await _coins(user, session)
    if item.price > coins:
        raise HTTPException(status_code=400, detail="金币不足")
    if item.price > 0 and not _is_admin(user):
        extra = dict(user.extra or {})
        extra["coins"] = coins - item.price
        user.extra = extra
    session.add(StorePurchase(user_id=user.id, item_id=item.id))
    await session.commit()
    await write_log("info", "store", f"购买商店物品「{item.name}」", user_id=user.id)
    return {"ok": True, "already": False, "coins": max(0, coins - item.price), "item": _item_view(item)}


# --------------------------------------------------------------- check-in / transfer / red packet


@router.get("/checkin", summary="签到状态")
async def checkin_status(user: UserDep, session: SessionDep):
    extra = dict(user.extra or {})
    today = date.today()
    last = str(extra.get("checkin_last") or "")
    streak = int(extra.get("checkin_streak") or 0)
    return {
        "checked_today": last == today.isoformat(),
        "streak": streak,
        "coins": await _coins(user, session),
        "last": last,
    }


@router.post("/checkin", summary="签到领取金币")
async def checkin(user: UserDep, session: SessionDep):
    extra = dict(user.extra or {})
    today = date.today()
    yesterday = (today - timedelta(days=1)).isoformat()
    last = str(extra.get("checkin_last") or "")
    if last == today.isoformat():
        raise HTTPException(status_code=400, detail="今天已经签到过了")
    old_streak = int(extra.get("checkin_streak") or 0)
    streak = old_streak + 1 if last == yesterday else 1
    reward = random.randint(5, 10)
    bonus = 0
    if streak >= 10:
        bonus = 5
        streak = 0
    coins = await _coins(user, session)
    if not _is_admin(user):
        extra["coins"] = coins + reward + bonus
    extra["checkin_last"] = today.isoformat()
    extra["checkin_streak"] = streak
    user.extra = extra
    await session.commit()
    return {
        "ok": True,
        "reward": reward,
        "bonus": bonus,
        "streak": streak,
        "coins": 999999999 if _is_admin(user) else coins + reward + bonus,
    }


async def _conversation_member(session: SessionDep, conversation_id: int, user_id: int) -> Conversation:
    conv = await session.get(Conversation, conversation_id)
    if conv is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    member = (
        await session.execute(
            select(ConversationMember).where(
                ConversationMember.conversation_id == conversation_id,
                ConversationMember.user_id == user_id,
            )
        )
    ).scalars().first()
    if member is None:
        raise HTTPException(status_code=403, detail="不在该会话中")
    return conv


async def _add_coins(session: SessionDep, user: User, amount: int) -> None:
    if amount == 0:
        return
    extra = dict(user.extra or {})
    extra["coins"] = int(extra.get("coins", 200)) + amount
    user.extra = extra


async def _ai_money_decision(
    session: SessionDep,
    conv: Conversation,
    ai: AiFriend,
    user: User,
    amount: int,
    kind: str,
    note: str,
) -> tuple[bool, str]:
    provider = await pick_provider(session, "analysis", user_id=user.id)
    if provider.is_demo:
        return False, "这个我不能收，退给你啦。"
    persona = await session.get(Persona, ai.persona_id)
    history = (
        await session.execute(
            select(Message)
            .where(Message.conversation_id == conv.id)
            .order_by(Message.id.desc())
            .limit(12)
        )
    ).scalars().all()
    lines = [
        f"{m.sender_name or ('我' if m.sender_type == 'ai' else '用户')}: {m.content[:120]}"
        for m in reversed(history)
        if m.content
    ]
    system = (
        f"你在扮演「{ai.name}」。{persona.personality if persona else ''}\n"
        "用户给你发了一个金币转账或红包。请结合最近聊天、你对用户的态度和角色设定，"
        "判断是否应该收下。不要因为金额大小改变角色立场。\n"
        '只输出 JSON：{"accept": true/false, "reply": "一句自然的中文回复"}'
    )
    context = "最近对话：\n" + ("\n".join(lines) if lines else "（暂无）")
    query = (
        f"{context}\n\n用户发了：{'转账' if kind == 'transfer' else '红包'}\n"
        f"金额：{amount} 金币\n留言：{note or '无'}\n"
        "如果要收下，reply 表达接受；如果不收，reply 说明退回。"
    )
    try:
        data = await analyze_json(provider, system, query)
    except Exception:
        data = None
    if not isinstance(data, dict):
        return False, "这个我不能收，退给你啦。"
    accept = bool(data.get("accept", False))
    reply = str(data.get("reply") or ("谢谢，那我收下啦。" if accept else "这个我不能收，退给你啦。"))[:300]
    return accept, reply


@router.post("/transfer", summary="向好友或 AI 转账金币")
async def transfer(payload: MoneyIn, user: UserDep, session: SessionDep):
    conv = await _conversation_member(session, payload.conversation_id, user.id)
    if conv.type not in ("direct", "ai"):
        raise HTTPException(status_code=400, detail="转账只能在私聊或 AI 会话中使用")
    coins = await _coins(user, session)
    if not _is_admin(user) and payload.amount > coins:
        raise HTTPException(status_code=400, detail="金币不足")
    if not _is_admin(user):
        extra = dict(user.extra or {})
        extra["coins"] = coins - payload.amount
        user.extra = extra

    if conv.type == "ai":
        ai = await session.get(AiFriend, conv.ai_friend_id) if conv.ai_friend_id else None
        if ai is None:
            raise HTTPException(status_code=404, detail="AI 好友不存在")
        message = Message(
            conversation_id=conv.id,
            sender_type="user",
            sender_id=user.id,
            sender_name=user.nickname,
            content="[转账]",
            content_type="transfer",
            meta={
                "amount": payload.amount,
                "note": payload.note.strip(),
                "status": "pending",
                "ai_friend_id": ai.id,
                "sender_avatar": user.avatar or "",
                "sender_frame": (user.extra or {}).get("avatar_frame", ""),
            },
        )
        session.add(message)
        await session.commit()
        await session.refresh(message)
        accept, reply = await _ai_money_decision(
            session, conv, ai, user, payload.amount, "transfer", payload.note.strip()
        )
        meta = dict(message.meta or {})
        meta["status"] = "accepted" if accept else "refunded"
        if not accept and not _is_admin(user):
            await _add_coins(session, user, payload.amount)
        message.meta = meta
        await session.commit()
        await engine.store_ai_message(
            session, conversation=conv, ai_friend=ai, content=reply,
            meta={"money_message_id": message.id, "money_status": meta["status"]},
        )
        return {
            "ok": True,
            "accepted": accept,
            "coins": 999999999 if _is_admin(user) else (coins - payload.amount if accept else coins),
            "message": serialize_message(message),
        }

    members = (
        await session.execute(
            select(ConversationMember.user_id).where(ConversationMember.conversation_id == conv.id)
        )
    ).scalars().all()
    target_id = next((mid for mid in members if mid != user.id), None)
    if not target_id:
        raise HTTPException(status_code=400, detail="没有可转账的对象")
    target = await session.get(User, target_id)
    if target is None:
        raise HTTPException(status_code=404, detail="收款用户不存在")
    await _add_coins(session, target, payload.amount)
    message = Message(
        conversation_id=conv.id,
        sender_type="user",
        sender_id=user.id,
        sender_name=user.nickname,
        content="[转账]",
        content_type="transfer",
        meta={
            "amount": payload.amount,
            "note": payload.note.strip(),
            "status": "done",
            "to_user_id": target.id,
            "to_name": target.nickname,
            "sender_avatar": user.avatar or "",
            "sender_frame": (user.extra or {}).get("avatar_frame", ""),
        },
    )
    session.add(message)
    await session.commit()
    await session.refresh(message)
    return {
        "ok": True,
        "coins": 999999999 if _is_admin(user) else coins - payload.amount,
        "message": serialize_message(message),
    }


@router.post("/redpacket", summary="发送金币红包")
async def redpacket(payload: MoneyIn, user: UserDep, session: SessionDep):
    conv = await _conversation_member(session, payload.conversation_id, user.id)
    coins = await _coins(user, session)
    if not _is_admin(user) and payload.amount > coins:
        raise HTTPException(status_code=400, detail="金币不足")
    if not _is_admin(user):
        extra = dict(user.extra or {})
        extra["coins"] = coins - payload.amount
        user.extra = extra
    is_ai = conv.type == "ai"
    ai = await session.get(AiFriend, conv.ai_friend_id) if is_ai and conv.ai_friend_id else None
    message = Message(
        conversation_id=conv.id,
        sender_type="user",
        sender_id=user.id,
        sender_name=user.nickname,
        content="[红包]",
        content_type="redpacket",
        meta={
            "amount": payload.amount,
            "note": payload.note.strip() or "恭喜发财，大吉大利",
            "status": "pending" if is_ai else "open",
            "sender_id": user.id,
            "sender_avatar": user.avatar or "",
            "sender_frame": (user.extra or {}).get("avatar_frame", ""),
            **({"ai_friend_id": ai.id} if ai else {}),
        },
    )
    session.add(message)
    await session.commit()
    await session.refresh(message)
    if is_ai and ai is not None:
        accept, reply = await _ai_money_decision(
            session, conv, ai, user, payload.amount, "redpacket", payload.note.strip()
        )
        meta = dict(message.meta or {})
        meta["status"] = "accepted" if accept else "refunded"
        if not accept and not _is_admin(user):
            await _add_coins(session, user, payload.amount)
        message.meta = meta
        await session.commit()
        await engine.store_ai_message(
            session, conversation=conv, ai_friend=ai, content=reply,
            meta={"money_message_id": message.id, "money_status": meta["status"]},
        )
    return {
        "ok": True,
        "coins": 999999999 if _is_admin(user) else coins - payload.amount,
        "message": serialize_message(message),
    }


@router.post("/redpacket/{message_id}/claim", summary="领取金币红包")
async def claim_redpacket(message_id: int, user: UserDep, session: SessionDep):
    message = await session.get(Message, message_id)
    if message is None or message.content_type != "redpacket":
        raise HTTPException(status_code=404, detail="红包不存在")
    conv = await _conversation_member(session, message.conversation_id, user.id)
    if conv.type == "ai":
        raise HTTPException(status_code=400, detail="AI 红包由 AI 自己决定是否收下")
    meta = dict(message.meta or {})
    if str(meta.get("status")) != "open":
        raise HTTPException(status_code=400, detail="红包已经被领取")
    if message.sender_id == user.id:
        raise HTTPException(status_code=400, detail="不能领取自己发的红包")
    amount = int(meta.get("amount") or 0)
    if amount <= 0:
        raise HTTPException(status_code=400, detail="红包金额无效")
    await _add_coins(session, user, amount)
    meta["status"] = "claimed"
    meta["claimed_by"] = user.id
    meta["claimed_name"] = user.nickname
    message.meta = meta
    await session.commit()
    return {"ok": True, "amount": amount, "coins": await _coins(user, session), "message": serialize_message(message)}


@router.post("/money/{message_id}/return", summary="退回转账或红包")
async def return_money(message_id: int, user: UserDep, session: SessionDep):
    message = await session.get(Message, message_id)
    if message is None or message.content_type not in MONEY_TYPES:
        raise HTTPException(status_code=404, detail="交易不存在")
    conv = await _conversation_member(session, message.conversation_id, user.id)
    meta = dict(message.meta or {})
    status = str(meta.get("status"))
    if status == "refunded":
        raise HTTPException(status_code=400, detail="已经退回")
    amount = int(meta.get("amount") or 0)
    if amount <= 0:
        raise HTTPException(status_code=400, detail="金额无效")

    sender = message.sender_id == user.id
    recipient = (meta.get("to_user_id") == user.id) or (meta.get("claimed_by") == user.id)
    ai_transaction = conv.type == "ai"
    if sender and status in ("pending", "open") or (sender and ai_transaction and status == "accepted"):
        await _add_coins(session, user, amount)
    elif recipient and status in ("done", "accepted", "claimed"):
        coins = await _coins(user, session)
        if not _is_admin(user) and coins < amount:
            raise HTTPException(status_code=400, detail="金币不足，无法退回")
        if not _is_admin(user):
            extra = dict(user.extra or {})
            extra["coins"] = coins - amount
            user.extra = extra
        source = await session.get(User, message.sender_id)
        if source:
            await _add_coins(session, source, amount)
    else:
        raise HTTPException(status_code=403, detail="当前状态不能退回")
    meta["status"] = "refunded"
    meta["refunded_at"] = utcnow().isoformat(timespec="seconds")
    message.meta = meta
    await session.commit()
    return {"ok": True, "message": serialize_message(message)}


# --------------------------------------------------------------- admin avatar frames


def _frame_view(item: StoreItem) -> dict[str, Any]:
    data = item.data or {}
    image = str(data.get("image") or "")
    return {
        "id": item.id,
        "name": item.name,
        "price": item.price,
        "image": image,
        "cover_url": item.cover_url or image,
        "enabled": item.enabled,
        "is_default": _is_default_item(item),
    }


@router.get("/admin/avatar-frames", summary="管理员头像框列表")
async def admin_avatar_frames(user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    rows = (
        await session.execute(
            select(StoreItem).where(StoreItem.kind == "avatar_frame").order_by(StoreItem.sort.asc(), StoreItem.id.asc())
        )
    ).scalars().all()
    return {"frames": [_frame_view(i) for i in rows if not _is_deleted_item(i)]}


@router.post("/admin/avatar-frames", summary="管理员上传头像框")
async def admin_create_avatar_frame(payload: AvatarFrameIn, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    image = payload.image.strip()
    item = StoreItem(
        kind="avatar_frame",
        name=payload.name.strip()[:64],
        description="自定义 PNG 头像框",
        price=payload.price,
        cover_url=image,
        sort=500,
        enabled=payload.enabled,
        data={"image": image},
    )
    session.add(item)
    await session.commit()
    await session.refresh(item)
    return {"ok": True, "frame": _frame_view(item)}


@router.put("/admin/avatar-frames/{frame_id}", summary="管理员修改头像框")
async def admin_update_avatar_frame(
    frame_id: int, payload: AvatarFrameUpdate, user: UserDep, session: SessionDep
):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    item = await session.get(StoreItem, frame_id)
    if item is None or item.kind != "avatar_frame":
        raise HTTPException(status_code=404, detail="头像框不存在")
    if payload.name is not None and payload.name.strip():
        item.name = payload.name.strip()[:64]
    if payload.price is not None:
        item.price = payload.price
    if payload.image is not None and payload.image.strip():
        image = payload.image.strip()
        item.cover_url = image
        item.data = {**(item.data or {}), "image": image}
    if payload.enabled is not None:
        item.enabled = payload.enabled
    await session.commit()
    return {"ok": True, "frame": _frame_view(item)}


@router.delete("/admin/avatar-frames/{frame_id}", summary="管理员删除头像框")
async def admin_delete_avatar_frame(frame_id: int, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    item = await session.get(StoreItem, frame_id)
    if item is None or item.kind != "avatar_frame":
        raise HTTPException(status_code=404, detail="头像框不存在")
    if _is_default_item(item):
        raise HTTPException(status_code=400, detail="默认头像框不能删除")
    await session.execute(delete(StorePurchase).where(StorePurchase.item_id == item.id))
    item.enabled = False
    item.data = {**(item.data or {}), "deleted": True}
    await session.commit()
    return {"ok": True}


# --------------------------------------------------------------- admin sticker packs


@router.get("/admin/sticker-packs", summary="管理端表情包组列表")
async def admin_packs(user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    packs = (
        await session.execute(select(StickerPack).order_by(StickerPack.id.desc()))
    ).scalars().all()
    out = []
    for pack in packs:
        items = (
            await session.execute(
                select(StickerPackItem).where(StickerPackItem.pack_id == pack.id).order_by(StickerPackItem.id.asc())
            )
        ).scalars().all()
        view = _pack_view(pack, list(items))
        view["missing_words"] = sum(1 for i in items if not (i.words or "").strip())
        out.append(view)
    return {"packs": out}


@router.post("/admin/sticker-packs", summary="管理员创建表情包组")
async def admin_create_pack(payload: PackIn, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = StickerPack(
        name=payload.name.strip()[:64],
        logo=(payload.logo or "").strip()[:2000] or "📦",
        owner_id=user.id,
        published=False,
    )
    session.add(pack)
    await session.commit()
    await session.refresh(pack)
    return {"ok": True, "pack": _pack_view(pack, [])}


@router.get("/admin/sticker-packs/{pack_id}", summary="管理端表情包组详情")
async def admin_pack(pack_id: int, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = await session.get(StickerPack, pack_id)
    if pack is None:
        raise HTTPException(status_code=404, detail="表情包组不存在")
    items = (
        await session.execute(
            select(StickerPackItem).where(StickerPackItem.pack_id == pack.id).order_by(StickerPackItem.id.asc())
        )
    ).scalars().all()
    return {"pack": _pack_view(pack, list(items))}


@router.post("/admin/sticker-packs/{pack_id}/items", summary="管理员添加表情图片")
async def admin_add_pack_item(pack_id: int, payload: PackItemIn, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = await session.get(StickerPack, pack_id)
    if pack is None:
        raise HTTPException(status_code=404, detail="表情包组不存在")
    if pack.published:
        raise HTTPException(status_code=400, detail="已上架的表情包组不能直接加图，请先下架或新建组")
    item = StickerPackItem(pack_id=pack.id, url=payload.url.strip(), words="")
    session.add(item)
    await session.commit()
    await session.refresh(item)
    return {"ok": True, "item": {"id": item.id, "url": item.url, "words": item.words}}


@router.put("/admin/sticker-items/{item_id}", summary="管理员填写表情 words")
async def admin_set_words(item_id: int, payload: WordsIn, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    item = await session.get(StickerPackItem, item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="表情不存在")
    pack = await session.get(StickerPack, item.pack_id)
    if pack and pack.published:
        raise HTTPException(status_code=400, detail="已上架的表情包不能修改")
    item.words = payload.words.strip()[:64]
    await session.commit()
    return {"ok": True, "item": {"id": item.id, "url": item.url, "words": item.words}}


@router.delete("/admin/sticker-items/{item_id}", summary="管理员删除表情")
async def admin_delete_item(item_id: int, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    item = await session.get(StickerPackItem, item_id)
    if item is None:
        raise HTTPException(status_code=404, detail="表情不存在")
    pack = await session.get(StickerPack, item.pack_id)
    if pack and pack.published:
        raise HTTPException(status_code=400, detail="已上架的表情包不能删除图片")
    await session.delete(item)
    await session.commit()
    return {"ok": True}


@router.put("/admin/sticker-packs/{pack_id}", summary="管理员重命名/更换表情包组 logo")
async def admin_update_pack(pack_id: int, payload: PackUpdate, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = await session.get(StickerPack, pack_id)
    if pack is None:
        raise HTTPException(status_code=404, detail="表情包组不存在")
    if payload.name is not None and payload.name.strip():
        pack.name = payload.name.strip()[:64]
    if payload.logo is not None and payload.logo.strip():
        pack.logo = payload.logo.strip()[:2000]
    items = (
        await session.execute(select(StickerPackItem).where(StickerPackItem.pack_id == pack.id))
    ).scalars().all()
    store_items = (
        await session.execute(select(StoreItem).where(StoreItem.kind == "sticker_pack"))
    ).scalars().all()
    for item in store_items:
        if int((item.data or {}).get("pack_id") or 0) == pack.id:
            item.name = pack.name
            item.cover_url = pack.logo
    await session.commit()
    return {"ok": True, "pack": _pack_view(pack, list(items))}


@router.post("/admin/sticker-packs/{pack_id}/unpublish", summary="管理员下架表情包组")
async def admin_unpublish(pack_id: int, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = await session.get(StickerPack, pack_id)
    if pack is None:
        raise HTTPException(status_code=404, detail="表情包组不存在")
    pack.published = False
    store_items = (
        await session.execute(select(StoreItem).where(StoreItem.kind == "sticker_pack"))
    ).scalars().all()
    for item in store_items:
        if int((item.data or {}).get("pack_id") or 0) == pack.id:
            item.enabled = False
    await session.commit()
    return {"ok": True}


@router.delete("/admin/sticker-packs/{pack_id}", summary="管理员删除表情包组")
async def admin_delete_pack(pack_id: int, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = await session.get(StickerPack, pack_id)
    if pack is None:
        raise HTTPException(status_code=404, detail="表情包组不存在")
    await session.execute(delete(StickerPackItem).where(StickerPackItem.pack_id == pack.id))
    store_items = (
        await session.execute(select(StoreItem).where(StoreItem.kind == "sticker_pack"))
    ).scalars().all()
    for item in store_items:
        if int((item.data or {}).get("pack_id") or 0) == pack.id:
            await session.execute(delete(StorePurchase).where(StorePurchase.item_id == item.id))
            await session.delete(item)
    await session.delete(pack)
    await session.commit()
    return {"ok": True}


@router.post("/admin/sticker-packs/{pack_id}/publish", summary="管理员上架表情包组")
async def admin_publish(pack_id: int, user: UserDep, session: SessionDep):
    if not _is_admin(user):
        raise HTTPException(status_code=403, detail="需要管理员权限")
    pack = await session.get(StickerPack, pack_id)
    if pack is None:
        raise HTTPException(status_code=404, detail="表情包组不存在")
    items = (
        await session.execute(select(StickerPackItem).where(StickerPackItem.pack_id == pack.id))
    ).scalars().all()
    if not items:
        raise HTTPException(status_code=400, detail="表情包组还没有图片，上架失败")
    missing = sum(1 for item in items if not (item.words or "").strip())
    if missing:
        raise HTTPException(status_code=400, detail=f"还有 {missing} 张图片没有填写 words，上架失败")
    pack.published = True
    existing = (
        await session.execute(
            select(StoreItem).where(StoreItem.kind == "sticker_pack")
        )
    ).scalars().all()
    store_item = next((i for i in existing if int((i.data or {}).get("pack_id") or 0) == pack.id), None)
    if store_item is None:
        store_item = StoreItem(
            kind="sticker_pack",
            name=pack.name,
            description=f"表情包组：{pack.name}",
            price=0,
            cover_url=pack.logo,
            data={"pack_id": pack.id},
            enabled=True,
            sort=100,
        )
        session.add(store_item)
    else:
        store_item.name = pack.name
        store_item.cover_url = pack.logo
        store_item.enabled = True
    await session.commit()
    return {"ok": True, "pack": _pack_view(pack, list(items)), "item_id": store_item.id}
