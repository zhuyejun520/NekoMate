from __future__ import annotations

from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from ..models import AppSetting

DEFAULTS: dict[str, Any] = {
    "site_name": "夜音 · AI 伴侣",
    "welcome_text": "欢迎回来，今天也一起把 AI 伴侣调教得更像真人吧。",
    "registration_enabled": True,
    "auto_create_ai_friend": True,
    "default_idle_minutes": 30,
    "proactive_min_minutes": 60,
    "proactive_max_minutes": 180,
    "proactive_max_streak": 3,
    "short_memory_days": 2,
    "weather_enabled": True,
    "timezone": "Asia/Shanghai",
    "max_context_messages": 24,
    "proactive_sources": ["recent_chat", "weather", "smalltalk"],
    "usage_reply_cooldown_minutes": 15,
    "plugin_dispatch_enabled": True,
    "admin_notice": "",
}


async def get_settings_map(session: AsyncSession) -> dict[str, Any]:
    rows = (await session.execute(select(AppSetting))).scalars().all()
    data = dict(DEFAULTS)
    for row in rows:
        data[row.key] = row.value
    return data


async def get_setting(session: AsyncSession, key: str, default: Any = None) -> Any:
    row = await session.get(AppSetting, key)
    if row is None:
        return DEFAULTS.get(key, default)
    return row.value


async def set_setting(session: AsyncSession, key: str, value: Any) -> None:
    row = await session.get(AppSetting, key)
    if row is None:
        session.add(AppSetting(key=key, value=value))
    else:
        row.value = value
    await session.commit()
