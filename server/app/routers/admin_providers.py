from __future__ import annotations

import io
import shutil
import zipfile
from pathlib import Path
from typing import Any

from fastapi import APIRouter, File, HTTPException, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import select

from ..ai import providers as provider_api
from ..deps import AdminDep, SessionDep
from ..models import ModelProvider, PluginRecord
from ..serializers import plugin_record, provider
from ..services.logger import write_log
from ..services.plugin_loader import plugin_manager
from ..utils import utcnow

router = APIRouter(prefix="/api/admin", tags=["管理·模型与插件"])

PROVIDER_PRESETS = [
    {"label": "DeepSeek", "kind": "chat", "base_url": "https://api.deepseek.com/v1", "model": "deepseek-chat"},
    {"label": "DeepSeek 推理", "kind": "chat", "base_url": "https://api.deepseek.com/v1", "model": "deepseek-reasoner"},
    {"label": "智谱 GLM-4-Flash（快且便宜）", "kind": "chat", "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4-flash"},
    {"label": "智谱 GLM-4-Plus", "kind": "chat", "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4-plus"},
    {"label": "通义千问 Qwen-Plus", "kind": "chat", "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1", "model": "qwen-plus"},
    {"label": "Moonshot Kimi", "kind": "chat", "base_url": "https://api.moonshot.cn/v1", "model": "moonshot-v1-8k"},
    {"label": "SiliconFlow Qwen2.5-7B", "kind": "chat", "base_url": "https://api.siliconflow.cn/v1", "model": "Qwen/Qwen2.5-7B-Instruct"},
    {"label": "OpenAI GPT-4o mini", "kind": "chat", "base_url": "https://api.openai.com/v1", "model": "gpt-4o-mini"},
    {"label": "本地 Ollama", "kind": "chat", "base_url": "http://127.0.0.1:11434/v1", "model": "qwen2.5:7b"},
    {"label": "智谱 CogView-3-Flash（图像）", "kind": "image", "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "cogview-3-flash"},
    {"label": "OpenAI DALL·E 3（图像）", "kind": "image", "base_url": "https://api.openai.com/v1", "model": "dall-e-3"},
    {"label": "SiliconFlow 图像", "kind": "image", "base_url": "https://api.siliconflow.cn/v1", "model": "Kwai-Kolors/Kolors"},
    {"label": "逻辑分析用轻量模型", "kind": "analysis", "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4-flash"},
    {"label": "OpenAI GPT-4o mini（视觉）", "kind": "vision", "base_url": "https://api.openai.com/v1", "model": "gpt-4o-mini"},
    {"label": "智谱 GLM-4V-Flash（视觉）", "kind": "vision", "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-4v-flash"},
    {"label": "智谱 GLM-ASR-2512（语音转写）", "kind": "stt", "base_url": "https://open.bigmodel.cn/api/paas/v4", "model": "glm-asr-2512"},
    {"label": "OpenAI Whisper（语音转写）", "kind": "stt", "base_url": "https://api.openai.com/v1", "model": "whisper-1"},
    {"label": "本地 whisper.cpp（/inference）", "kind": "stt", "base_url": "http://127.0.0.1:8080/inference", "model": "tiny"},
]


class ProviderIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    kind: str = "chat"
    base_url: str = Field(min_length=1)
    api_key: str | None = ""
    model: str = ""
    enabled: bool = True
    priority: int = 10
    owner_user_id: int | None = None
    extra: dict[str, Any] = Field(default_factory=dict)


class ProviderTestIn(BaseModel):
    name: str = "临时测试"
    kind: str = "chat"
    base_url: str
    api_key: str | None = ""
    model: str = ""
    extra: dict[str, Any] = Field(default_factory=dict)


class PluginConfigIn(BaseModel):
    config: dict[str, Any]


# ------------------------------------------------------------------ providers
@router.get("/providers", summary="模型提供商列表")
async def list_providers(admin: AdminDep, session: SessionDep, owner_user_id: int | None = None):
    stmt = select(ModelProvider).order_by(ModelProvider.kind.asc(), ModelProvider.priority.asc())
    if owner_user_id is not None:
        stmt = stmt.where(ModelProvider.owner_user_id == owner_user_id)
    rows = (await session.execute(stmt)).scalars().all()
    used = {p.kind for p in rows if p.enabled and p.owner_user_id is None}
    owners = {}
    owner_ids = {p.owner_user_id for p in rows if p.owner_user_id}
    if owner_ids:
        from ..models import User

        owner_rows = (await session.execute(select(User).where(User.id.in_(owner_ids)))).scalars().all()
        owners = {u.id: (u.nickname or u.username) for u in owner_rows}
    items = []
    for p in rows:
        data = provider(p)
        if p.owner_user_id:
            data["owner_name"] = owners.get(p.owner_user_id, f"用户 {p.owner_user_id}")
        items.append(data)
    return {
        "providers": items,
        "presets": PROVIDER_PRESETS,
        "roles": {
            "chat": {"configured": "chat" in used, "label": "对话模型"},
            "image": {"configured": "image" in used, "label": "图像模型"},
            "analysis": {"configured": "analysis" in used or "chat" in used, "label": "知识库/记忆分析模型"},
            "vision": {"configured": "vision" in used, "label": "图片识别模型"},
            "stt": {"configured": "stt" in used, "label": "语音转写模型"},
        },
    }


@router.post("/providers", summary="新增模型提供商")
async def create_provider(payload: ProviderIn, admin: AdminDep, session: SessionDep):
    if payload.kind not in ("chat", "image", "analysis", "vision", "stt"):
        raise HTTPException(status_code=400, detail="类型必须是 chat / image / analysis / vision / stt")
    if payload.owner_user_id is not None:
        from ..models import User

        owner = await session.get(User, payload.owner_user_id)
        if owner is None:
            raise HTTPException(status_code=404, detail="专属用户不存在")
    row = ModelProvider(
        name=payload.name.strip(),
        kind=payload.kind,
        base_url=provider_api.normalize_base_url(payload.base_url),
        api_key=(payload.api_key or "").strip(),
        model=payload.model.strip(),
        enabled=payload.enabled,
        priority=payload.priority,
        owner_user_id=payload.owner_user_id,
        extra=payload.extra or {},
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    await write_log(
        "info",
        "provider",
        f"新增模型提供商「{row.name}」({row.kind}/{row.model}"
        + (f"，专属用户 {row.owner_user_id}" if row.owner_user_id else "")
        + ")",
        admin_id=admin.id,
    )
    return {"id": row.id, "provider": provider(row)}


@router.put("/providers/{provider_id}", summary="修改模型提供商")
async def update_provider(provider_id: int, payload: ProviderIn, admin: AdminDep, session: SessionDep):
    row = await session.get(ModelProvider, provider_id)
    if row is None:
        raise HTTPException(status_code=404, detail="提供商不存在")
    row.name = payload.name.strip()
    row.kind = payload.kind if payload.kind in ("chat", "image", "analysis", "vision", "stt") else row.kind
    row.base_url = provider_api.normalize_base_url(payload.base_url)
    if payload.api_key:
        row.api_key = payload.api_key.strip()
    row.model = payload.model.strip()
    row.enabled = payload.enabled
    row.priority = payload.priority
    row.owner_user_id = payload.owner_user_id
    row.extra = payload.extra or {}
    await session.commit()
    return {"provider": provider(row)}


@router.delete("/providers/{provider_id}", summary="删除模型提供商")
async def delete_provider(provider_id: int, admin: AdminDep, session: SessionDep):
    row = await session.get(ModelProvider, provider_id)
    if row is None:
        raise HTTPException(status_code=404, detail="提供商不存在")
    await session.delete(row)
    await session.commit()
    return {"ok": True}


@router.post("/providers/{provider_id}/test", summary="测试提供商连通性")
async def test_provider(provider_id: int, admin: AdminDep, session: SessionDep):
    row = await session.get(ModelProvider, provider_id)
    if row is None:
        raise HTTPException(status_code=404, detail="提供商不存在")
    spec = provider_api.spec_from_row(row)
    result = await provider_api.probe(spec)
    row.status = "ok" if result.get("ok") else "error"
    row.last_error = None if result.get("ok") else str(result.get("error"))[:500]
    row.last_checked_at = utcnow()
    await session.commit()
    return {"result": result, "provider": provider(row)}


@router.post("/providers/test", summary="测试未保存的提供商配置")
async def test_provider_adhoc(payload: ProviderTestIn, admin: AdminDep):
    spec = provider_api.ProviderSpec(
        id=None,
        name=payload.name,
        kind=payload.kind,
        base_url=payload.base_url,
        api_key=payload.api_key or "",
        model=payload.model,
        extra=payload.extra or {},
    )
    return {"result": await provider_api.probe(spec)}


# -------------------------------------------------------------------- plugins
@router.get("/plugins", summary="插件列表")
async def list_plugins(admin: AdminDep, session: SessionDep):
    rows = (await session.execute(select(PluginRecord).order_by(PluginRecord.id.asc()))).scalars().all()
    runtime = {slug: rt for slug, rt in plugin_manager.plugins.items()}
    items = []
    for row in rows:
        data = plugin_record(row)
        rt = runtime.get(row.slug)
        data["runtime_loaded"] = bool(rt and rt.status == "loaded")
        data["runtime_error"] = rt.error if rt else None
        items.append(data)
    return {
        "plugins": items,
        "builtin": ["hello_world", "neko_tools"],
        "compat": "支持 AstrBot 插件（from astrbot.api... / @register / @filter.command / Star 基类 / message_components）",
    }


@router.post("/plugins/upload", summary="上传插件 zip")
async def upload_plugin(admin: AdminDep, session: SessionDep, file: UploadFile = File(...)):
    from ..config import settings

    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="文件为空")
    if len(data) > 64 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="插件包过大（上限 64MB）")
    settings.ensure_dirs()
    try:
        archive = zipfile.ZipFile(io.BytesIO(data))
    except zipfile.BadZipFile:
        raise HTTPException(status_code=400, detail="不是合法的 zip 文件")
    names = [info.filename for info in archive.infolist()]
    roots = {Path(n).parts[0] for n in names if n and not n.startswith("__MACOSX")}
    has_root_dir = all(len(Path(n).parts) > 1 for n in names if n and not n.startswith("__MACOSX"))
    slug = (list(roots)[0] if has_root_dir and len(roots) == 1 else Path(file.filename or "plugin").stem)
    slug = "".join(ch for ch in slug if ch.isalnum() or ch in "._-")[:64] or "plugin"
    target = settings.plugins_dir / slug
    if target.exists():
        raise HTTPException(status_code=409, detail=f"插件目录 {slug} 已存在，请先删除旧版本")
    target.mkdir(parents=True, exist_ok=True)
    try:
        for info in archive.infolist():
            member = info.filename
            if member.startswith("__MACOSX"):
                continue
            path = Path(member)
            if path.is_absolute() or ".." in path.parts:
                raise HTTPException(status_code=400, detail="插件包包含非法路径")
            dest = target.joinpath(*path.parts[1:] if has_root_dir else path.parts)
            if info.is_dir():
                dest.mkdir(parents=True, exist_ok=True)
            else:
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(archive.read(info))
    finally:
        archive.close()
    rt = await plugin_manager.load_dir(target)
    if rt and rt.status == "failed":
        await write_log("warning", "plugin", f"插件 {slug} 已解压但加载失败: {rt.error}", admin_id=admin.id)
    await write_log("info", "plugin", f"上传插件 {slug}", admin_id=admin.id)
    return {"ok": True, "slug": slug, "status": rt.status if rt else "unknown", "error": rt.error if rt else None}


