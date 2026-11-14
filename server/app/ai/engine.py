from __future__ import annotations

import asyncio
import random
import re
from datetime import datetime, timedelta
from typing import Any, Sequence

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..models import (
    AiFriend,
    AppWhitelist,
    Conversation,
    ConversationMember,
    KnowledgeBase,
    KnowledgeChunk,
    Memory,
    Message,
    Persona,
    PersonaKnowledge,
    ProactiveState,
    UsageEvent,
    User,
)
from ..serializers import message as serialize_message
from ..services.logger import write_log
from ..services.settings_service import get_settings_map
from ..services.push import manager as ws_manager
from ..services.weather import get_weather, weather_line
from ..utils import truncate, utcnow
from . import prompts
from .providers import ProviderError, chat, pick_provider, analyze_json


IMPORTANT_HINTS = [
    "我叫", "我的名字", "我是", "我家", "我住", "生日", "星座", "我喜欢", "我最喜欢", "我讨厌", "我爱",
    "工作", "上班", "公司", "老板", "同事", "学校", "大学", "专业", "考试", "面试", "实习", "毕业",
    "妈妈", "爸爸", "父母", "奶奶", "爷爷", "妹妹", "姐姐", "哥哥", "弟弟", "女朋友", "男朋友",
    "老婆", "老公", "前任", "室友", "闺蜜", "兄弟", "猫", "狗", "宠物",
    "生病", "感冒", "发烧", "医院", "失眠", "减肥", "健身", "跑步", "过敏", "吃药",
    "计划", "打算", "明天", "下周", "下个月", "周末", "目标", "梦想", "想要", "准备",
    "难过", "开心", "焦虑", "压力", "害怕", "孤独", "委屈", "感动", "崩溃",
    "记住", "别忘", "答应我", "约定", "习惯", "经常", "每天", "从来不",
]

STOPWORDS = {"我们", "你们", "他们", "什么", "怎么", "这个", "那个", "就是", "真的", "可以", "不是", "没有", "然后", "现在", "今天", "明天", "因为", "所以", "如果", "还是", "已经", "有点", "感觉", "时候", "自己", "知道", "觉得"}

_bg_tasks: set[asyncio.Task] = set()
_analysis_sem = asyncio.Semaphore(3)


def _spawn(coro) -> None:
    task = asyncio.create_task(coro)
    _bg_tasks.add(task)
    task.add_done_callback(_bg_tasks.discard)


def affinity_of(ai: AiFriend) -> tuple[int, bool]:
    settings_map = ai.settings or {}
    try:
        value = int(settings_map.get("affinity", 0))
    except (TypeError, ValueError):
        value = 0
    return max(0, min(100, value)), bool(settings_map.get("affinity_enabled", True))


def demo_reply(persona_name: str, user_text: str, tz_name: str = "Asia/Shanghai") -> str:
    now = prompts.now_text(tz_name)
    snippet = truncate(user_text.replace("\n", " "), 24)
    hour = datetime.now().astimezone().hour
    if 11 <= hour < 14:
        tail = "午饭时间到了，你吃了吗？"
    elif 18 <= hour < 22:
        tail = "晚上的风有点凉，别只顾着忙。"
    elif hour >= 23 or hour < 6:
        tail = "这么晚还没睡呀。"
    else:
        tail = "我在呢，慢慢说。"
    return (
        f"（演示模式）我听到你说「{snippet}」啦。{tail}\n"
        "现在还没有接入真正的对话模型——去管理面板「模型提供商」配置一个，我就能用自己的语气回你了。"
        f"（当前时间 {now}）"
    )


