from __future__ import annotations

import csv
import io
import json
import random
import re
from collections import Counter
from dataclasses import dataclass, field
from typing import Any, Iterable, Sequence

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import KnowledgeBase, KnowledgeChunk, KnowledgeDocument
from ..utils import count_emoji, truncate, utcnow

TS = r"\d{4}[-/年]\d{1,2}[-/月]\d{1,2}日?[ T]+\d{1,2}:\d{2}(?::\d{2})?"
PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("ts_speaker", re.compile(rf"^(?:\[)?({TS})(?:\])?\s+(.{{1,40}}?)(?:\(\d+\)|<\d+>)?\s*$")),
    ("speaker_ts", re.compile(rf"^(.{{1,40}}?)\s+({TS})\s*$")),
    ("inline", re.compile(rf"^(?:\[)?({TS})(?:\])?\s*[-–—]?\s*(.{{1,40}}?)\s*[:：]\s*(.*)$")),
    ("speaker_colon", re.compile(r"^(.{1,20}?)\s*[:：]\s+(.+)$")),
]

NOISE_LINES = re.compile(
    r"^(消息记录|聊天记录|导出时间|==========|-{3,}|={3,}|以下为新消息|以上是全部消息|MSGTYPE.*)$", re.I
)
WECHAT_SYSTEM = re.compile(r"^(\[图片\]|\[表情\]|\[语音\]|\[文件\]|\[视频\]|\[链接\]|\[转账\]|\[红包\]|\[位置\]|\[动画表情\])$")
STOPWORDS = {
    "我们", "你们", "他们", "什么", "怎么", "这个", "那个", "就是", "真的", "可以", "不是", "没有",
    "然后", "现在", "今天", "明天", "因为", "所以", "如果", "还是", "已经", "有点", "感觉", "时候",
    "自己", "知道", "觉得", "一个", "一下", "这样", "那样", "哈哈", "哈哈哈", "嗯嗯", "好吧", "其实",
}


@dataclass
class ChatMessage:
    speaker: str
    text: str
    ts: str | None = None


@dataclass
class ParsedChat:
    source_type: str
    messages: list[ChatMessage] = field(default_factory=list)
    speakers: Counter = field(default_factory=Counter)
    format_hint: str = ""


def decode_bytes(data: bytes) -> str:
    for enc in ("utf-8-sig", "utf-8", "gb18030", "utf-16", "big5"):
        try:
            return data.decode(enc)
        except Exception:
            continue
    return data.decode("utf-8", errors="ignore")


def html_to_text(html: str) -> str:
    html = re.sub(r"(?is)<(script|style).*?</\1>", "", html)
    html = re.sub(r"(?i)<br\s*/?>", "\n", html)
    html = re.sub(r"(?i)</(p|div|li|h\d)>", "\n", html)
    html = re.sub(r"(?i)<[^>]+>", "", html)
    html = (
        html.replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&quot;", '"')
    )
    return html


