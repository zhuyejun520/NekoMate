from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import and_, or_, select

from ..deps import SessionDep, UserDep
from ..models import Conversation, ConversationMember, FriendRequest, Friendship, User, UserBlock
from ..serializers import conversation as serialize_conversation
from ..serializers import user_brief, user_full
from ..services.logger import write_log
from ..services.push import manager as ws_manager
from ..utils import utcnow

router = APIRouter(prefix="/api/v1/friends", tags=["好友"])


class RequestIn(BaseModel):
    username: str | None = None
    user_id: int | None = None
    message: str | None = Field(default=None, max_length=120)


class RemarkIn(BaseModel):
    remark: str | None = Field(default=None, max_length=32)


async def _friendship(session, user_id: int, friend_id: int) -> Friendship | None:
    return (
        await session.execute(
            select(Friendship).where(Friendship.user_id == user_id, Friendship.friend_id == friend_id)
        )
    ).scalars().first()


async def ensure_direct_conversation(session, user_a: int, user_b: int) -> Conversation:
    member_a = (
        await session.execute(select(ConversationMember.conversation_id).where(ConversationMember.user_id == user_a))
    ).scalars().all()
    if member_a:
        conv = (
            await session.execute(
                select(Conversation)
                .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
                .where(
                    Conversation.type == "direct",
                    Conversation.id.in_(member_a),
                    ConversationMember.user_id == user_b,
                )
                .limit(1)
            )
        ).scalars().first()
        if conv:
            return conv
    conv = Conversation(type="direct", owner_id=user_a)
    session.add(conv)
    await session.flush()
    session.add_all(
        [
            ConversationMember(conversation_id=conv.id, user_id=user_a, role="owner"),
            ConversationMember(conversation_id=conv.id, user_id=user_b, role="member"),
        ]
    )
    await session.commit()
    await session.refresh(conv)
    return conv


@router.get("/search", summary="搜索用户")
async def search_users(q: str, user: UserDep, session: SessionDep):
    q = (q or "").strip()
    if len(q) < 1:
        return {"users": []}
    pattern = f"%{q}%"
    rows = (
        await session.execute(
            select(User)
            .where(
                User.id != user.id,
                User.status == "active",
                or_(User.username.ilike(pattern), User.nickname.ilike(pattern)),
            )
            .limit(20)
        )
    ).scalars().all()
    my_friends = set(
        (await session.execute(select(Friendship.friend_id).where(Friendship.user_id == user.id))).scalars().all()
    )
    out = []
    for row in rows:
        item = user_brief(row)
        item["is_friend"] = row.id in my_friends
        out.append(item)
    return {"users": out}


@router.get("", summary="好友列表")
async def list_friends(user: UserDep, session: SessionDep):
    rows = (
        await session.execute(select(Friendship).where(Friendship.user_id == user.id).order_by(Friendship.id.desc()))
    ).scalars().all()
    items = []
    for fr in rows:
        friend = await session.get(User, fr.friend_id)
        if friend is None:
            continue
        conv = await ensure_direct_conversation(session, user.id, friend.id)
        data = user_brief(friend)
        data["remark"] = fr.remark
        data["conversation_id"] = conv.id
        items.append(data)
    return {"friends": items}


@router.post("/requests", summary="发送好友申请")
async def send_request(payload: RequestIn, user: UserDep, session: SessionDep):
    target = None
    if payload.user_id:
        target = await session.get(User, payload.user_id)
    elif payload.username:
        target = (
            await session.execute(select(User).where(User.username == payload.username.strip()))
        ).scalars().first()
    if target is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    if target.id == user.id:
        raise HTTPException(status_code=400, detail="不能添加自己为好友")
    if await _friendship(session, user.id, target.id):
        raise HTTPException(status_code=400, detail="你们已经是好友了")
    pending = (
        await session.execute(
            select(FriendRequest).where(
                FriendRequest.from_user_id == user.id,
                FriendRequest.to_user_id == target.id,
                FriendRequest.status == "pending",
            )
        )
    ).scalars().first()
    if pending:
        raise HTTPException(status_code=400, detail="已发送申请，等待对方处理")
    request = FriendRequest(from_user_id=user.id, to_user_id=target.id, message=payload.message or "")
    session.add(request)
    await session.commit()
    await session.refresh(request)
    await ws_manager.send_user(
        target.id,
        {
            "type": "friend_request",
            "request": {
                "id": request.id,
                "from": user_brief(user),
                "message": request.message,
                "created_at": request.created_at.isoformat() if request.created_at else None,
            },
        },
    )
    await write_log("info", "friend", f"{user.username} 向 {target.username} 发送好友申请", user_id=user.id)
    return {"ok": True, "request_id": request.id}


