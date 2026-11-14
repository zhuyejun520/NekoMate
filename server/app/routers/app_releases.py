from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse
from sqlalchemy import select

from ..config import settings
from ..deps import SessionDep
from ..models import AppRelease
from ..serializers import app_release

router = APIRouter(prefix="/api/v1/app", tags=["客户端更新"])


def _base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


@router.get("/version", summary="检查客户端最新版本")
async def check_version(request: Request, session: SessionDep, platform: str = "android", version_code: int = 0):
    row = (
        await session.execute(
            select(AppRelease)
            .where(AppRelease.platform == platform)
            .order_by(AppRelease.version_code.desc())
            .limit(1)
        )
    ).scalars().first()
    if row is None:
        return {"platform": platform, "current_version_code": version_code, "update": False, "latest": None}
    latest = app_release(row, download_url=f"{_base_url(request)}/api/v1/app/download/{row.id}")
    return {
        "platform": platform,
        "current_version_code": version_code,
        "update": row.version_code > version_code,
        "latest": latest,
    }


@router.get("/download/{release_id}", summary="下载客户端安装包")
async def download(release_id: int, session: SessionDep):
    row = await session.get(AppRelease, release_id)
    if row is None:
        raise HTTPException(status_code=404, detail="版本不存在")
    path = Path(row.file_path)
    if not path.exists():
        raise HTTPException(status_code=404, detail="安装包文件已丢失")
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename=row.file_name or "app.apk",
    )
