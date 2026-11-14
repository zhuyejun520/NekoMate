from __future__ import annotations

import asyncio
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles

from .ai.proactive import proactive_worker
from .ai.providers import close_client
from .bootstrap import bootstrap
from .config import settings
from .database import dispose_engine, init_db, init_engine
from .routers import (
    admin,
    admin_knowledge,
    admin_providers,
    admin_releases,
    admin_tasks,
    ai,
    app_releases,
    auth,
    chat,
    files,
    friends,
    meta,
    moments,
    speech,
    store,
    ws,
)
from .services.logger import write_log
from .services.plugin_loader import plugin_manager
from .services.push import manager as ws_manager
from .services.scheduler import scheduler


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings.ensure_dirs()
    await init_engine()
    await init_db()
    await bootstrap()
    try:
        await plugin_manager.load_all()
    except Exception as exc:
        await write_log("error", "plugin", f"插件系统初始化失败: {exc}")
    scheduler.start()
    stop_event = asyncio.Event()
    proactive_task = asyncio.create_task(proactive_worker(stop_event), name="proactive-worker")
    await write_log(
        "info",
        "system",
        f"服务就绪：管理面板 {settings.public_base_url}/admin ，API 文档 /docs",
    )
    try:
        yield
    finally:
        stop_event.set()
        proactive_task.cancel()
        try:
            await proactive_task
        except (asyncio.CancelledError, Exception):
            pass
        await scheduler.stop()
        await ws_manager.close_all()
        await close_client()
        await dispose_engine()


app = FastAPI(
    title=f"{settings.app_title} API",
    description=(
        "NekoMate AI 伴侣服务端：账号、好友、群聊、AI 好友、知识库风格学习、记忆、主动消息、"
        "App 使用事件（root）、AstrBot 兼容插件、未来任务。"
    ),
    version=settings.version,
    lifespan=lifespan,
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def access_log(request: Request, call_next):
    started = time.perf_counter()
    try:
        response = await call_next(request)
    except Exception as exc:  # pragma: no cover - defensive
        await write_log(
            "error",
            "api",
            f"{request.method} {request.url.path} 未处理异常: {type(exc).__name__}: {exc}",
        )
        return JSONResponse({"detail": "服务器内部错误"}, status_code=500)
    elapsed = int((time.perf_counter() - started) * 1000)
    path = request.url.path
    if path.startswith("/api") and not path.startswith("/api/v1/ws"):
        is_admin_write = path.startswith("/api/admin") and request.method not in ("GET", "HEAD", "OPTIONS")
        if response.status_code >= 500:
            level = "error"
        elif response.status_code >= 400:
            level = "warning"
        elif is_admin_write:
            level = "info"
        else:
            level = None
        if level:
            await write_log(
                level,
                "api",
                f"{request.method} {path} → {response.status_code} ({elapsed}ms)",
                status=response.status_code,
                elapsed_ms=elapsed,
            )
    return response


for r in (
    meta.router,
    auth.router,
    friends.router,
    chat.router,
    ai.router,
    moments.router,
    files.router,
    ws.router,
    speech.router,
    store.router,
    app_releases.router,
    admin.router,
    admin_knowledge.router,
    admin_providers.router,
    admin_releases.router,
    admin_tasks.router,
):
    app.include_router(r)


@app.get("/", include_in_schema=False)
async def root():
    return RedirectResponse(url="/admin")


@app.get("/favicon.ico", include_in_schema=False)
async def favicon():
    icon = settings.static_dir / "admin" / "favicon.svg"
    if icon.exists():
        return FileResponse(icon)
    return JSONResponse({"ok": False}, status_code=404)


# 静态资源：管理面板 + 上传文件
admin_dir = Path(settings.static_dir) / "admin"
if admin_dir.exists():
    app.mount("/admin", StaticFiles(directory=str(admin_dir), html=True), name="admin")
settings.ensure_dirs()
app.mount("/media", StaticFiles(directory=str(settings.uploads_dir)), name="media")
