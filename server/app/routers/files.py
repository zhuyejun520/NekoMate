from __future__ import annotations

import uuid
from pathlib import Path

from fastapi import APIRouter, File, HTTPException, Request, UploadFile

from ..config import settings
from ..deps import UserDep
from ..utils import safe_filename

router = APIRouter(prefix="/api/v1", tags=["文件"])

ALLOWED_SUFFIXES = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".mp3", ".m4a", ".amr", ".ogg", ".mp4", ".wav", ".pdf", ".txt"}


@router.post("/upload", summary="上传文件（头像/图片/语音）")
async def upload(request: Request, user: UserDep, file: UploadFile = File(...)):
    settings.ensure_dirs()
    original = safe_filename(file.filename or "upload")
    suffix = Path(original).suffix.lower()
    if suffix not in ALLOWED_SUFFIXES:
        raise HTTPException(status_code=400, detail=f"不支持的文件类型: {suffix or '未知'}")
    contents = await file.read()
    limit = settings.max_upload_mb * 1024 * 1024
    if len(contents) > limit:
        raise HTTPException(status_code=400, detail=f"文件超过 {settings.max_upload_mb}MB 限制")
    name = f"{uuid.uuid4().hex}{suffix}"
    target = settings.uploads_dir / name
    target.write_bytes(contents)
    base = str(request.base_url).rstrip("/") if request is not None else settings.public_base_url.rstrip("/")
    return {
        "url": f"{base}/media/{name}",
        "path": f"/media/{name}",
        "size": len(contents),
        "filename": original,
    }