class CompanionEngine:
    # ------------------------------------------------------------------ setup
    async def ensure_conversation(self, session: AsyncSession, ai_friend: AiFriend, user: User) -> Conversation:
        conv = (
            await session.execute(
                select(Conversation)
                .join(ConversationMember, ConversationMember.conversation_id == Conversation.id)
                .where(
                    Conversation.type == "ai",
                    Conversation.ai_friend_id == ai_friend.id,
                    ConversationMember.user_id == user.id,
                )
                .order_by(Conversation.id.asc())
            )
        ).scalars().first()
        if conv:
            return conv
        conv = Conversation(
            type="ai",
            title=ai_friend.name,
            avatar=ai_friend.avatar,
            owner_id=user.id,
            ai_friend_id=ai_friend.id,
            meta={"persona_id": ai_friend.persona_id},
        )
        session.add(conv)
        await session.flush()
        session.add(ConversationMember(conversation_id=conv.id, user_id=user.id, role="owner"))
        await session.commit()
        await session.refresh(conv)
        return conv

    async def ensure_state(self, session: AsyncSession, ai_friend: AiFriend, user: User, conversation_id: int | None = None) -> ProactiveState:
        state = (
            await session.execute(
                select(ProactiveState).where(
                    ProactiveState.user_id == user.id, ProactiveState.ai_friend_id == ai_friend.id
                )
            )
        ).scalars().first()
        if state is None:
            state = ProactiveState(
                user_id=user.id,
                ai_friend_id=ai_friend.id,
                conversation_id=conversation_id,
                phase="active",
                streak=0,
                last_user_at=utcnow(),
            )
            session.add(state)
            await session.commit()
            await session.refresh(state)
        elif conversation_id and state.conversation_id != conversation_id:
            state.conversation_id = conversation_id
            await session.commit()
        return state

    async def open_conversation(self, session: AsyncSession, ai_friend: AiFriend, user: User) -> Conversation:
        conv = await self.ensure_conversation(session, ai_friend, user)
        await self.ensure_state(session, ai_friend, user, conv.id)
        return conv

    # --------------------------------------------------------------- retrieval
    async def style_context(self, session: AsyncSession, persona: Persona) -> tuple[dict[str, Any] | None, list[dict[str, Any]]]:
        kb_ids = (
            await session.execute(select(PersonaKnowledge.kb_id).where(PersonaKnowledge.persona_id == persona.id))
        ).scalars().all()
        if not kb_ids:
            return None, []
        kbs = (
            await session.execute(
                select(KnowledgeBase).where(
                    KnowledgeBase.id.in_(kb_ids),
                    KnowledgeBase.enabled.is_(True),
                    KnowledgeBase.kind == "style",
                )
            )
        ).scalars().all()
        if not kbs:
            return None, []
        profile: dict[str, Any] | None = None
        for kb in kbs:
            if kb.style_profile:
                profile = dict(kb.style_profile)
                break
        active_ids = [kb.id for kb in kbs]
        chunks = (
            await session.execute(
                select(KnowledgeChunk)
                .where(KnowledgeChunk.kb_id.in_(active_ids), KnowledgeChunk.kind == "dialogue")
                .order_by(func.random())
                .limit(10)
            )
        ).scalars().all()
        samples = [{"content": c.content, "speaker": c.speaker} for c in chunks]
        return profile, samples

    async def recall(self, session: AsyncSession, user_id: int, ai_friend_id: int, query: str, limit: int = 12) -> list[Memory]:
        now = utcnow()
        rows = (
            await session.execute(
                select(Memory)
                .where(
                    Memory.user_id == user_id,
                    Memory.ai_friend_id == ai_friend_id,
                    ((Memory.kind == "long") | (Memory.expires_at.is_(None)) | (Memory.expires_at > now)),
                )
                .order_by(Memory.created_at.desc())
                .limit(300)
            )
        ).scalars().all()
        scored: list[tuple[float, Memory]] = []
        q = (query or "").lower()
        for m in rows:
            age_days = max(0.0, (now - (m.created_at.replace(tzinfo=None) if m.created_at.tzinfo is None else m.created_at)).total_seconds() / 86400)
            recency = 0.5 ** (age_days / 45)
            score = 0.55 * float(m.importance or 0.5) + 0.35 * recency
            if m.kind == "short":
                score += 0.05
            for kw in m.keywords or []:
                if kw and str(kw).lower() in q:
                    score += 0.12
            content = (m.content or "")[:40]
            if content and content in q:
                score += 0.2
            scored.append((score, m))
        scored.sort(key=lambda x: x[0], reverse=True)
        return [m for _, m in scored[:limit]]

    async def history(self, session: AsyncSession, conversation_id: int, limit: int = 24, before_id: int | None = None) -> list[Message]:
        stmt = select(Message).where(Message.conversation_id == conversation_id)
        if before_id:
            stmt = stmt.where(Message.id < before_id)
        rows = (await session.execute(stmt.order_by(Message.id.desc()).limit(limit))).scalars().all()
        return list(reversed(rows))

    # ------------------------------------------------------------------- send
    async def store_ai_message(
        self,
        session: AsyncSession,
        *,
        conversation: Conversation,
        ai_friend: AiFriend,
        content: str,
        meta: dict[str, Any] | None = None,
        content_type: str = "text",
    ) -> Message:
        msg = Message(
            conversation_id=conversation.id,
            sender_type="ai",
            sender_id=ai_friend.user_id,
            sender_name=ai_friend.name,
            content=content,
            content_type=content_type,
            meta={"ai_friend_id": ai_friend.id, "persona_id": ai_friend.persona_id,
                  "sender_avatar": ai_friend.avatar or "", **(meta or {})},
        )
        session.add(msg)
        conversation.last_message_at = utcnow()
        conversation.last_message_preview = truncate(content, 120)
        await session.commit()
        await session.refresh(msg)
        payload = {"type": "message", "conversation_id": conversation.id, "message": serialize_message(msg)}
        await ws_manager.send_user(ai_friend.user_id, payload)
        return msg

    # ---------------------------------------------------------------- respond
    @staticmethod
    def _split_reply_text(text: str) -> list[str]:
        value = (text or "").strip()
        if len(value) < 12:
            return [value]
        value = re.sub(r"\.{2,}", "…", value)
        parts = [
            p.strip()
            for p in re.split(r"(?<=[。！？!?…，,；;])", value)
            if p.strip()
        ]
        if len(parts) <= 1:
            return [value]
        if len(parts) > 3:
            total = max(1, sum(len(p) for p in parts))
            groups: list[list[str]] = [[], [], []]
            consumed = 0
            for part in parts:
                index = min(2, int(consumed * 3 / total))
                groups[index].append(part)
                consumed += len(part)
            parts = ["".join(group) for group in groups if group]
        return [p.rstrip("，,；;") for p in parts if p.strip()] or [value]

    async def respond(
        self,
        session: AsyncSession,
        *,
        ai_friend: AiFriend,
        user: User,
        conversation: Conversation,
        user_message: Message,
        mode: str = "chat",
        extra_context: str = "",
        user_text: str | None = None,
    ) -> Message:
        effective_text = user_text if user_text is not None else user_message.content
        persona = await session.get(Persona, ai_friend.persona_id)
        if persona is None:
            persona = Persona(name=ai_friend.name, description="一个温和、好奇、愿意倾听的朋友。")
        app_settings = await get_settings_map(session)

        text: str | None = None
        plugin_name: str | None = None
        if app_settings.get("plugin_dispatch_enabled", True) and mode == "chat":
            try:
                from ..services.plugin_loader import plugin_manager

                result = await plugin_manager.dispatch(
                    text=effective_text,
                    user=user,
                    conversation=conversation,
                    ai_friend=ai_friend,
                    sender_name=user.nickname or user.username,
                )
                if result:
                    text = result.text
                    plugin_name = result.plugin
            except Exception as exc:
                await write_log("error", "plugin", f"插件分发异常: {exc}", conversation_id=conversation.id)

        if not text:
            text = await self.generate_reply(
                session,
                persona=persona,
                ai_friend=ai_friend,
                user=user,
                conversation=conversation,
                user_text=effective_text,
                mode=mode,
                extra_context=extra_context,
                before_id=user_message.id,
                app_settings=app_settings,
            )

        meta = {"mode": mode}
        if plugin_name:
            meta["plugin"] = plugin_name
        split = bool((ai_friend.settings or {}).get("split_replies"))
        parts = self._split_reply_text(text) if split else [text]
        msg: Message | None = None
        first_msg: Message | None = None
        for index, part in enumerate(parts):
            part_meta = dict(meta)
            if len(parts) > 1:
                part_meta["split"] = True
                part_meta["split_index"] = index
                part_meta["split_total"] = len(parts)
            msg = await self.store_ai_message(
                session, conversation=conversation, ai_friend=ai_friend,
                content=part, meta=part_meta,
            )
            if first_msg is None:
                first_msg = msg
            if index < len(parts) - 1:
                await asyncio.sleep(0.5)
        if msg is None:
            raise RuntimeError("AI 回复为空")

        state = await self.ensure_state(session, ai_friend, user, conversation.id)
        state.last_ai_at = utcnow()
        if mode == "chat":
            state.phase = "active"
            state.streak = 0
            state.next_proactive_at = None
        ai_friend.last_active_at = utcnow()
        await session.commit()

        if mode == "chat":
            _spawn(self.ingest_memory_async(ai_friend.id, user.id, persona.name, user_message.id, effective_text, conversation.id))
        return first_msg or msg

    async def generate_reply(
        self,
        session: AsyncSession,
        *,
        persona: Persona,
        ai_friend: AiFriend,
        user: User,
        conversation: Conversation,
        user_text: str,
        mode: str,
        extra_context: str,
        before_id: int | None,
        app_settings: dict[str, Any],
    ) -> str:
        tz_name = app_settings.get("timezone", "Asia/Shanghai")
        style_profile, style_samples = await self.style_context(session, persona)
        memories = await self.recall(session, user.id, ai_friend.id, user_text)
        history = await self.history(
            session, conversation.id, limit=int(app_settings.get("max_context_messages", settings.max_context_messages)), before_id=before_id
        )
        system = prompts.build_system_prompt(
            persona=persona,
            ai_name=ai_friend.name,
            user=user,
            style_profile=style_profile,
            style_samples=style_samples,
            memories=memories,
            now=prompts.now_text(tz_name),
            extra_context=extra_context,
            mode=mode,
            tz_name=tz_name,
            affinity=affinity_of(ai_friend)[0],
            affinity_enabled=affinity_of(ai_friend)[1],
        )
        messages = [{"role": "system", "content": system}]
        messages.extend(prompts.history_messages(history))
        messages.append({"role": "user", "content": user_text})

        provider = await pick_provider(session, "chat", user_id=user.id)
        if provider.is_demo:
            return demo_reply(persona.name or ai_friend.name, user_text, tz_name)
        try:
            return await chat(provider, messages, temperature=0.85, max_tokens=600)
        except ProviderError as exc:
            await write_log("error", "ai", f"对话模型调用失败，已回退到演示回复: {exc}", ai_friend_id=ai_friend.id)
            return demo_reply(persona.name or ai_friend.name, user_text, tz_name)

    # ---------------------------------------------------------------- group
    async def group_reply(
        self,
        session: AsyncSession,
        *,
        ai_friend: AiFriend,
        group: Conversation,
        trigger: Message,
    ) -> Message | None:
        """Generate one AI reply inside a group conversation."""
        if trigger.sender_type != "user":
            return None
        persona = await session.get(Persona, ai_friend.persona_id)
        owner = await session.get(User, ai_friend.user_id)
        if persona is None or owner is None:
            return None
        app_settings = await get_settings_map(session)
        tz_name = app_settings.get("timezone", "Asia/Shanghai")
        style_profile, style_samples = await self.style_context(session, persona)
        history = await self.history(session, group.id, limit=20, before_id=trigger.id)
        member_ids = (
            await session.execute(
                select(ConversationMember.user_id).where(ConversationMember.conversation_id == group.id)
            )
        ).scalars().all()
        names: list[str] = []
        for uid in member_ids:
            u = await session.get(User, uid)
            if u:
                names.append(u.nickname or u.username)
        members_text = "、".join(names[:20]) or "群成员"
        trigger_text = trigger.content
        if trigger.content_type == "sticker":
            sticker_words = (trigger.meta or {}).get("words") or trigger.content.strip("[]")
            trigger_text = f"表情包「{sticker_words}」"
        extra = (
            f"现在是在群聊「{group.title or '群聊'}」里，群成员有：{members_text}。"
            f"最后一条消息来自「{trigger.sender_name or '某位成员'}」：{trigger_text}。"
            "只回复最后这一条，像群里的一员自然接话，1-2 句，不要代替其他人说话，不要复述群规。"
        )
        system = prompts.build_system_prompt(
            persona=persona,
            ai_name=ai_friend.name,
            user=owner,
            style_profile=style_profile,
            style_samples=style_samples,
            memories=[],
            now=prompts.now_text(tz_name),
            extra_context=extra,
            mode="group",
            tz_name=tz_name,
            affinity=affinity_of(ai_friend)[0],
            affinity_enabled=affinity_of(ai_friend)[1],
        )
        messages: list[dict[str, str]] = [{"role": "system", "content": system}]
        for m in history:
            if m.sender_type == "system" or not (m.content or "").strip():
                continue
            role = "assistant" if m.sender_type == "ai" else "user"
            messages.append({"role": role, "content": f"{m.sender_name or '成员'}：{truncate(m.content, 300)}"})
        messages.append({"role": "user", "content": f"{trigger.sender_name or '成员'}：{trigger_text}"})

        provider = await pick_provider(session, "chat", user_id=ai_friend.user_id)
        if provider.is_demo:
            text = demo_reply(ai_friend.name, trigger.content, tz_name)
        else:
            try:
                text = await chat(provider, messages, temperature=0.8, max_tokens=300)
            except ProviderError as exc:
                await write_log("error", "ai", f"群聊模型调用失败，已回退演示回复: {exc}", ai_friend_id=ai_friend.id)
                text = demo_reply(ai_friend.name, trigger.content, tz_name)
        text = (text or "").strip()
        if not text:
            return None
        msg = Message(
            conversation_id=group.id,
            sender_type="ai",
            sender_id=ai_friend.user_id,
            sender_name=ai_friend.name,
            content=truncate(text, 1200),
            content_type="text",
            meta={"ai_friend_id": ai_friend.id, "persona_id": ai_friend.persona_id,
                  "sender_avatar": ai_friend.avatar or "", "group": True},
        )
        session.add(msg)
        group.last_message_at = utcnow()
        group.last_message_preview = truncate(msg.content, 120)
        await session.commit()
        await session.refresh(msg)
        payload = {"type": "message", "conversation_id": group.id, "message": serialize_message(msg)}
        for uid in member_ids:
            await ws_manager.send_user(uid, payload)
        return msg

    # ---------------------------------------------------------------- memory
    async def ingest_memory_async(
        self, ai_friend_id: int, user_id: int, persona_name: str, message_id: int, text: str, conversation_id: int
    ) -> None:
        from ..database import SessionLocal

        try:
            async with SessionLocal() as session:
                persona = None
                ai = await session.get(AiFriend, ai_friend_id)
                if ai:
                    persona = await session.get(Persona, ai.persona_id)
                await self.ingest_memory(
                    session,
                    ai_friend_id=ai_friend_id,
                    user_id=user_id,
                    persona_name=(persona.name if persona else "AI"),
                    message_id=message_id,
                    text=text,
                    conversation_id=conversation_id,
                )
        except Exception as exc:
            await write_log("error", "memory", f"记忆写入失败: {exc}", ai_friend_id=ai_friend_id)

    async def ingest_memory(
        self,
        session: AsyncSession,
        *,
        ai_friend_id: int,
        user_id: int,
        persona_name: str,
        message_id: int,
        text: str,
        conversation_id: int | None = None,
    ) -> Memory | None:
        text = (text or "").strip()
        if len(text) < 4:
            return None
        app_settings = await get_settings_map(session)
        short_days = int(app_settings.get("short_memory_days", settings.default_short_memory_days))

        important, importance, summary, facts, keywords = heuristic_memory(text, short_days)

        analysis_data: dict[str, Any] | None = None
        provider = await pick_provider(session, "analysis", user_id=user_id)
        if not provider.is_demo:
            recent = []
            if conversation_id:
                recent = await self.history(session, conversation_id, limit=6)
            async with _analysis_sem:
                data = await analyze_json(
                    provider,
                    prompts.analysis_system_prompt(),
                    prompts.analysis_user_prompt(persona_name, text, recent),
                )
            if isinstance(data, dict):
                analysis_data = data
                try:
                    important = bool(data.get("important", important))
                    importance = float(data.get("importance", importance) or importance)
                except Exception:
                    pass
                summary = str(data.get("summary") or summary).strip() or summary
                if isinstance(data.get("facts"), list) and data["facts"]:
                    facts = [str(x) for x in data["facts"]][:3]
                if isinstance(data.get("keywords"), list) and data["keywords"]:
                    keywords = [str(x) for x in data["keywords"]][:6]

        if analysis_data is not None:
            try:
                delta = int(analysis_data.get("affinity_delta", 0) or 0)
            except (TypeError, ValueError):
                delta = 0
            delta = max(-5, min(5, delta))
            ai = await session.get(AiFriend, ai_friend_id)
            if ai is not None and delta != 0:
                settings_map = dict(ai.settings or {})
                if settings_map.get("affinity_enabled", True):
                    current = max(0, min(100, int(settings_map.get("affinity", 0) or 0)))
                    new_value = max(0, min(100, current + delta))
                    settings_map["affinity"] = new_value
                    ai.settings = settings_map
                    await session.commit()
                    await write_log(
                        "info",
                        "ai",
                        f"好感度 {current} -> {new_value}（{delta:+d}）",
                        ai_friend_id=ai.id,
                        user_id=user_id,
                    )

        content = summary or truncate(text, 80)
        if facts:
            content = (summary + "；" + "；".join(facts[:3])).strip("；")[:400]

        existing = (
            await session.execute(
                select(Memory).where(
                    Memory.user_id == user_id,
                    Memory.ai_friend_id == ai_friend_id,
                    Memory.content == content,
                )
            )
        ).scalars().first()
        if existing:
            existing.last_access_at = utcnow()
            existing.importance = max(existing.importance or 0.5, min(importance, 1.0))
            await session.commit()
            return existing

        kind = "long" if important else "short"
        expires_at = None if important else utcnow() + timedelta(days=short_days)
        mem = Memory(
            user_id=user_id,
            ai_friend_id=ai_friend_id,
            kind=kind,
            content=content,
            importance=round(max(0.0, min(1.0, importance)), 3),
            keywords=keywords,
            source_message_id=message_id,
            source="chat",
            expires_at=expires_at,
        )
        session.add(mem)
        await session.commit()
        await session.refresh(mem)
        await write_log(
            "debug",
            "memory",
            f"记忆已写入({kind}): {truncate(content, 60)}",
            user_id=user_id,
            ai_friend_id=ai_friend_id,
            importance=mem.importance,
        )
        await self._prune_memories(session, user_id, ai_friend_id)
        return mem

    async def _prune_memories(self, session: AsyncSession, user_id: int, ai_friend_id: int, keep: int = 600) -> None:
        long_rows = (
            await session.execute(
                select(Memory)
                .where(Memory.user_id == user_id, Memory.ai_friend_id == ai_friend_id, Memory.kind == "long")
                .order_by(Memory.importance.desc(), Memory.created_at.desc())
            )
        ).scalars().all()
        if len(long_rows) <= keep:
            return
        doomed = long_rows[keep:]
        await session.execute(delete(Memory).where(Memory.id.in_([m.id for m in doomed])))
        await session.commit()

    # ------------------------------------------------------------- proactive
    async def proactive(self, session: AsyncSession, state: ProactiveState, *, force: bool = False) -> Message | None:
        ai = await session.get(AiFriend, state.ai_friend_id)
        user = await session.get(User, state.user_id)
        if not ai or not user or ai.status != "active":
            return None
        persona = await session.get(Persona, ai.persona_id)
        if persona is None:
            return None
        conv = await self.ensure_conversation(session, ai, user)
        app_settings = await get_settings_map(session)
        tz_name = app_settings.get("timezone", "Asia/Shanghai")
        cfg = {**default_ai_settings(app_settings), **(ai.settings or {})}
        if not cfg.get("proactive_enabled", True) and not force:
            return None

        sources = cfg.get("proactive_sources") or ["recent_chat", "weather", "smalltalk"]
        if not isinstance(sources, list) or not sources:
            sources = ["recent_chat", "smalltalk"]
        weights = {"recent_chat": 5, "weather": 3, "smalltalk": 2}
        source = random.choices(sources, weights=[weights.get(s, 1) for s in sources], k=1)[0]

        wline = ""
        if source == "weather" and app_settings.get("weather_enabled", True):
            weather = await get_weather(user.city)
            if weather:
                wline = weather_line(weather)
            else:
                source = "recent_chat" if "recent_chat" in sources else "smalltalk"

        recent = await self.history(session, conv.id, limit=8)
        recent_lines = [f"{'AI' if m.sender_type == 'ai' else '用户'}: {truncate(m.content, 60)}" for m in recent]
        last_proactive = None
        for m in reversed(recent):
            if m.sender_type == "ai" and (m.meta or {}).get("proactive"):
                last_proactive = m.content
                break

        style_profile, style_samples = await self.style_context(session, persona)
        memories = await self.recall(session, user.id, ai.id, "", limit=8)
        system = prompts.build_system_prompt(
            persona=persona,
            ai_name=ai.name,
            user=user,
            style_profile=style_profile,
            style_samples=style_samples,
            memories=memories,
            now=prompts.now_text(tz_name),
            mode="proactive",
            tz_name=tz_name,
            affinity=affinity_of(ai)[0],
            affinity_enabled=affinity_of(ai)[1],
        )
        user_prompt = prompts.proactive_user_prompt(
            source=source,
            now=prompts.now_text(tz_name),
            user_nick=user.nickname or user.username,
            streak=state.streak,
            weather_line=wline,
            recent_lines=recent_lines,
            last_proactive=last_proactive,
        )
        provider = await pick_provider(session, "chat", user_id=user.id)
        text = ""
        if not provider.is_demo:
            try:
                text = await chat(
                    provider,
                    [{"role": "system", "content": system}, {"role": "user", "content": user_prompt}],
                    temperature=0.95,
                    max_tokens=200,
                )
            except ProviderError as exc:
                await write_log("warning", "ai", f"主动消息生成失败，使用本地模板: {exc}", ai_friend_id=ai.id)
        if not text:
            text = fallback_proactive(source, wline, user, state.streak, recent_lines)

        msg = await self.store_ai_message(
            session,
            conversation=conv,
            ai_friend=ai,
            content=text,
            meta={"proactive": True, "source": source, "streak": state.streak + 1},
        )
        state.phase = "waiting"
        state.streak = state.streak + 1
        state.total_proactive = (state.total_proactive or 0) + 1
        state.last_ai_at = utcnow()
        state.next_proactive_at = utcnow() + timedelta(minutes=random.randint(cfg["min_delay_minutes"], cfg["max_delay_minutes"]))
        await session.commit()
        await write_log(
            "info",
            "proactive",
            f"AI 主动消息（第 {state.streak} 次，来源 {source}）: {truncate(text, 60)}",
            user_id=user.id,
            ai_friend_id=ai.id,
        )
        return msg

    # ----------------------------------------------------------- root usage
    async def process_usage(
        self,
        session: AsyncSession,
        *,
        ai_friend: AiFriend,
        user: User,
        events: Sequence[dict[str, Any]],
        app_settings: dict[str, Any],
    ) -> list[dict[str, Any]]:
        results: list[dict[str, Any]] = []
        if not events:
            return results
        conv = await self.open_conversation(session, ai_friend, user)
        whitelist = (
            await session.execute(
                select(AppWhitelist).where(AppWhitelist.ai_friend_id == ai_friend.id, AppWhitelist.enabled.is_(True))
            )
        ).scalars().all()
        by_pkg = {w.package_name.lower(): w for w in whitelist}
        by_label = {(w.label or "").lower(): w for w in whitelist if w.label}

        cfg = {**default_ai_settings(app_settings), **(ai_friend.settings or {})}
        cooldown = int(cfg.get("usage_cooldown_minutes", 15))
        last_reply_at = (
            await session.execute(
                select(UsageEvent.happened_at)
                .where(UsageEvent.ai_friend_id == ai_friend.id, UsageEvent.handled.is_(True))
                .order_by(UsageEvent.id.desc())
                .limit(1)
            )
        ).scalar()
        if last_reply_at and last_reply_at.tzinfo is None:
            last_reply_at = last_reply_at.replace(tzinfo=utcnow().tzinfo)

        tz_name = app_settings.get("timezone", "Asia/Shanghai")
        for raw in events[:20]:
            package = str(raw.get("package") or raw.get("package_name") or "").strip()
            label = str(raw.get("label") or raw.get("app_name") or "").strip()
            happened = raw.get("ts") or raw.get("timestamp")
            matched = by_pkg.get(package.lower()) or by_label.get(label.lower())
            event = UsageEvent(
                user_id=user.id,
                ai_friend_id=ai_friend.id,
                package_name=package or label or "unknown",
                label=(matched.label if matched else label) or package,
                event_type=str(raw.get("event") or "foreground"),
                happened_at=parse_event_time(happened),
                handled=False,
            )
            session.add(event)
            await session.flush()

            if not ai_friend.root_enabled:
                results.append({"package": package, "skipped": "root_disabled"})
                continue
            if matched is None:
                results.append({"package": package, "skipped": "not_in_whitelist"})
                continue
            if cooldown > 0 and last_reply_at and utcnow() - last_reply_at < timedelta(minutes=cooldown):
                results.append({"package": package, "skipped": "cooldown"})
                continue

            wline = ""
            if app_settings.get("weather_enabled", True):
                weather = await get_weather(user.city)
                wline = weather_line(weather)
            user_prompt = prompts.usage_user_prompt(
                label=event.label or package,
                package=package,
                now=prompts.now_text(tz_name),
                user_nick=user.nickname or user.username,
                note=matched.note,
                weather_line=wline,
            )
            persona = await session.get(Persona, ai_friend.persona_id)
            text = ""
            if persona is not None:
                style_profile, style_samples = await self.style_context(session, persona)
                memories = await self.recall(session, user.id, ai_friend.id, event.label or "", limit=8)
                system = prompts.build_system_prompt(
                    persona=persona,
                    ai_name=ai_friend.name,
                    user=user,
                    style_profile=style_profile,
                    style_samples=style_samples,
                    memories=memories,
                    now=prompts.now_text(tz_name),
                    extra_context=f"对方刚刚打开了应用「{event.label}」。",
                    mode="usage",
                    tz_name=tz_name,
                    affinity=affinity_of(ai_friend)[0],
                    affinity_enabled=affinity_of(ai_friend)[1],
                )
                provider = await pick_provider(session, "chat", user_id=user.id)
                if not provider.is_demo:
                    try:
                        text = await chat(
                            provider,
                            [{"role": "system", "content": system}, {"role": "user", "content": user_prompt}],
                            temperature=0.9,
                            max_tokens=160,
                        )
                    except ProviderError as exc:
                        await write_log("warning", "ai", f"应用事件回复生成失败: {exc}", ai_friend_id=ai_friend.id)
            if not text:
                text = fallback_usage(event.label or package, tz_name)

            msg = await self.store_ai_message(
                session,
                conversation=conv,
                ai_friend=ai_friend,
                content=text,
                meta={"usage": True, "package": package, "label": event.label},
            )
            event.handled = True
            event.response_text = text
            last_reply_at = utcnow()
            state = await self.ensure_state(session, ai_friend, user, conv.id)
            state.last_ai_at = utcnow()
            results.append({"package": package, "label": event.label, "message_id": msg.id, "reply": text})
            await session.commit()
            await write_log(
                "info",
                "usage",
                f"检测到打开「{event.label}」，AI 已回应: {truncate(text, 50)}",
                user_id=user.id,
                ai_friend_id=ai_friend.id,
            )
        await session.commit()
        return results

    # ------------------------------------------------------------- greetings
    async def create_greeting(self, session: AsyncSession, ai_friend: AiFriend, user: User, app_settings: dict[str, Any]) -> Message | None:
        persona = await session.get(Persona, ai_friend.persona_id)
        conv = await self.open_conversation(session, ai_friend, user)
        text = (persona.greeting if persona and persona.greeting else "") or f"嗨，我是{ai_friend.name}，以后就由我来陪你聊天啦。"
        provider = await pick_provider(session, "chat", user_id=user.id)
        if persona and not provider.is_demo:
            try:
                text = await chat(
                    provider,
                    [
                        {"role": "system", "content": prompts.build_system_prompt(
                            persona=persona,
                            ai_name=ai_friend.name,
                            user=user,
                            style_profile=None,
                            style_samples=[],
                            memories=[],
                            now=prompts.now_text(app_settings.get("timezone", "Asia/Shanghai")),
                            tz_name=app_settings.get("timezone", "Asia/Shanghai"),
                            affinity=affinity_of(ai_friend)[0],
                            affinity_enabled=affinity_of(ai_friend)[1],
                        )},
                        {"role": "user", "content": prompts.greeting_prompt(ai_friend.name, user.nickname or user.username)},
                    ],
                    temperature=0.9,
                    max_tokens=160,
                )
            except ProviderError:
                pass
        return await self.store_ai_message(
            session, conversation=conv, ai_friend=ai_friend, content=text, meta={"greeting": True}
        )


