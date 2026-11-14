from __future__ import annotations

import asyncio
import base64
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import httpx
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from ..config import settings
from ..models import ModelProvider
from ..utils import strip_code_fence
from ..services.logger import write_log


class ProviderError(RuntimeError):
    pass


@dataclass
class ProviderSpec:
    id: int | None
    name: str
    kind: str
    base_url: str
    api_key: str
    model: str
    extra: dict[str, Any] = field(default_factory=dict)
    priority: int = 10

    @property
    def is_demo(self) -> bool:
        return self.kind == "demo" or (self.base_url or "").startswith("demo://")


DEMO_SPEC = ProviderSpec(id=None, name="内置演示模型", kind="demo", base_url="demo://local", api_key="", model="demo", priority=999)

_client: httpx.AsyncClient | None = None


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.ai_request_timeout, connect=15.0),
            limits=httpx.Limits(max_connections=40, max_keepalive_connections=10),
            headers={"User-Agent": f"{settings.app_name}/{settings.version}"},
        )
    return _client


async def close_client() -> None:
    global _client
    if _client is not None and not _client.is_closed:
        await _client.aclose()
    _client = None


def normalize_base_url(url: str) -> str:
    u = (url or "").strip().rstrip("/")
    for suffix in ("/chat/completions", "/images/generations", "/audio/transcriptions", "/completions", "/embeddings", "/models"):
        if u.endswith(suffix):
            u = u[: -len(suffix)].rstrip("/")
    if not u:
        return u
    parsed = urlparse(u)
    if parsed.path in ("", "/"):
        return u + "/v1"
    return u


def _stt_base_url(url: str) -> str:
    """Managed Agents endpoints are not ASR endpoints; correct common Zhipu URL mistakes."""
    value = (url or "").strip()
    if "agent-api.bigmodel.cn" in value or "/api/agent/managed" in value:
        value = "https://open.bigmodel.cn/api/paas/v4"
    return normalize_base_url(value)


def _headers(spec: ProviderSpec) -> dict[str, str]:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if spec.api_key:
        headers["Authorization"] = f"Bearer {spec.api_key}"
    extra_headers = (spec.extra or {}).get("headers")
    if isinstance(extra_headers, dict):
        headers.update({str(k): str(v) for k, v in extra_headers.items()})
    return headers


def _json_body(spec: ProviderSpec, payload: dict[str, Any]) -> dict[str, Any]:
    body = dict(payload)
    body["model"] = payload.get("model") or spec.model
    if not body["model"]:
        raise ProviderError("模型名称未填写，请在管理面板补全 model 字段")
    extra_body = (spec.extra or {}).get("body")
    if isinstance(extra_body, dict):
        for k, v in extra_body.items():
            body.setdefault(k, v)
    return body


def spec_from_row(row: ModelProvider) -> ProviderSpec:
    return ProviderSpec(
        id=row.id,
        name=row.name,
        kind=row.kind,
        base_url=row.base_url,
        api_key=row.api_key or "",
        model=row.model or "",
        extra=row.extra or {},
        priority=row.priority or 10,
    )


