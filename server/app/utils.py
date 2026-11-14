from __future__ import annotations

import json
import random
import re
import string
import time
from datetime import datetime, timedelta, timezone, tzinfo
from typing import Any
from zoneinfo import ZoneInfo

UTC = timezone.utc
DEFAULT_TZ_NAME = "Asia/Shanghai"
FALLBACK_TZ = timezone(timedelta(hours=8))


def get_tz(name: str | None = None) -> tzinfo:
    """ZoneInfo with a fixed +08:00 fallback for images without tzdata."""
    try:
        return ZoneInfo(name or DEFAULT_TZ_NAME)
    except Exception:
        return FALLBACK_TZ


def utcnow() -> datetime:
    return datetime.now(UTC)


def iso(dt: datetime | None) -> str | None:
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=UTC)
    return dt.astimezone(UTC).isoformat(timespec="seconds")


def parse_dt(value: Any) -> datetime | None:
    if value in (None, ""):
        return None
    if isinstance(value, datetime):
        return value if value.tzinfo else value.replace(tzinfo=UTC)
    if isinstance(value, (int, float)):
        stamp = float(value)
        if stamp > 1e11:  # milliseconds
            stamp /= 1000.0
        return datetime.fromtimestamp(stamp, UTC)
    text = str(value).strip().replace("Z", "+00:00")
    for fmt in (None, "%Y-%m-%d %H:%M:%S", "%Y/%m/%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S", "%Y-%m-%d %H:%M"):
        try:
            if fmt:
                return datetime.strptime(text, fmt).replace(tzinfo=UTC)
            return datetime.fromisoformat(text).astimezone(UTC)
        except Exception:
            continue
    return None


def random_delay_minutes(min_minutes: int, max_minutes: int) -> int:
    lo, hi = max(1, int(min_minutes)), max(1, int(max_minutes))
    if hi < lo:
        lo, hi = hi, lo
    return random.randint(lo, hi)


def now_ms() -> int:
    return int(time.time() * 1000)


def mask_secret(secret: str | None, keep: int = 4) -> str:
    if not secret:
        return ""
    if len(secret) <= keep * 2:
        return "*" * len(secret)
    return f"{secret[:keep]}***{secret[-keep:]}"


def json_loads(text: Any, default: Any = None) -> Any:
    if isinstance(text, (dict, list)):
        return text
    if not text:
        return default
    try:
        return json.loads(text)
    except Exception:
        return default


def json_dumps(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, default=str)


_emoji_re = re.compile("[\U0001F000-\U0001FAFF\u2600-\u27BF\uFE0F]")


def has_emoji(text: str) -> bool:
    return bool(_emoji_re.search(text or ""))


def count_emoji(text: str) -> int:
    return len(_emoji_re.findall(text or ""))


def rand_token(n: int = 8) -> str:
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def safe_filename(name: str, fallback: str = "upload") -> str:
    name = (name or "").strip().replace("\\", "/").split("/")[-1]
    name = re.sub(r"[^\w.\-\u4e00-\u9fff]+", "_", name)
    return name[:120] or fallback


def human_size(num: int) -> str:
    value = float(num)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{value:.1f}{unit}"
        value /= 1024
    return f"{num}B"


def days_ago(days: int) -> datetime:
    return utcnow() - timedelta(days=days)


def truncate(text: str, limit: int = 300) -> str:
    text = (text or "").strip()
    return text if len(text) <= limit else text[: limit - 1] + "…"


def strip_code_fence(text: str) -> str:
    if not text:
        return ""
    text = text.strip()
    m = re.match(r"^```(?:json|python|text)?\s*(.*?)\s*```$", text, re.S)
    return m.group(1).strip() if m else text