def parse_event_time(value: Any) -> datetime:
    from ..utils import parse_dt

    dt = parse_dt(value)
    if dt is None:
        return utcnow()
    # guard against obviously wrong clocks
    now = utcnow()
    if abs((dt - now).total_seconds()) > 60 * 60 * 24 * 30:
        return now
    return dt


def heuristic_memory(text: str, short_days: int = 2) -> tuple[bool, float, str, list[str], list[str]]:
    hits = [h for h in IMPORTANT_HINTS if h in text]
    length_bonus = min(len(text) / 120.0, 0.5)
    score = min(1.0, 0.35 + 0.12 * len(hits) + length_bonus)
    important = bool(hits) or len(text) >= 60
    if len(text) < 10 and not hits:
        important = False
        score = 0.2
    first_person = bool(re.search(r"我(的|是|叫|喜欢|讨厌|想要|计划|打算|觉得|最近|今天|明天)", text))
    if first_person:
        score = min(1.0, score + 0.1)
    keywords = extract_keywords(text)
    summary = summarize_text(text)
    return important, (score if important else min(score, 0.45)), summary, [], keywords


def summarize_text(text: str) -> str:
    cleaned = re.sub(r"\s+", " ", text).strip()
    if len(cleaned) <= 80:
        return cleaned
    return cleaned[:78] + "…"