def parse_text_messages(text: str) -> ParsedChat:
    lines = [ln.rstrip() for ln in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    parsed = ParsedChat(source_type="txt")
    current: ChatMessage | None = None
    fmt_hits = Counter()

    for raw in lines:
        line = raw.strip("\ufeff").strip()
        if not line or NOISE_LINES.match(line):
            continue
        matched = False
        for name, pattern in PATTERNS:
            m = pattern.match(line)
            if not m:
                continue
            if name == "ts_speaker":
                if current and current.text.strip():
                    parsed.messages.append(current)
                ts, speaker = m.group(1), m.group(2).strip()
                current = ChatMessage(speaker=speaker, text="", ts=ts)
                fmt_hits[name] += 1
                matched = True
                break
            if name == "speaker_ts":
                speaker, ts = m.group(1).strip(), m.group(2)
                if current and current.text.strip():
                    parsed.messages.append(current)
                current = ChatMessage(speaker=speaker, text="", ts=ts)
                fmt_hits[name] += 1
                matched = True
                break
            if name == "inline":
                ts, speaker, content = m.group(1), m.group(2).strip(), m.group(3).strip()
                if current and current.text.strip():
                    parsed.messages.append(current)
                    current = None
                parsed.messages.append(ChatMessage(speaker=speaker, text=content, ts=ts))
                fmt_hits[name] += 1
                matched = True
                break
            if name == "speaker_colon":
                speaker, content = m.group(1).strip(), m.group(2).strip()
                if speaker in ("http", "https", "时间", "发送者", "内容"):
                    continue
                if len(speaker) > 12 or re.search(r"\d{2}:\d{2}", speaker):
                    continue
                if current and current.text.strip() and current.speaker == speaker and not current.ts:
                    current.text += "\n" + content
                else:
                    if current and current.text.strip():
                        parsed.messages.append(current)
                    current = ChatMessage(speaker=speaker, text=content)
                fmt_hits[name] += 1
                matched = True
                break
        if matched:
            continue
        if current is not None:
            current.text = (current.text + "\n" + line).strip()
        else:
            current = ChatMessage(speaker="未知", text=line)

    if current and current.text.strip():
        parsed.messages.append(current)

    parsed.messages = [m for m in parsed.messages if m.text and not WECHAT_SYSTEM.match(m.text.strip())]
    for m in parsed.messages:
        parsed.speakers[m.speaker] += 1
    if fmt_hits.get("ts_speaker") or fmt_hits.get("speaker_ts"):
        parsed.source_type = "qq"
        parsed.format_hint = "时间戳 + 昵称"
    elif fmt_hits.get("inline"):
        parsed.source_type = "wechat"
        parsed.format_hint = "内联时间戳 + 昵称"
    elif fmt_hits.get("speaker_colon"):
        parsed.source_type = "wechat"
        parsed.format_hint = "昵称: 内容"
    return parsed


def parse_json_messages(text: str) -> ParsedChat:
    data = json.loads(text)
    items: list[Any] = []
    if isinstance(data, list):
        items = data
    elif isinstance(data, dict):
        for key in ("messages", "chatRecords", "records", "data", "list", "chats"):
            value = data.get(key)
            if isinstance(value, list):
                items = value
                break
        if not items and isinstance(data.get("data"), dict):
            for key in ("messages", "records", "list"):
                value = data["data"].get(key)
                if isinstance(value, list):
                    items = value
                    break
    parsed = ParsedChat(source_type="json", format_hint="JSON 结构化")
    for item in items:
        if not isinstance(item, dict):
            continue
        speaker = (
            item.get("sender") or item.get("from") or item.get("name") or item.get("nickname")
            or item.get("speaker") or item.get("talker") or item.get("displayName")
        )
        content = (
            item.get("content") or item.get("text") or item.get("message") or item.get("msg")
            or item.get("plainText") or item.get("body")
        )
        if isinstance(content, dict):
            content = content.get("text") or content.get("content") or ""
        ts = item.get("time") or item.get("timestamp") or item.get("date") or item.get("createTime")
        if speaker is None or content is None:
            continue
        parsed.messages.append(ChatMessage(speaker=str(speaker)[:40], text=str(content).strip(), ts=str(ts) if ts else None))
    for m in parsed.messages:
        parsed.speakers[m.speaker] += 1
    return parsed


def parse_csv_messages(text: str) -> ParsedChat:
    parsed = ParsedChat(source_type="csv", format_hint="CSV 表格")
    reader = csv.reader(io.StringIO(text))
    rows = [r for r in reader if any((c or "").strip() for c in r)]
    if not rows:
        return parsed
    header = [c.strip().lower() for c in rows[0]]
    idx_speaker = idx_content = idx_ts = None
    for i, h in enumerate(header):
        if idx_speaker is None and h in ("sender", "from", "name", "nickname", "speaker", "昵称", "发送者", "发送人", "说话人"):
            idx_speaker = i
        if idx_content is None and h in ("content", "text", "message", "msg", "内容", "消息", "聊天内容"):
            idx_content = i
        if idx_ts is None and h in ("time", "timestamp", "date", "时间", "日期"):
            idx_ts = i
    if idx_speaker is None or idx_content is None:
        idx_speaker, idx_content, idx_ts = 0, 1, 2 if len(header) > 2 else None
        rows = rows
    else:
        rows = rows[1:]
    for row in rows:
        if len(row) <= max(idx_speaker, idx_content):
            continue
        parsed.messages.append(
            ChatMessage(
                speaker=row[idx_speaker].strip()[:40] or "未知",
                text=row[idx_content].strip(),
                ts=row[idx_ts].strip() if idx_ts is not None and len(row) > idx_ts else None,
            )
        )
    for m in parsed.messages:
        parsed.speakers[m.speaker] += 1
    return parsed


def parse_chat(text: str, filename: str = "") -> ParsedChat:
    lower = (filename or "").lower()
    stripped = text.lstrip()
    if lower.endswith(".json") or stripped.startswith("{") or stripped.startswith("["):
        try:
            parsed = parse_json_messages(text)
            if parsed.messages:
                return parsed
        except Exception:
            pass
    if lower.endswith(".csv") and "," in text[:500]:
        try:
            parsed = parse_csv_messages(text)
            if parsed.messages:
                return parsed
        except Exception:
            pass
    if lower.endswith((".html", ".htm")) or re.search(r"(?i)<html|<div|<p[ >]", text[:2000]):
        text = html_to_text(text)
    return parse_text_messages(text)


def pick_target_speaker(parsed: ParsedChat, requested: str | None) -> str | None:
    if requested:
        for speaker in parsed.speakers:
            if speaker == requested or requested in speaker:
                return speaker
    if not parsed.speakers:
        return None
    filtered = {s: c for s, c in parsed.speakers.items() if s not in ("未知", "我", "系统")}
    pool = filtered or dict(parsed.speakers)
    return max(pool.items(), key=lambda x: x[1])[0]


def build_samples(messages: Sequence[ChatMessage], target: str, max_samples: int = 300) -> list[dict[str, Any]]:
    samples: list[dict[str, Any]] = []
    for i, m in enumerate(messages):
        if m.speaker != target:
            continue
        text = m.text.strip()
        if len(text) < 2 or len(text) > 300:
            continue
        context: list[str] = []
        j = i - 1
        while j >= 0 and len(context) < 2:
            prev = messages[j]
            if prev.speaker == target and context:
                break
            context.insert(0, f"{prev.speaker}：{truncate(prev.text, 80)}")
            j -= 1
        line = f"{target}：{truncate(text, 200)}"
        content = "\n".join(context + [line])
        quality = 0.4
        if 6 <= len(text) <= 60:
            quality += 0.3
        if count_emoji(text):
            quality += 0.1
        if len(context) >= 2:
            quality += 0.2
        samples.append({"content": content, "text": text, "quality": round(min(quality, 1.0), 2)})
    if len(samples) > max_samples:
        random.seed(len(samples))
        samples = random.sample(samples, max_samples)
        samples.sort(key=lambda s: s["quality"], reverse=True)
    return samples


def build_style_profile(messages: Sequence[ChatMessage], target: str, samples: Sequence[dict[str, Any]]) -> dict[str, Any]:
    texts = [m.text.strip() for m in messages if m.speaker == target and m.text.strip()]
    if not texts:
        return {}
    lengths = [len(t) for t in texts]
    emoji_total = sum(count_emoji(t) for t in texts)
    no_punct = sum(1 for t in texts if not re.search(r"[，。！？,.!?~～…]", t))
    tokens: Counter = Counter()
    openers: Counter = Counter()
    endings: Counter = Counter()
    for t in texts:
        for token in re.findall(r"[\u4e00-\u9fa5]{2,4}", t):
            if token not in STOPWORDS:
                tokens[token] += 1
        clean = re.sub(r"[\s，。！？,.!?~～…]+", "", t)
        if len(clean) >= 2:
            openers[clean[:2]] += 1
            endings[clean[-2:]] += 1
    avg_len = sum(lengths) / len(lengths)
    repeated = [(w, c) for w, c in tokens.items() if c >= 2]
    repeated.sort(key=lambda x: (-x[1], -len(x[0])))
    profile = {
        "speaker": target,
        "messages": len(texts),
        "avg_len": round(avg_len, 1),
        "median_len": sorted(lengths)[len(lengths) // 2],
        "max_len": max(lengths),
        "emoji_per_100": round(emoji_total / max(len(texts), 1) * 100, 1),
        "no_punct_ratio": round(no_punct / len(texts), 2),
        "top_words": [w for w, _ in repeated[:12]],
        "openers": [w for w, _ in openers.most_common(6)],
        "endings": [w for w, _ in endings.most_common(6)],
        "sample_lines": [s["text"] for s in samples[:5]],
        "updated_at": utcnow().isoformat(timespec="seconds"),
    }
    return profile


async def import_chat_text(
    session: AsyncSession,
    kb: KnowledgeBase,
    *,
    title: str,
    raw_text: str,
    target_speaker: str | None = None,
    source_type: str | None = None,
    max_samples: int = 300,
) -> dict[str, Any]:
    parsed = parse_chat(raw_text, filename=title)
    if source_type and source_type != "auto":
        parsed.source_type = source_type
    target = pick_target_speaker(parsed, target_speaker)
    if not parsed.messages:
        raise ValueError("没有解析到任何聊天消息，请检查文件格式（支持微信/QQ 导出的 txt、json、csv、html）")
    if target is None:
        raise ValueError("无法识别说话人，请在导入时手动指定 target_speaker")

    samples = build_samples(parsed.messages, target, max_samples=max_samples)
    if not samples:
        raise ValueError(f"「{target}」没有可用于模仿的对话样本（消息太短或太少）")
    profile = build_style_profile(parsed.messages, target, samples)

    doc = KnowledgeDocument(
        kb_id=kb.id,
        title=title[:200],
        source_type=parsed.source_type,
        raw_text=raw_text[:2_000_000],
        target_speaker=target,
        message_count=len(parsed.messages),
        chunk_count=len(samples),
        meta={
            "format": parsed.format_hint or parsed.source_type,
            "speakers": dict(parsed.speakers.most_common(20)),
            "filename": title,
        },
    )
    session.add(doc)
    await session.flush()

    for s in samples:
        keywords = re.findall(r"[\u4e00-\u9fa5]{2,4}", s["text"])
        session.add(
            KnowledgeChunk(
                kb_id=kb.id,
                doc_id=doc.id,
                speaker=target,
                content=s["content"],
                kind="dialogue",
                keywords=[k for k in keywords if k not in STOPWORDS][:8],
                quality=s["quality"],
            )
        )

    kb.style_profile = profile
    stats = dict(kb.stats or {})
    stats["documents"] = int(stats.get("documents", 0)) + 1
    stats["chunks"] = int(stats.get("chunks", 0)) + len(samples)
    stats["messages"] = int(stats.get("messages", 0)) + len(parsed.messages)
    stats["last_import_at"] = utcnow().isoformat(timespec="seconds")
    kb.stats = stats
    await session.commit()
    return {
        "document_id": doc.id,
        "messages": len(parsed.messages),
        "samples": len(samples),
        "target_speaker": target,
        "speakers": dict(parsed.speakers.most_common(20)),
        "style_profile": profile,
        "source_type": parsed.source_type,
    }


async def preview_chat_text(raw_text: str, filename: str = "", target_speaker: str | None = None) -> dict[str, Any]:
    parsed = parse_chat(raw_text, filename=filename)
    target = pick_target_speaker(parsed, target_speaker)
    samples = build_samples(parsed.messages, target, max_samples=30) if target else []
    return {
        "source_type": parsed.source_type,
        "format": parsed.format_hint,
        "messages": len(parsed.messages),
        "speakers": dict(parsed.speakers.most_common(20)),
        "target_speaker": target,
        "preview": [s["content"] for s in samples[:8]],
        "style_profile": build_style_profile(parsed.messages, target, samples) if target else {},
    }


async def delete_document(session: AsyncSession, doc: KnowledgeDocument) -> None:
    await session.execute(delete(KnowledgeChunk).where(KnowledgeChunk.doc_id == doc.id))
    await session.delete(doc)
    await session.commit()


async def kb_counters(session: AsyncSession, kb_id: int) -> tuple[int, int]:
    docs = (await session.execute(select(func.count(KnowledgeDocument.id)).where(KnowledgeDocument.kb_id == kb_id))).scalar() or 0
    chunks = (await session.execute(select(func.count(KnowledgeChunk.id)).where(KnowledgeChunk.kb_id == kb_id))).scalar() or 0
    return int(docs), int(chunks)


async def rebuild_style_from_documents(session: AsyncSession, kb: KnowledgeBase) -> bool:
    """Re-learn a style KB from its existing raw documents.

    Used when a fact library is switched to style imitation, or when an older
    style KB has no profile. Documents that cannot be parsed as a chat are left
    untouched, so a plain fact library is not destroyed accidentally.
    """
    docs = (
        await session.execute(
            select(KnowledgeDocument).where(KnowledgeDocument.kb_id == kb.id)
        )
    ).scalars().all()
    changed = False
    total_chunks = 0
    for doc in docs:
        raw = (doc.raw_text or "").strip()
        if not raw:
            continue
        parsed = parse_chat(raw, filename=doc.title or "")
        target = pick_target_speaker(parsed, doc.target_speaker)
        if not parsed.messages or not target:
            continue
        samples = build_samples(parsed.messages, target, max_samples=300)
        if not samples:
            continue
        await session.execute(delete(KnowledgeChunk).where(KnowledgeChunk.doc_id == doc.id))
        for sample in samples:
            keywords = re.findall(r"[\u4e00-\u9fa5]{2,4}", sample["text"])
            session.add(
                KnowledgeChunk(
                    kb_id=kb.id,
                    doc_id=doc.id,
                    speaker=target,
                    content=sample["content"],
                    kind="dialogue",
                    keywords=keywords[:8],
                    quality=sample["quality"],
                )
            )
        profile = build_style_profile(parsed.messages, target, samples)
        if profile:
            kb.style_profile = profile
        doc.target_speaker = target
        doc.message_count = len(parsed.messages)
        doc.chunk_count = len(samples)
        total_chunks += len(samples)
        changed = True
    if changed:
        stats = dict(kb.stats or {})
        stats["chunks"] = total_chunks
        kb.stats = stats
        await session.commit()
    return changed
