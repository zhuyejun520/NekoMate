from __future__ import annotations

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from ..ai.providers import ProviderError, pick_stt_provider, transcribe_audio
from ..config import settings
from ..deps import SessionDep, UserDep
from ..services.media import media_name

router = APIRouter(prefix="/api/v1/speech", tags=["语音"])


class TranscribeIn(BaseModel):
    audio_url: str


@router.post("/transcribe", summary="语音转文本")
async def transcribe(payload: TranscribeIn, user: UserDep, session: SessionDep):
    name = media_name(payload.audio_url)
    if not name:
        raise HTTPException(status_code=400, detail="语音地址无效")
    path = (settings.uploads_dir / name).resolve()
    if path.parent != settings.uploads_dir.resolve() or not path.is_file():
        raise HTTPException(status_code=404, detail="语音文件不存在")
    provider = await pick_stt_provider(session, user_id=user.id)
    mime = "audio/wav" if path.suffix.lower() == ".wav" else "audio/mp4"
    try:
        text = await transcribe_audio(provider, path, mime)
    except ProviderError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"语音转文本失败：{exc}") from exc
    return {"text": text}
