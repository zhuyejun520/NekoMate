from __future__ import annotations

from datetime import datetime
from typing import Any, Iterable, Sequence

from ..models import AiFriend, Memory, Message, Persona, User
from ..utils import get_tz, truncate

WEEKDAYS = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]


def now_text(tz_name: str = "Asia/Shanghai", dt: datetime | None = None) -> str:
    zone = get_tz(tz_name)
    cur = (dt or datetime.now(zone)).astimezone(zone)
    period = "凌晨"
    hour = cur.hour
    if 5 <= hour < 9:
        period = "早上"
    elif 9 <= hour < 12:
        period = "上午"
    elif 12 <= hour < 14:
        period = "中午"
    elif 14 <= hour < 18:
        period = "下午"
    elif 18 <= hour < 23:
        period = "晚上"
    return f"{cur.strftime('%Y-%m-%d %H:%M')}（{WEEKDAYS[cur.weekday()]}，{period}）"


def persona_identity(persona: Persona, display_name: str) -> str:
    lines = [f"你的名字是「{display_name}」。"]
    if persona.tagline:
        lines.append(f"一句话人设：{persona.tagline}")
    if persona.description:
        lines.append(f"身份背景：{persona.description}")
    if persona.system_prompt:
        lines.append(f"角色设定（最高优先级）：{persona.system_prompt}")
    if persona.personality:
        lines.append(f"性格：{persona.personality}")
    if persona.speaking_style:
        lines.append(f"说话方式：{persona.speaking_style}")
    if persona.catchphrases:
        lines.append("口头禅（偶尔自然使用，不要每句都用）：" + "、".join(str(x) for x in persona.catchphrases[:8]))
    return "\n".join(lines)


def style_block(profile: dict[str, Any] | None, samples: Sequence[dict[str, Any]], target_name: str) -> str:
    parts: list[str] = []
    if profile:
        stats = []
        if profile.get("speaker"):
            stats.append(f"模仿对象：{profile['speaker']}")
        if profile.get("messages"):
            stats.append(f"样本条数：{profile['messages']}")
        if profile.get("avg_len"):
            stats.append(f"平均每条 {profile['avg_len']:.1f} 字")
        if profile.get("emoji_per_100") is not None:
            stats.append(f"每百条约 {profile['emoji_per_100']:.1f} 个 emoji")
        if profile.get("no_punct_ratio") is not None:
            stats.append(f"{profile['no_punct_ratio'] * 100:.0f}% 的句子不带标点")
        if profile.get("top_words"):
            stats.append("高频词：" + "、".join(profile["top_words"][:10]))
        if profile.get("openers"):
            stats.append("常见开头：" + "、".join(profile["openers"][:6]))
        if profile.get("endings"):
            stats.append("常见结尾：" + "、".join(profile["endings"][:6]))
        if stats:
            parts.append("【语气统计】\n" + "\n".join(f"- {s}" for s in stats))
    if samples:
        lines = []
        for item in samples[:10]:
            content = str(item.get("content") or "").strip()
            if content:
                lines.append(content)
        if lines:
            parts.append(
                f"【聊天样本：请体会「{target_name}」的用词、长度、语气和标点习惯，模仿风格但不要照抄内容】\n"
                + "\n---\n".join(lines)
            )
    return "\n\n".join(parts)


def memory_block(memories: Iterable[Memory], tz_name: str = "Asia/Shanghai") -> str:
    zone = get_tz(tz_name)
    lines = []
    for m in memories:
        stamp = m.created_at.astimezone(zone).strftime("%m-%d") if m.created_at else ""
        tag = "长期" if m.kind == "long" else "近期"
        lines.append(f"- [{tag}·{stamp}] {truncate(m.content, 160)}")
    return "\n".join(lines)