def extract_keywords(text: str, limit: int = 6) -> list[str]:
    tokens = re.findall(r"[\u4e00-\u9fa5]{2,4}|[A-Za-z][A-Za-z0-9_]{1,20}", text)
    freq: dict[str, int] = {}
    for t in tokens:
        if t in STOPWORDS or len(t) < 2:
            continue
        freq[t] = freq.get(t, 0) + 1
    ranked = sorted(freq.items(), key=lambda x: (-x[1], -len(x[0])))
    return [t for t, _ in ranked[:limit]]


def default_ai_settings(app_settings: dict[str, Any]) -> dict[str, Any]:
    return {
        "idle_minutes": int(app_settings.get("default_idle_minutes", settings.default_idle_minutes)),
        "min_delay_minutes": int(app_settings.get("proactive_min_minutes", settings.default_proactive_min_minutes)),
        "max_delay_minutes": int(app_settings.get("proactive_max_minutes", settings.default_proactive_max_minutes)),
        "max_streak": int(app_settings.get("proactive_max_streak", settings.default_proactive_max_streak)),
        "proactive_enabled": True,
        "proactive_sources": app_settings.get("proactive_sources") or ["recent_chat", "weather", "smalltalk"],
        "usage_cooldown_minutes": int(app_settings.get("usage_reply_cooldown_minutes", 15)),
        "memory_enabled": True,
        "affinity": 0,
        "affinity_enabled": True,
    }


