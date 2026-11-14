from __future__ import annotations

from datetime import timedelta
from typing import Any

import bcrypt
import jwt

from .config import settings
from .utils import utcnow

ALGORITHM = "HS256"


def hash_password(password: str) -> str:
    raw = (password or "").encode("utf-8")[:72]
    return bcrypt.hashpw(raw, bcrypt.gensalt(rounds=11)).decode("utf-8")


def verify_password(password: str, hashed: str) -> bool:
    if not hashed:
        return False
    try:
        return bcrypt.checkpw((password or "").encode("utf-8")[:72], hashed.encode("utf-8"))
    except Exception:
        return False


def create_token(subject: Any, role: str = "user", ttl_hours: int | None = None) -> str:
    now = utcnow()
    payload = {
        "sub": str(subject),
        "role": role,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(hours=ttl_hours or settings.token_ttl_hours)).timestamp()),
        "iss": settings.app_name,
    }
    return jwt.encode(payload, settings.secret_key, algorithm=ALGORITHM)


def decode_token(token: str) -> dict | None:
    try:
        return jwt.decode(token, settings.secret_key, algorithms=[ALGORITHM], issuer=settings.app_name)
    except Exception:
        return None