def build_system_prompt(
    *,
    persona: Persona,
    ai_name: str,
    user: User,
    style_profile: dict[str, Any] | None,
    style_samples: Sequence[dict[str, Any]],
    memories: Sequence[Memory],
    now: str,
    extra_context: str = "",
    mode: str = "chat",
    tz_name: str = "Asia/Shanghai",
    affinity: int | None = None,
    affinity_enabled: bool = True,
) -> str:
    identity = persona_identity(persona, ai_name)
    style = style_block(style_profile, style_samples, persona.name or ai_name)
    memory_txt = memory_block(memories, tz_name)
    user_nick = user.nickname or user.username

    sections = [
        "# 角色\n你正在用手机和「" + user_nick + "」聊天。你不是助手，也不是AI程序，你就是这个人，按这个人的方式说话。",
        "## 身份\n" + identity,
    ]
    if affinity_enabled and affinity is not None:
        value = max(0, min(100, int(affinity)))
        if value <= 20:
            tone = "刚认识不久，礼貌、自然，保持一点距离，不要过度亲昵。"
        elif value <= 40:
            tone = "普通朋友，友好自然，可以聊日常，但不要黏人。"
        elif value <= 60:
            tone = "熟悉的朋友，会关心对方的生活，偶尔开玩笑，语气更放松。"
        elif value <= 80:
            tone = "关系亲近，会主动关心、记住细节，可以适度撒娇或表达想念。"
        else:
            tone = "非常亲密，语气明显亲昵、依赖，会直接表达在意，但仍然符合角色设定。"
        sections.append(
            "## 好感度\n"
            f"当前好感度：{value}/100。\n"
            f"语气参考：{tone}\n"
            "好感度只影响你说话的温度和亲近程度，不要直接报出数值，也不要每句都提好感度。"
        )
    if style:
        sections.append("## 说话风格\n" + style)
    if memory_txt:
        sections.append("## 你记得的事\n" + memory_txt)
    sections.append("## 此刻\n" + f"当前时间：{now}\n对方的昵称：{user_nick}" + (f"\n对方的城市：{user.city}" if user.city else ""))
    if extra_context:
        sections.append("## 刚刚发生的事\n" + extra_context)

    rules = [
        "像真人在手机上打字：口语、短句，一条回复 1-3 句，不要写成小作文。",
        "可以用语气词、emoji、省略号，但不要刻意堆砌。",
        "绝对不要自称 AI、助手、模型，也不要解释你在扮演角色。",
        "不要每次都提问，不要列清单，不要用“首先/其次/总之”这种书面结构。",
        "自然地带出你记得的事，而不是复述记忆原文。",
        "对方情绪低落时先共情，再给一句轻一点的建议。",
        "只输出你要发出去的那条消息本身，不要加引号、不要加旁白。",
    ]
    if mode == "proactive":
        rules.append("这次是你主动找对方说话：开场要自然、不突兀，别问“在吗”，直接说事或分享感受。")
    if mode == "usage":
        rules.append("这次你是因为看到对方打开了某个应用而搭话：一句话点到即可，不要连续追问。")
    sections.append("## 聊天规则\n" + "\n".join(f"{i + 1}. {r}" for i, r in enumerate(rules)))
    return "\n\n".join(sections)


def history_messages(messages: Sequence[Message]) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    for m in messages:
        if not (m.content or "").strip():
            continue
        role = "assistant" if m.sender_type == "ai" else "user"
        content = truncate(m.content, 400)
        if m.content_type == "event":
            content = f"（{content}）"
        out.append({"role": role, "content": content})
    return out