@router.get("/requests", summary="好友申请列表")
async def list_requests(user: UserDep, session: SessionDep):
    incoming = (
        await session.execute(
            select(FriendRequest)
            .where(FriendRequest.to_user_id == user.id, FriendRequest.status == "pending")
            .order_by(FriendRequest.id.desc())
        )
    ).scalars().all()
    outgoing = (
        await session.execute(
            select(FriendRequest)
            .where(FriendRequest.from_user_id == user.id, FriendRequest.status == "pending")
            .order_by(FriendRequest.id.desc())
        )
    ).scalars().all()

    async def pack(rows, incoming_flag: bool):
        out = []
        for r in rows:
            other = await session.get(User, r.from_user_id if incoming_flag else r.to_user_id)
            out.append(
                {
                    "id": r.id,
                    "user": user_brief(other),
                    "message": r.message,
                    "status": r.status,
                    "direction": "incoming" if incoming_flag else "outgoing",
                    "created_at": r.created_at.isoformat() if r.created_at else None,
                }
            )
        return out

    return {"incoming": await pack(incoming, True), "outgoing": await pack(outgoing, False)}


@router.post("/requests/{request_id}/accept", summary="同意好友申请")
async def accept_request(request_id: int, user: UserDep, session: SessionDep):
    request = await session.get(FriendRequest, request_id)
    if request is None or request.to_user_id != user.id:
        raise HTTPException(status_code=404, detail="申请不存在")
    if request.status != "pending":
        raise HTTPException(status_code=400, detail="该申请已处理")
    request.status = "accepted"
    request.handled_at = utcnow()
    if not await _friendship(session, user.id, request.from_user_id):
        session.add(Friendship(user_id=user.id, friend_id=request.from_user_id))
    if not await _friendship(session, request.from_user_id, user.id):
        session.add(Friendship(user_id=request.from_user_id, friend_id=user.id))
    await session.commit()
    conv = await ensure_direct_conversation(session, user.id, request.from_user_id)
    await ws_manager.send_user(
        request.from_user_id,
        {"type": "friend_accepted", "user": user_brief(user), "conversation_id": conv.id},
    )
    return {"ok": True, "conversation_id": conv.id}


@router.post("/requests/{request_id}/reject", summary="拒绝好友申请")
async def reject_request(request_id: int, user: UserDep, session: SessionDep):
    request = await session.get(FriendRequest, request_id)
    if request is None or request.to_user_id != user.id:
        raise HTTPException(status_code=404, detail="申请不存在")
    request.status = "rejected"
    request.handled_at = utcnow()
    await session.commit()
    return {"ok": True}


@router.put("/{friend_id}", summary="设置好友备注")
async def set_remark(friend_id: int, payload: RemarkIn, user: UserDep, session: SessionDep):
    fr = await _friendship(session, user.id, friend_id)
    if fr is None:
        raise HTTPException(status_code=404, detail="不是好友关系")
    fr.remark = (payload.remark or "").strip() or None
    await session.commit()
    return {"ok": True, "remark": fr.remark}


@router.delete("/{friend_id}", summary="删除好友")
async def delete_friend(friend_id: int, user: UserDep, session: SessionDep):
    for a, b in ((user.id, friend_id), (friend_id, user.id)):
        fr = await _friendship(session, a, b)
        if fr:
            await session.delete(fr)
    await session.commit()
    return {"ok": True}


@router.post("/{friend_id}/block", summary="拉黑好友")
async def block_friend(friend_id: int, user: UserDep, session: SessionDep):
    if friend_id == user.id:
        raise HTTPException(status_code=400, detail="不能拉黑自己")
    target = await session.get(User, friend_id)
    if target is None:
        raise HTTPException(status_code=404, detail="用户不存在")
    exists = (
        await session.execute(
            select(UserBlock).where(UserBlock.blocker_id == user.id, UserBlock.blocked_id == friend_id)
        )
    ).scalars().first()
    if exists is None:
        session.add(UserBlock(blocker_id=user.id, blocked_id=friend_id))
    for a, b in ((user.id, friend_id), (friend_id, user.id)):
        fr = await _friendship(session, a, b)
        if fr:
            await session.delete(fr)
    await session.commit()
    return {"ok": True, "blocked": True}


@router.delete("/{friend_id}/block", summary="解除拉黑")
async def unblock_friend(friend_id: int, user: UserDep, session: SessionDep):
    row = (
        await session.execute(
            select(UserBlock).where(UserBlock.blocker_id == user.id, UserBlock.blocked_id == friend_id)
        )
    ).scalars().first()
    if row:
        await session.delete(row)
        await session.commit()
    return {"ok": True, "blocked": False}
