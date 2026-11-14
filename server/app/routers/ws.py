from __future__ import annotations

from fastapi import APIRouter, Query, WebSocket, WebSocketDisconnect
from sqlalchemy import update

from ..database import SessionLocal
from ..models import User
from ..security import decode_token
from ..services.push import manager
from ..utils import utcnow

router = APIRouter(tags=["实时"])


@router.websocket("/api/v1/ws")
async def websocket_endpoint(ws: WebSocket, token: str = Query(default="")):
    payload = decode_token(token)
    user = None
    if payload:
        if SessionLocal is None:
            await ws.close(code=1011)
            return
        async with SessionLocal() as session:
            user = await session.get(User, int(payload.get("sub", 0)))
            if user is not None:
                user.last_seen_at = utcnow()
                await session.commit()
    if user is None:
        await ws.close(code=4401)
        return

    await manager.connect(user.id, ws)
    try:
        await ws.send_json({"type": "ready", "user_id": user.id, "online": manager.online_count()})
        while True:
            data = await ws.receive_json()
            event = (data or {}).get("type")
            if event == "ping":
                await ws.send_json({"type": "pong", "ts": utcnow().isoformat(timespec="seconds")})
            elif event == "read":
                conversation_id = int((data or {}).get("conversation_id") or 0)
                if conversation_id and SessionLocal is not None:
                    async with SessionLocal() as session:
                        from ..models import ConversationMember, Message
                        from sqlalchemy import func, select

                        latest = (
                            await session.execute(
                                select(func.max(Message.id)).where(Message.conversation_id == conversation_id)
                            )
                        ).scalar() or 0
                        await session.execute(
                            update(ConversationMember)
                            .where(
                                ConversationMember.conversation_id == conversation_id,
                                ConversationMember.user_id == user.id,
                            )
                            .values(last_read_message_id=int(latest))
                        )
                        await session.commit()
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        await manager.disconnect(user.id, ws)