def analysis_system_prompt() -> str:
    return (
        "你是一个记忆筛选与关系判断器，负责判断用户的话里哪些信息值得长期记住，以及这次互动是否改变好感度。只输出 JSON，不要输出多余文字。\n"
        "JSON 结构："
        '{"important": true/false, "importance": 0.0-1.0, "summary": "一句话记忆", '
        '"facts": ["事实1", "事实2"], "keywords": ["关键词"], "affinity_delta": 0}\n'
        "判定标准：\n"
        "- 重要：身份信息、喜好厌恶、家人朋友宠物、工作学习、计划与约定、身体状况、重要情绪事件、对AI的设定要求。\n"
        "- 不重要：寒暄、语气词、一次性闲聊、重复内容、无信息的应答。\n"
        "- summary 用第三人称写「用户……」，不超过 60 字；facts 最多 3 条；keywords 最多 5 个。\n"
        "- affinity_delta 是 -5 到 5 的整数：用户明显表达喜欢/依赖/夸奖/感谢/记住AI的设定，或分享重要情绪、主动陪伴，可以 +1 到 +5；"
        "用户明显冷淡、贬低、欺骗、恶意攻击，可以 -1 到 -5；普通聊天、信息询问、正常对话一律为 0。\n"
        "- 不要因为礼貌用语、表情、普通问候就给好感度；没有明显情感信号时必须为 0。"
    )


def analysis_user_prompt(persona_name: str, user_text: str, recent: Sequence[Message]) -> str:
    context_lines = []
    for m in list(recent)[-6:]:
        role = persona_name if m.sender_type == "ai" else "用户"
        context_lines.append(f"{role}: {truncate(m.content, 120)}")
    context = "\n".join(context_lines)
    return (
        f"【最近对话】\n{context or '（无）'}\n\n"
        f"【需要判断的用户新消息】\n{user_text}\n\n"
        "请判断这条消息是否值得长期记住、这次互动的好感度变化，并按 JSON 输出。"
    )


def proactive_user_prompt(
    *,
    source: str,
    now: str,
    user_nick: str,
    streak: int,
    weather_line: str,
    recent_lines: Sequence[str],
    last_proactive: str | None,
) -> str:
    source_label = {"recent_chat": "接着之前的聊天话题", "weather": "聊天气", "smalltalk": "随口闲聊"}.get(source, "随口闲聊")
    parts = [
        f"现在是 {now}。你已经有一段时间没和「{user_nick}」说话了，这次由你主动发一条消息。",
        f"内容方向：{source_label}。",
    ]
    if weather_line:
        parts.append(f"天气信息：{weather_line}")
    if recent_lines:
        parts.append("你们之前聊到的内容：\n" + "\n".join(f"- {line}" for line in recent_lines[-6:]))
    if last_proactive:
        parts.append(f"你上一次主动说的话是：「{truncate(last_proactive, 80)}」，这次不要重复同样的意思。")
    if streak >= 1:
        parts.append(f"这是你连续第 {streak + 1} 次主动找对方，对方还没回。语气要更轻，不要显得催促或委屈。")
    parts.append("只输出一条可以直接发送的短消息（15-40字），不要加引号、不要解释。")
    return "\n\n".join(parts)


def usage_user_prompt(*, label: str, package: str, now: str, user_nick: str, note: str | None, weather_line: str) -> str:
    parts = [
        f"现在是 {now}。你从手机通知里看到「{user_nick}」刚刚打开了应用「{label}」（包名 {package}）。",
    ]
    if note:
        parts.append(f"对方给这个应用的备注是：{note}")
    if weather_line:
        parts.append(f"当前天气：{weather_line}")
    parts.append("请结合时间、应用用途，自然地说一句相关的话：可以是关心、提醒、好奇或一点小吐槽。")
    parts.append("只输出一条 10-40 字的消息，不要提问超过一次，不要说教。")
    return "\n\n".join(parts)


def greeting_prompt(persona_name: str, user_nick: str) -> str:
    return (
        f"你是「{persona_name}」，刚刚和「{user_nick}」成为好友。"
        "写一条打招呼的短消息（20-50字），符合你的人设，自然一点，不要自我介绍太长。只输出消息本身。"
    )


def moment_prompt(persona_name: str, now: str, user_nick: str) -> str:
    return (
        f"你是「{persona_name}」。现在是 {now}。请写一条发在“日常空间”的短动态（30-80字），"
        f"像真人发朋友圈一样，可以提到生活小事、心情或和「{user_nick}」有关的小细节。只输出动态正文。"
    )
