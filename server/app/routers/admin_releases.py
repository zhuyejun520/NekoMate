from __future__ import annotations

import hashlib
import time
from pathlib import Path

from fastapi import APIRouter, File, Form, HTTPException, Request, UploadFile
from sqlalchemy import select

from ..config import settings
from ..deps import AdminDep, SessionDep
from ..models import AppRelease
from ..serializers import app_release
from ..services.logger import write_log

router = APIRouter(prefix="/api/admin", tags=["管理·客户端更新"])

MAX_APK = 200 * 1024 * 1024


def _releases_dir() -> Path:
    path = settings.data_path / "releases"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _base_url(request: Request) -> str:
    return str(request.base_url).rstrip("/")


@router.get("/releases", summary="客户端版本列表")
async def list_releases(request: Request, admin: AdminDep, session: SessionDep):
    rows = (
        await session.execute(select(AppRelease).order_by(AppRelease.version_code.desc()))
    ).scalars().all()
    base = _base_url(request)
    return {
        "releases": [
            app_release(row, download_url=f"{base}/api/v1/app/download/{row.id}") for row in rows
        ]
    }


@router.post("/releases", summary="上传客户端安装包")
async def upload_release(
    request: Request,
    admin: AdminDep,
    session: SessionDep,
    file: UploadFile = File(...),
    version_code: int = Form(...),
    version_name: str = Form(...),
    changelog: str = Form(default=""),
    force_update: bool = Form(default=False),
    platform: str = Form(default="android"),
):
    if version_code <= 0:
        raise HTTPException(status_code=400, detail="version_code 必须大于 0")
    data = await file.read()
    if not data:
        raise HTTPException(status_code=400, detail="安装包为空")
    if len(data) > MAX_APK:
        raise HTTPException(status_code=400, detail="安装包超过 200MB")
    if not (file.filename or "").lower().endswith(".apk"):
        raise HTTPException(status_code=400, detail="请上传 .apk 文件")

    releases = _releases_dir()
    old = (
        await session.execute(
            select(AppRelease).where(
                AppRelease.platform == platform, AppRelease.version_code == version_code
            )
        )
    ).scalars().first()
    if old is not None:
        try:
            Path(old.file_path).unlink(missing_ok=True)
        except Exception:
            pass
        await session.delete(old)
        await session.commit()

    name = f"{platform}-{version_code}-{int(time.time())}.apk"
    target = releases / name
    target.write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    row = AppRelease(
        platform=platform,
        version_code=version_code,
        version_name=version_name.strip() or str(version_code),
        changelog=changelog.strip(),
        file_path=str(target),
        file_name=file.filename or name,
        file_size=len(data),
        sha256=digest,
        force_update=force_update,
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    await write_log(
        "info",
        "release",
        f"上传客户端版本 {row.version_name} (versionCode {row.version_code})",
        admin_id=admin.id,
    )
    base = _base_url(request)
    return {"release": app_release(row, download_url=f"{base}/api/v1/app/download/{row.id}")}


@router.delete("/releases/{release_id}", summary="删除客户端版本")
async def delete_release(release_id: int, admin: AdminDep, session: SessionDep):
    row = await session.get(AppRelease, release_id)
    if row is None:
        raise HTTPException(status_code=404, detail="版本不存在")
    try:
        Path(row.file_path).unlink(missing_ok=True)
    except Exception:
        pass
    await session.delete(row)
    await session.commit()
    return {"ok": True}