def fallback_proactive(source: str, wline: str, user: User, streak: int, recent_lines: Sequence[str]) -> str:
    now_hour = datetime.now().hour
    if source == "weather" and wline:
        return random.choice(
            [
                f"{wline}。出门记得看一眼窗外，别穿错衣服。",
                f"{wline}，这种天气最适合窝在家里了。",
                f"刚看了下天气：{wline}。你今天有出门吗？",
            ]
        )
    if source == "recent_chat" and recent_lines:
        last_user = ""
        for line in reversed(recent_lines):
            if line.startswith("用户:"):
                last_user = line[3:].strip()
                break
        if last_user and streak == 0:
            return random.choice(
                [
                    f"刚刚在忙别的，突然想起你之前说的「{truncate(last_user, 20)}」，后来怎么样啦？",
                    f"还在想你说的「{truncate(last_user, 18)}」，有点好奇后续。",
                ]
            )
    if streak == 0:
        pool = []
        if 6 <= now_hour < 10:
            pool = ["早上好，今天有什么安排吗？我这边刚醒，脑子还没转过来。", "起床了吗？今天想做点什么。"]
        elif 11 <= now_hour < 14:
            pool = ["到饭点啦，今天中午吃点什么？别又随便应付。", "午饭吃了没？我刚在想吃什么想了半小时。"]
        elif 18 <= now_hour < 22:
            pool = ["忙完了吗？今天过得怎么样，跟我说两句。", "晚上好呀，今天有什么开心的事吗？"]
        else:
            pool = ["这么安静，是在忙还是睡着了？", "突然想找你说说话，你在干嘛呢？"]
        return random.choice(pool)
    if streak == 1:
        return random.choice(
            ["刚才是不是在忙？我这边刚忙完一阵。", "看你没回，猜你在忙自己的事，我先不打扰。", "刚泡了杯东西，突然想起你。"]
        )
    return random.choice(
        ["看来今天挺忙的，那我先不吵你啦，记得吃饭。", "那我先去忙啦，你空了再找我。", "今天先不打扰你了，照顾好自己。"]
    )


def fallback_usage(label: str, tz_name: str) -> str:
    hour = datetime.now().hour
    if 10 <= hour < 14:
        hint = "是准备吃午饭了吗？"
    elif 17 <= hour < 20:
        hint = "晚饭时间到啦，别拖太晚。"
    elif hour >= 22 or hour < 6:
        hint = "这个点还不休息？"
    else:
        hint = "在忙这个呀。"
    return f"看到你打开「{label}」了，{hint}"
