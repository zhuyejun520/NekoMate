from __future__ import annotations

from fastapi import APIRouter

from ..config import settings
from ..database import SessionLocal
from ..utils import utcnow

router = APIRouter(prefix="/api/v1", tags=["系统"])
STARTED_AT = utcnow()


@router.get("/health", summary="健康检查")
async def health():
    db_ok = False
    try:
        from sqlalchemy import text

        if SessionLocal is not None:
            async with SessionLocal() as session:
                await session.execute(text("SELECT 1"))
                db_ok = True
    except Exception:
        db_ok = False
    return {
        "ok": True,
        "app": settings.app_name,
        "version": settings.version,
        "db": db_ok,
        "uptime_seconds": int((utcnow() - STARTED_AT).total_seconds()),
        "time": utcnow().isoformat(timespec="seconds"),
    }


@router.get("/meta", summary="服务端元信息")
async def meta():
    return {
        "app": settings.app_name,
        "title": settings.app_title,
        "version": settings.version,
        "features": [
            "账号注册登录",
            "好友与群聊",
            "AI 好友",
            "知识库风格学习",
            "角色卡",
            "模型提供商",
            "记忆系统",
            "主动消息",
            "应用白名单（root）",
            "AstrBot 插件",
            "未来任务",
            "日常空间",
        ],
        "admin_url": "/admin",
        "docs_url": "/docs",
    }
