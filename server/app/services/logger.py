from __future__ import annotations

import logging
import sys
from collections import deque
from datetime import datetime
from typing import Any, Deque

from ..utils import utcnow

logger = logging.getLogger("nekomate")
if not logger.handlers:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter("%(asctime)s | %(levelname)-7s | %(message)s", "%Y-%m-%d %H:%M:%S"))
    logger.addHandler(handler)
logger.setLevel(logging.INFO)


class LogRing:
    """In-memory tail of recent logs, used by the admin panel live view."""

    def __init__(self, size: int = 300) -> None:
        self._buf: Deque[dict] = deque(maxlen=size)

    def push(self, record: dict) -> None:
        self._buf.appendleft(record)

    def tail(self, limit: int = 100) -> list[dict]:
        return list(self._buf)[:limit]


ring = LogRing()


async def write_log(level: str, category: str, message: str, **meta: Any) -> None:
    """Persist a structured log line. Never raises."""
    level = (level or "info").lower()
    record = {
        "level": level,
        "category": category,
        "message": message,
        "meta": meta or {},
        "created_at": utcnow().isoformat(timespec="seconds"),
    }
    ring.push(record)
    text = f"[{category}] {message}"
    try:
        getattr(logger, level if level in ("debug", "info", "warning", "error", "critical") else "info")(text)
    except Exception:
        pass
    try:
        from ..database import SessionLocal
        from ..models import SystemLog

        if SessionLocal is None:
            return
        async with SessionLocal() as session:
            session.add(
                SystemLog(
                    level=level,
                    category=category,
                    message=message[:4000],
                    meta=meta or {},
                    created_at=datetime.fromisoformat(record["created_at"]),
                )
            )
            await session.commit()
    except Exception as exc:  # pragma: no cover - logging must stay silent
        try:
            logger.debug("log persist failed: %s", exc)
        except Exception:
            pass


async def prune_logs(days: int = 30) -> int:
    from sqlalchemy import delete

    from ..database import SessionLocal
    from ..models import SystemLog
    from ..utils import days_ago

    if SessionLocal is None:
        return 0
    cutoff = days_ago(days)
    async with SessionLocal() as session:
        result = await session.execute(delete(SystemLog).where(SystemLog.created_at < cutoff))
        await session.commit()
        return result.rowcount or 0