async def pick_provider(session: AsyncSession, kind: str, user_id: int | None = None) -> ProviderSpec:
    """Return the best enabled provider for a role.

    If the user has a private (owner-bound) provider, only that pool is considered:
    a user with a dedicated key never consumes a global key. If no matching private
    provider exists, the demo provider is returned instead of falling back globally.
    """
    if user_id:
        private_total = (
            await session.execute(
                select(func.count(ModelProvider.id)).where(ModelProvider.owner_user_id == user_id)
            )
        ).scalar() or 0
        if private_total:
            private_rows = (
                await session.execute(
                    select(ModelProvider)
                    .where(ModelProvider.owner_user_id == user_id, ModelProvider.enabled.is_(True))
                    .order_by(ModelProvider.priority.asc(), ModelProvider.id.asc())
                )
            ).scalars().all()
            exact = [row for row in private_rows if row.kind == kind]
            if exact:
                return spec_from_row(exact[0])
            if kind == "analysis":
                chat_rows = [row for row in private_rows if row.kind == "chat"]
                if chat_rows:
                    return spec_from_row(chat_rows[0])
            if kind == "vision":
                for fallback in ("analysis", "chat"):
                    rows = [row for row in private_rows if row.kind == fallback]
                    if rows:
                        return spec_from_row(rows[0])
            if kind == "image":
                image_rows = [row for row in private_rows if row.kind == "image"]
                if image_rows:
                    return spec_from_row(image_rows[0])
            return DEMO_SPEC

    rows = (
        await session.execute(
            select(ModelProvider)
            .where(ModelProvider.kind == kind, ModelProvider.enabled.is_(True), ModelProvider.owner_user_id.is_(None))
            .order_by(ModelProvider.priority.asc(), ModelProvider.id.asc())
        )
    ).scalars().all()
    if rows:
        return spec_from_row(rows[0])

    if kind in ("analysis", "chat"):
        rows = (
            await session.execute(
                select(ModelProvider)
                .where(
                    ModelProvider.kind == "chat",
                    ModelProvider.enabled.is_(True),
                    ModelProvider.owner_user_id.is_(None),
                )
                .order_by(ModelProvider.priority.asc(), ModelProvider.id.asc())
            )
        ).scalars().all()
        if rows:
            return spec_from_row(rows[0])

    if kind == "vision":
        for fallback in ("analysis", "chat"):
            rows = (
                await session.execute(
                    select(ModelProvider)
                    .where(
                        ModelProvider.kind == fallback,
                        ModelProvider.enabled.is_(True),
                        ModelProvider.owner_user_id.is_(None),
                    )
                    .order_by(ModelProvider.priority.asc(), ModelProvider.id.asc())
                )
            ).scalars().all()
            if rows:
                return spec_from_row(rows[0])

    return DEMO_SPEC


async def pick_stt_provider(session: AsyncSession, user_id: int | None = None) -> ProviderSpec:
    """Prefer a Zhipu/BigModel provider (GLM-ASR) and otherwise fall back to chat creds."""
    rows = (
        await session.execute(
            select(ModelProvider)
            .where(ModelProvider.enabled.is_(True))
            .order_by(ModelProvider.priority.asc(), ModelProvider.id.asc())
        )
    ).scalars().all()
    if user_id:
        private = [row for row in rows if row.owner_user_id == user_id]
        for row in private:
            if row.kind == "stt":
                return spec_from_row(row)
        for row in private:
            if "bigmodel.cn" in (row.base_url or ""):
                return spec_from_row(row)
    # No user-private STT key: use a global STT provider. A private chat key
    # (DeepSeek etc.) usually has no /audio/transcriptions and would return 404.
    global_rows = [row for row in rows if row.owner_user_id is None]
    for row in global_rows:
        if row.kind == "stt":
            return spec_from_row(row)
    for row in global_rows:
        if "bigmodel.cn" in (row.base_url or ""):
            return spec_from_row(row)
    for row in rows:
        if row.kind == "stt":
            return spec_from_row(row)
    for row in rows:
        if row.kind == "chat":
            return spec_from_row(row)
    return await pick_provider(session, "chat", user_id=user_id)


async def has_private_key(session: AsyncSession, user_id: int) -> bool:
    count = (
        await session.execute(
            select(func.count(ModelProvider.id)).where(ModelProvider.owner_user_id == user_id)
        )
    ).scalar() or 0
    return bool(count)


async def _post_with_retry(url: str, headers: dict[str, str], body: dict[str, Any], *, attempts: int = 3) -> httpx.Response:
    client = get_client()
    last_exc: Exception | None = None
    for i in range(attempts):
        try:
            resp = await client.post(url, headers=headers, json=body)
            if resp.status_code in (429, 500, 502, 503, 504) and i < attempts - 1:
                await asyncio.sleep(1.2 * (i + 1))
                continue
            return resp
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            last_exc = exc
            if i < attempts - 1:
                await asyncio.sleep(1.0 * (i + 1))
                continue
            raise ProviderError(f"网络请求失败: {exc}") from exc
    raise ProviderError(f"网络请求失败: {last_exc}")


