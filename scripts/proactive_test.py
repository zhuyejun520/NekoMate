"""主动消息 / 记忆逻辑的确定性测试（独立数据目录）。"""
from __future__ import annotations

import asyncio
import os
import shutil
import sys
from datetime import timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data-proactive"
if DATA.exists():
    shutil.rmtree(DATA)
os.environ["NM_DATA_DIR"] = str(DATA)
sys.path.insert(0, str(ROOT / "server"))

from sqlalchemy import select  # noqa: E402

from app.ai.engine import CompanionEngine  # noqa: E402
from app.ai.proactive import proactive_tick  # noqa: E402
from app.bootstrap import bootstrap, create_default_ai_friend  # noqa: E402
from app.config import settings  # noqa: E402
from app.database import SessionLocal, dispose_engine, init_db, init_engine  # noqa: E402
from app.models import Memory, Message, ProactiveState, User  # noqa: E402
from app.security import hash_password  # noqa: E402
from app.services.settings_service import set_setting  # noqa: E402
from app.services.weather import _cache  # noqa: E402
from app.utils import utcnow  # noqa: E402

engine = CompanionEngine()
fail = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global fail
    print(("[PASS] " if cond else "[FAIL] ") + name + ("" if cond else " :: " + str(detail)[:300]))
    if not cond:
        fail += 1


async def main() -> int:
    settings.ensure_dirs()
    await init_engine()
    await init_db()
    await bootstrap()

    async with SessionLocal() as session:
        await set_setting(session, "weather_enabled", False)
        user = User(username="ptest", password_hash=hash_password("test123456"), nickname="主动测试")
        session.add(user)
        await session.commit()
        await session.refresh(user)
        ai = await create_default_ai_friend(session, user)
        conv = await engine.open_conversation(session, ai, user)
        state = await engine.ensure_state(session, ai, user, conv.id)
        state.last_user_at = utcnow() - timedelta(minutes=31)
        await session.commit()

    # 1) 超过 30 分钟 → 结束会话，安排 1-3 小时后的主动消息
    stats = await proactive_tick()
    check("idle ends session", stats["ended"] == 1, stats)
    async with SessionLocal() as session:
        state = (
            await session.execute(select(ProactiveState).where(ProactiveState.user_id == user.id))
        ).scalars().first()
        delay = int((state.next_proactive_at - utcnow()).total_seconds() // 60)
        check("phase waiting", state.phase == "waiting", state.phase)
        check("delay 60-180 min", 58 <= delay <= 181, delay)

    # 2) 到点后连续主动 3 次
    for i in range(3):
        async with SessionLocal() as session:
            state = (
                await session.execute(select(ProactiveState).where(ProactiveState.user_id == user.id))
            ).scalars().first()
            state.next_proactive_at = utcnow() - timedelta(seconds=1)
            await session.commit()
        stats = await proactive_tick()
        async with SessionLocal() as session:
            state = (
                await session.execute(select(ProactiveState).where(ProactiveState.user_id == user.id))
            ).scalars().first()
            msgs = (
                await session.execute(
                    select(Message).where(Message.conversation_id == conv.id, Message.sender_type == "ai")
                )
            ).scalars().all()
            proactive_msgs = [m for m in msgs if (m.meta or {}).get("proactive")]
            check(f"proactive #{i + 1} sent", stats["sent"] == 1 and state.streak == i + 1,
                  f"stats={stats} streak={state.streak}")
            check(f"proactive #{i + 1} stored", len(proactive_msgs) == i + 1, len(proactive_msgs))

    # 3) 第四次到点 → 停止，不再发
    async with SessionLocal() as session:
        state = (
            await session.execute(select(ProactiveState).where(ProactiveState.user_id == user.id))
        ).scalars().first()
        state.next_proactive_at = utcnow() - timedelta(seconds=1)
        await session.commit()
    stats = await proactive_tick()
    async with SessionLocal() as session:
        state = (
            await session.execute(select(ProactiveState).where(ProactiveState.user_id == user.id))
        ).scalars().first()
        check("stops after 3", stats["stopped"] == 1 and state.phase == "stopped", f"{stats} {state.phase}")

    # 4) 用户回复后恢复循环
    async with SessionLocal() as session:
        msg = Message(
            conversation_id=conv.id, sender_type="user", sender_id=user.id, sender_name=user.nickname,
            content="我回来了",
        )
        session.add(msg)
        await session.flush()
        await session.commit()
        await session.refresh(msg)
        reply = await engine.respond(session, ai_friend=ai, user=user, conversation=conv, user_message=msg)
        state = (
            await session.execute(select(ProactiveState).where(ProactiveState.user_id == user.id))
        ).scalars().first()
        check("user reply resets", state.phase == "active" and state.streak == 0 and reply.content, state.phase)
        state.last_user_at = utcnow() - timedelta(minutes=31)
        await session.commit()
    stats = await proactive_tick()
    check("idle again after reply", stats["ended"] == 1, stats)

    # 5) 记忆重要性
    async with SessionLocal() as session:
        short = await engine.ingest_memory(
            session, ai_friend_id=ai.id, user_id=user.id, persona_name="小夜", message_id=999,
            text="今天风挺大的，出门记得带外套",
        )
        long = await engine.ingest_memory(
            session, ai_friend_id=ai.id, user_id=user.id, persona_name="小夜", message_id=1000,
            text="我叫小林，我养了一只叫团子的猫，最近在准备考研，压力有点大",
        )
        check("short memory", short is not None and short.kind == "short" and short.expires_at is not None,
              short.kind if short else None)
        check("long memory", long is not None and long.kind == "long" and long.expires_at is None,
              long.kind if long else None)
        rows = (await session.execute(select(Memory))).scalars().all()
        check("memories persisted", len(rows) >= 2, len(rows))

    await dispose_engine()
    print(f"\nfailures: {fail}")
    return 1 if fail else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