@router.post("/plugins/reload", summary="重新加载全部插件")
async def reload_plugins(admin: AdminDep):
    await plugin_manager.reload()
    return {"ok": True, "plugins": len(plugin_manager.plugins)}


@router.get("/plugins/{slug}", summary="插件详情")
async def plugin_detail(slug: str, admin: AdminDep, session: SessionDep):
    row = (await session.execute(select(PluginRecord).where(PluginRecord.slug == slug))).scalars().first()
    if row is None:
        raise HTTPException(status_code=404, detail="插件不存在")
    data = plugin_record(row)
    rt = plugin_manager.plugins.get(slug)
    data["runtime_loaded"] = bool(rt and rt.status == "loaded")
    main_file = None
    for candidate in ("main.py", "plugin.py"):
        p = Path(row.path) / candidate
        if p.exists():
            main_file = p
            break
    if main_file:
        try:
            data["source"] = main_file.read_text(encoding="utf-8")[:20000]
        except Exception:
            data["source"] = None
    return {"plugin": data}


@router.post("/plugins/{slug}/enable", summary="启用插件")
async def enable_plugin(slug: str, admin: AdminDep):
    ok = await plugin_manager.set_enabled(slug, True)
    if not ok:
        raise HTTPException(status_code=404, detail="插件不存在")
    return {"ok": True}


@router.post("/plugins/{slug}/disable", summary="停用插件")
async def disable_plugin(slug: str, admin: AdminDep):
    ok = await plugin_manager.set_enabled(slug, False)
    if not ok:
        raise HTTPException(status_code=404, detail="插件不存在")
    return {"ok": True}


@router.put("/plugins/{slug}/config", summary="保存插件配置")
async def save_plugin_config(slug: str, payload: PluginConfigIn, admin: AdminDep):
    ok = await plugin_manager.update_config(slug, payload.config)
    if not ok:
        raise HTTPException(status_code=404, detail="插件不存在")
    return {"ok": True, "config": plugin_manager.plugins[slug].config}


@router.delete("/plugins/{slug}", summary="删除插件")
async def delete_plugin(slug: str, admin: AdminDep):
    ok = await plugin_manager.delete(slug)
    if not ok:
        raise HTTPException(status_code=404, detail="插件不存在")
    return {"ok": True}