def _extract_content(data: dict[str, Any]) -> str:
    choices = data.get("choices") or []
    if not choices:
        raise ProviderError(f"响应中没有 choices: {str(data)[:300]}")
    message = choices[0].get("message") or {}
    content = message.get("content")
    if isinstance(content, list):
        parts = []
        for item in content:
            if isinstance(item, dict):
                parts.append(item.get("text") or item.get("content") or "")
            else:
                parts.append(str(item))
        content = "".join(parts)
    if not content:
        # Never fall back to reasoning_content: reasoning models expose their
        # chain-of-thought there, and it must never be sent to the user as a
        # chat reply. If reasoning consumed the token budget, fail cleanly.
        content = choices[0].get("text") or ""
    text = strip_code_fence(str(content or "")).strip()
    if not text:
        raise ProviderError("模型返回了空内容")
    return text


async def chat(
    spec: ProviderSpec,
    messages: list[dict[str, Any]],
    *,
    temperature: float = 0.8,
    max_tokens: int = 1200,
    json_mode: bool = False,
    timeout: float | None = None,
) -> str:
    if spec.is_demo:
        raise ProviderError("demo-provider")
    url = normalize_base_url(spec.base_url) + "/chat/completions"
    payload: dict[str, Any] = {
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    if json_mode:
        payload["response_format"] = {"type": "json_object"}
    is_bigmodel = "bigmodel.cn" in (spec.base_url or "")
    if is_bigmodel:
        # GLM-5.x is an always-thinking model: {"type":"disabled"} is rejected
        # with code 1210. Keep reasoning short, and reserve enough output
        # budget so the final content is not truncated by reasoning tokens.
        payload["thinking"] = {"type": "low"}
        payload["max_tokens"] = max(int(max_tokens), 1600)
    body = _json_body(spec, payload)
    started = time.perf_counter()
    resp = await _post_with_retry(url, _headers(spec), body)
    if resp.status_code == 400 and is_bigmodel:
        body.pop("thinking", None)
        resp = await _post_with_retry(url, _headers(spec), body)
    if resp.status_code == 400 and json_mode:
        body.pop("response_format", None)
        resp = await _post_with_retry(url, _headers(spec), body)
    elapsed = int((time.perf_counter() - started) * 1000)
    if resp.status_code >= 400:
        raise ProviderError(f"HTTP {resp.status_code}: {resp.text[:400]}")
    try:
        data = resp.json()
    except Exception as exc:
        raise ProviderError(f"响应不是合法 JSON: {resp.text[:300]}") from exc
    text = _extract_content(data)
    usage = data.get("usage") or {}
    await write_log(
        "debug",
        "provider",
        f"{spec.name} 对话完成 ({elapsed}ms, {usage.get('total_tokens', '?')} tokens)",
        provider_id=spec.id,
        model=spec.model,
        elapsed_ms=elapsed,
    )
    return text


def _image_reference(image_url: str) -> str:
    """Turn a local /media URL into a data URI so the vision provider can read it."""
    try:
        parsed = urlparse(image_url)
        path = parsed.path or image_url
        if path.startswith("/media/"):
            name = path.split("/media/", 1)[1]
            target = settings.uploads_dir / name
            if target.exists() and target.stat().st_size <= 12 * 1024 * 1024:
                suffix = target.suffix.lower().lstrip(".") or "png"
                mime = "jpeg" if suffix in ("jpg", "jpeg") else suffix
                encoded = base64.b64encode(target.read_bytes()).decode("ascii")
                return f"data:image/{mime};base64,{encoded}"
    except Exception:
        pass
    return image_url


async def vision_describe(spec: ProviderSpec, image_url: str, caption: str = "") -> str:
    """Describe an image with an OpenAI-compatible vision model."""
    if spec.is_demo:
        raise ProviderError("demo-provider")
    prompt = "请仔细描述这张图片里的人物、物体、场景、文字和氛围。"
    if caption:
        prompt += f"发送者附言：{caption}。请结合附言理解图片。"
    prompt += "只输出对图片内容的客观描述，不要编造看不到的细节，不要输出 Markdown。"
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": "你是一个图像理解模型，负责把图片内容准确描述给另一个聊天角色。"},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": _image_reference(image_url)}},
            ],
        },
    ]
    text = await chat(spec, messages, temperature=0.25, max_tokens=500)
    return text.strip()


