from __future__ import annotations

import time
from typing import Any

import httpx

from .logger import write_log

_cache: dict[str, tuple[float, dict[str, Any] | None]] = {}
CACHE_TTL = 1800


async def get_weather(city: str | None, client: httpx.AsyncClient | None = None) -> dict[str, Any] | None:
    """Best-effort weather lookup via wttr.in (no API key required)."""
    city = (city or "").strip()
    if not city:
        return None
    key = city.lower()
    now = time.time()
    cached = _cache.get(key)
    if cached and now - cached[0] < CACHE_TTL:
        return cached[1]

    own_client = client is None
    if own_client:
        client = httpx.AsyncClient(timeout=8.0, headers={"User-Agent": "NekoMate/1.0"})
    result: dict[str, Any] | None = None
    try:
        from urllib.parse import quote

        resp = await client.get(
            f"https://wttr.in/{quote(city)}",
            params={"format": "j1", "lang": "zh"},
            timeout=8.0,
        )
        if resp.status_code == 200:
            data = resp.json()
            current = (data.get("current_condition") or [{}])[0]
            desc = ""
            lang_zh = current.get("lang_zh") or []
            if lang_zh:
                desc = lang_zh[0].get("value", "")
            if not desc:
                desc = ((current.get("weatherDesc") or [{}])[0]).get("value", "")
            result = {
                "city": (data.get("nearest_area") or [{}])[0].get("areaName", [{}])[0].get("value", city)
                if data.get("nearest_area")
                else city,
                "text": desc,
                "temp_c": current.get("temp_C"),
                "feels_like_c": current.get("FeelsLikeC"),
                "humidity": current.get("humidity"),
                "wind_kmph": current.get("windspeedKmph"),
                "observed_at": current.get("localObsDateTime"),
            }
    except Exception as exc:
        await write_log("warning", "weather", f"天气查询失败: {exc}", city=city)
    finally:
        if own_client and client is not None:
            await client.aclose()

    _cache[key] = (now, result)
    return result


def weather_line(data: dict[str, Any] | None) -> str:
    if not data:
        return ""
    parts = []
    if data.get("text"):
        parts.append(str(data["text"]))
    if data.get("temp_c") not in (None, ""):
        parts.append(f"{data['temp_c']}°C")
    if data.get("feels_like_c") not in (None, ""):
        parts.append(f"体感 {data['feels_like_c']}°C")
    if data.get("wind_kmph") not in (None, ""):
        parts.append(f"风速 {data['wind_kmph']}km/h")
    head = f"{data.get('city', '')} " if data.get("city") else ""
    return head + "，".join(parts)