async def transcribe_audio(spec: ProviderSpec, path, mime: str = "audio/mp4") -> str:
    """Transcribe with either OpenAI /audio/transcriptions or whisper.cpp /inference."""
    if spec.is_demo:
        raise ProviderError("demo-provider")
    base = _stt_base_url(spec.base_url)
    whisper_cpp = base.endswith("/inference")
    url = base if whisper_cpp else base + "/audio/transcriptions"
    headers = {}
    if spec.api_key:
        headers["Authorization"] = f"Bearer {spec.api_key}"
    try:
        data = Path(path).read_bytes()
    except OSError as exc:
        raise ProviderError("语音文件读取失败") from exc
    files = {"file": (Path(path).name, data, mime)}
    if whisper_cpp:
        payload = {"temperature": "0", "response_format": "json",
                   "language": (spec.extra or {}).get("language") or "zh"}
    else:
        model = (spec.extra or {}).get("stt_model") or settings.stt_model
        if "bigmodel.cn" in (spec.base_url or ""):
            model = "glm-asr-2512"
        payload = {"model": model}
    async with httpx.AsyncClient(timeout=settings.ai_request_timeout) as client:
        response = await client.post(url, headers=headers, data=payload, files=files)
        if whisper_cpp and response.status_code in (400, 404, 415, 422, 500, 502, 503, 504, 550):
            # whisper.cpp occasionally returns a transient 550/404 while its
            # temporary upload file is being flushed. Retry once with auto
            # language detection before surfacing the upstream error.
            await asyncio.sleep(0.25)
            retry_payload = dict(payload)
            retry_payload["language"] = "auto"
            response = await client.post(url, headers=headers, data=retry_payload, files=files)
    if response.status_code >= 400:
        raise ProviderError(f"语音转文本失败 HTTP {response.status_code}: {response.text[:300]}")
    try:
        text = response.json().get("text", "")
    except Exception as exc:
        raise ProviderError("语音转文本返回格式错误") from exc
    text = (text or "").strip()
    if not text:
        raise ProviderError("语音转文本结果为空")
    return text


async def vision_ocr(spec: ProviderSpec, image_url: str) -> str:
    """Extract text from a chat screenshot with a vision model."""
    if spec.is_demo:
        raise ProviderError("demo-provider")
    messages: list[dict[str, Any]] = [
        {"role": "system", "content": "你是 OCR 文本提取器。只输出图片里能看清的文字，不要总结、不要解释。"},
        {
            "role": "user",
            "content": [
                {
                    "type": "text",
                    "text": "提取这张聊天截图里的文字，按对话逐行输出，尽量保留说话人和每行内容；不要输出 Markdown 代码块。",
                },
                {"type": "image_url", "image_url": {"url": _image_reference(image_url)}},
            ],
        },
    ]
    text = await chat(spec, messages, temperature=0.1, max_tokens=1600)
    return text.strip()


async def generate_image(spec: ProviderSpec, prompt: str, *, size: str = "1024x1024") -> dict[str, Any]:
    if spec.is_demo:
        return {"url": _demo_image_data_uri(prompt, size), "demo": True}
    url = normalize_base_url(spec.base_url) + "/images/generations"
    body = _json_body(spec, {"prompt": prompt, "n": 1, "size": size})
    resp = await _post_with_retry(url, _headers(spec), body)
    if resp.status_code >= 400:
        raise ProviderError(f"HTTP {resp.status_code}: {resp.text[:400]}")
    data = resp.json()
    items = data.get("data") or []
    if not items:
        raise ProviderError("图像接口没有返回数据")
    item = items[0]
    if item.get("url"):
        return {"url": item["url"], "revised_prompt": item.get("revised_prompt")}
    if item.get("b64_json"):
        return {"url": "data:image/png;base64," + item["b64_json"], "b64": True}
    raise ProviderError("图像接口返回格式无法识别")


def _demo_image_data_uri(prompt: str, size: str) -> str:
    text = (prompt or "NekoMate")[:28]
    w, h = 512, 512
    try:
        w, h = [int(x) for x in size.lower().split("x")]
        w = min(max(w, 256), 1024)
        h = min(max(h, 256), 1024)
    except Exception:
        pass
    safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    svg = f"""<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
<stop offset="0%" stop-color="#5b8cff"/><stop offset="55%" stop-color="#9b5cff"/><stop offset="100%" stop-color="#ff6fae"/>
</linearGradient></defs>
<rect width="100%" height="100%" fill="url(#g)"/>
<circle cx="{w*0.22:.0f}" cy="{h*0.22:.0f}" r="{w*0.18:.0f}" fill="rgba(255,255,255,0.18)"/>
<circle cx="{w*0.82:.0f}" cy="{h*0.78:.0f}" r="{w*0.24:.0f}" fill="rgba(255,255,255,0.12)"/>
<text x="50%" y="48%" text-anchor="middle" fill="#ffffff" font-family="PingFang SC,Microsoft YaHei,sans-serif"
 font-size="{max(18, w//22)}" opacity="0.95">{safe}</text>
<text x="50%" y="57%" text-anchor="middle" fill="rgba(255,255,255,0.75)" font-family="sans-serif"
 font-size="{max(12, w//40)}">NekoMate · demo image</text>
</svg>"""
    return "data:image/svg+xml;base64," + base64.b64encode(svg.encode("utf-8")).decode("ascii")


async def probe(spec: ProviderSpec) -> dict[str, Any]:
    started = time.perf_counter()
    result: dict[str, Any] = {"ok": False, "provider": spec.name, "kind": spec.kind, "model": spec.model}
    try:
        if spec.kind == "stt":
            result.update(await _probe_stt(spec))
        elif spec.kind == "image":
            data = await generate_image(spec, "一只在夜色里发光的猫", size="512x512")
            result.update({"ok": True, "image_url": data.get("url")})
        elif spec.is_demo:
            result.update({"ok": True, "reply": "演示模型可用（不会真正调用外部 API）", "demo": True})
        else:
            reply = await chat(
                spec,
                [
                    {"role": "system", "content": "你是一个连通性测试助手，只回复四个字：连接成功。"},
                    {"role": "user", "content": "ping"},
                ],
                temperature=0.1,
                max_tokens=32,
            )
            result.update({"ok": True, "reply": reply})
    except Exception as exc:
        result.update({"ok": False, "error": str(exc)[:500]})
    result["latency_ms"] = int((time.perf_counter() - started) * 1000)
    return result


async def _probe_stt(spec: ProviderSpec) -> dict[str, Any]:
    import io
    import wave

    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(16000)
        wav.writeframes(b"\x00\x00" * 19200)
    data = buffer.getvalue()
    model = "glm-asr-2512" if "bigmodel.cn" in (spec.base_url or "") else (spec.model or settings.stt_model)
    base = _stt_base_url(spec.base_url)
    whisper_cpp = base.endswith("/inference")
    url = base if whisper_cpp else base + "/audio/transcriptions"
    headers = {}
    if spec.api_key:
        headers["Authorization"] = f"Bearer {spec.api_key}"
    payload = ({"temperature": "0", "response_format": "json",
                "language": (spec.extra or {}).get("language") or "zh"}
               if whisper_cpp else {"model": model})
    async with httpx.AsyncClient(timeout=30) as client:
        response = await client.post(
            url,
            headers=headers,
            data=payload,
            files={"file": ("probe.wav", data, "audio/wav")},
        )
    if response.status_code == 200:
        return {"ok": True, "reply": "语音转写接口可用"}
    if response.status_code in (400, 422):
        return {"ok": True, "reply": "语音转写接口可达（静音探测无转写结果）"}
    return {"ok": False, "error": f"HTTP {response.status_code}: {response.text[:300]}"}


async def analyze_json(spec: ProviderSpec, system: str, user: str) -> dict[str, Any] | None:
    """Ask the analysis model for a JSON object. Returns None when unavailable or unparsable."""
    if spec.is_demo:
        return None
    try:
        text = await chat(
            spec,
            [{"role": "system", "content": system}, {"role": "user", "content": user}],
            temperature=0.2,
            max_tokens=600,
            json_mode=True,
        )
    except Exception as exc:
        await write_log("warning", "analysis", f"逻辑分析模型调用失败: {exc}")
        return None
    candidate = strip_code_fence(text)
    try:
        return json.loads(candidate)
    except Exception:
        start, end = candidate.find("{"), candidate.rfind("}")
        if start >= 0 and end > start:
            try:
                return json.loads(candidate[start : end + 1])
            except Exception:
                return None
    return None
