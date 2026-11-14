from __future__ import annotations

from typing import Any

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from ..deps import AdminDep, SessionDep
from ..models import KnowledgeBase, KnowledgeChunk, KnowledgeDocument, PersonaKnowledge
from ..serializers import knowledge_base as serialize_kb
from ..serializers import knowledge_chunk, knowledge_doc
from ..services.knowledge import decode_bytes, delete_document, import_chat_text, kb_counters, preview_chat_text, rebuild_style_from_documents
from ..services.logger import write_log

router = APIRouter(prefix="/api/admin/knowledge", tags=["管理·知识库"])

MAX_TEXT = 8_000_000


class KnowledgeIn(BaseModel):
    name: str = Field(min_length=1, max_length=64)
    description: str | None = None
    kind: str = "style"
    enabled: bool = True


class ImportTextIn(BaseModel):
    text: str
    title: str = "粘贴导入"
    target_speaker: str | None = None
    source_type: str | None = None


class PreviewIn(BaseModel):
    text: str
    filename: str = ""
    target_speaker: str | None = None


async def _get_kb(session, kb_id: int) -> KnowledgeBase:
    kb = await session.get(KnowledgeBase, kb_id)
    if kb is None:
        raise HTTPException(status_code=404, detail="知识库不存在")
    return kb


async def _kb_view(session, kb: KnowledgeBase) -> dict[str, Any]:
    docs, chunks = await kb_counters(session, kb.id)
    persona_ids = (
        await session.execute(select(PersonaKnowledge.persona_id).where(PersonaKnowledge.kb_id == kb.id))
    ).scalars().all()
    return serialize_kb(kb, docs=docs, chunks=chunks, personas=list(persona_ids))


@router.get("", summary="知识库列表")
async def list_knowledge(admin: AdminDep, session: SessionDep):
    rows = (await session.execute(select(KnowledgeBase).order_by(KnowledgeBase.id.desc()))).scalars().all()
    return {"knowledge_bases": [await _kb_view(session, kb) for kb in rows]}


@router.post("", summary="新建知识库")
async def create_knowledge(payload: KnowledgeIn, admin: AdminDep, session: SessionDep):
    kb = KnowledgeBase(
        name=payload.name.strip(),
        description=payload.description,
        kind=payload.kind if payload.kind in ("style", "fact") else "style",
        enabled=payload.enabled,
        owner_id=admin.id,
        style_profile={},
        stats={},
    )
    session.add(kb)
    await session.commit()
    await session.refresh(kb)
    await write_log("info", "knowledge", f"创建知识库「{kb.name}」", admin_id=admin.id)
    return {"id": kb.id, "knowledge_base": await _kb_view(session, kb)}


@router.get("/{kb_id}", summary="知识库详情")
async def get_knowledge(kb_id: int, admin: AdminDep, session: SessionDep):
    kb = await _get_kb(session, kb_id)
    docs = (
        await session.execute(
            select(KnowledgeDocument).where(KnowledgeDocument.kb_id == kb_id).order_by(KnowledgeDocument.id.desc())
        )
    ).scalars().all()
    data = await _kb_view(session, kb)
    data["documents"] = [knowledge_doc(d) for d in docs]
    return {"knowledge_base": data}


@router.put("/{kb_id}", summary="修改知识库")
async def update_knowledge(kb_id: int, payload: KnowledgeIn, admin: AdminDep, session: SessionDep):
    kb = await _get_kb(session, kb_id)
    old_kind = kb.kind
    kb.name = payload.name.strip()
    kb.description = payload.description
    kb.kind = payload.kind if payload.kind in ("style", "fact") else kb.kind
    kb.enabled = payload.enabled
    await session.commit()
    rebuilt = False
    if kb.kind == "style" and (old_kind != "style" or not (kb.style_profile or {})):
        rebuilt = await rebuild_style_from_documents(session, kb)
        await session.refresh(kb)
    data = await _kb_view(session, kb)
    data["rebuilt"] = rebuilt
    return {"knowledge_base": data}


@router.delete("/{kb_id}", summary="删除知识库")
async def remove_knowledge(kb_id: int, admin: AdminDep, session: SessionDep):
    kb = await _get_kb(session, kb_id)
    await session.execute(KnowledgeChunk.__table__.delete().where(KnowledgeChunk.kb_id == kb_id))
    await session.execute(KnowledgeDocument.__table__.delete().where(KnowledgeDocument.kb_id == kb_id))
    await session.execute(PersonaKnowledge.__table__.delete().where(PersonaKnowledge.kb_id == kb_id))
    await session.delete(kb)
    await session.commit()
    await write_log("info", "knowledge", f"删除知识库「{kb.name}」", admin_id=admin.id)
    return {"ok": True}


@router.post("/{kb_id}/import", summary="导入聊天记录文件（微信/QQ/JSON/CSV/HTML）")
async def import_file(
    kb_id: int,
    admin: AdminDep,
    session: SessionDep,
    file: UploadFile = File(...),
    target_speaker: str | None = Form(default=None),
    source_type: str | None = Form(default=None),
):
    kb = await _get_kb(session, kb_id)
    raw = await file.read()
    if len(raw) > MAX_TEXT:
        raise HTTPException(status_code=400, detail="文件过大（上限约 8MB 文本）")
    text = decode_bytes(raw)
    if not text.strip():
        raise HTTPException(status_code=400, detail="文件内容为空或编码无法识别")
    try:
        result = await import_chat_text(
            session,
            kb,
            title=file.filename or "聊天记录",
            raw_text=text,
            target_speaker=target_speaker,
            source_type=source_type,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    await write_log(
        "info",
        "knowledge",
        f"导入聊天记录到「{kb.name}」：{file.filename}，{result['messages']} 条消息，{result['samples']} 条风格样本",
        admin_id=admin.id,
    )
    return {"ok": True, "result": result, "knowledge_base": await _kb_view(session, kb)}


@router.post("/{kb_id}/import-text", summary="粘贴文本导入")
async def import_text(kb_id: int, payload: ImportTextIn, admin: AdminDep, session: SessionDep):
    kb = await _get_kb(session, kb_id)
    if len(payload.text) > MAX_TEXT:
        raise HTTPException(status_code=400, detail="文本过长")
    try:
        result = await import_chat_text(
            session,
            kb,
            title=payload.title,
            raw_text=payload.text,
            target_speaker=payload.target_speaker,
            source_type=payload.source_type,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    return {"ok": True, "result": result, "knowledge_base": await _kb_view(session, kb)}


@router.post("/{kb_id}/preview", summary="预览解析结果（不落库）")
async def preview_file(
    kb_id: int,
    admin: AdminDep,
    file: UploadFile = File(...),
    target_speaker: str | None = Form(default=None),
):
    await _get_kb(session, kb_id)
    raw = await file.read()
    if len(raw) > MAX_TEXT:
        raise HTTPException(status_code=400, detail="文件过大")
    text = decode_bytes(raw)
    result = await preview_chat_text(text, filename=file.filename or "", target_speaker=target_speaker)
    return {"preview": result}


@router.post("/preview-text", summary="预览粘贴文本")
async def preview_text(payload: PreviewIn, admin: AdminDep):
    return {"preview": await preview_chat_text(payload.text, filename=payload.filename, target_speaker=payload.target_speaker)}


@router.get("/{kb_id}/documents", summary="文档列表")
async def list_documents(kb_id: int, admin: AdminDep, session: SessionDep):
    await _get_kb(session, kb_id)
    rows = (
        await session.execute(
            select(KnowledgeDocument)
            .where(KnowledgeDocument.kb_id == kb_id)
            .order_by(KnowledgeDocument.id.desc())
        )
    ).scalars().all()
    return {"documents": [knowledge_doc(d) for d in rows]}


@router.delete("/documents/{doc_id}", summary="删除文档")
async def remove_document(doc_id: int, admin: AdminDep, session: SessionDep):
    doc = await session.get(KnowledgeDocument, doc_id)
    if doc is None:
        raise HTTPException(status_code=404, detail="文档不存在")
    kb = await session.get(KnowledgeBase, doc.kb_id)
    await delete_document(session, doc)
    if kb:
        kb.stats = {**(kb.stats or {}), "documents": max(0, int((kb.stats or {}).get("documents", 1)) - 1)}
        await session.commit()
    return {"ok": True}


@router.get("/{kb_id}/chunks", summary="风格样本预览")
async def list_chunks(kb_id: int, admin: AdminDep, session: SessionDep, limit: int = 30, offset: int = 0):
    await _get_kb(session, kb_id)
    total = (
        await session.execute(select(func.count(KnowledgeChunk.id)).where(KnowledgeChunk.kb_id == kb_id))
    ).scalar() or 0
    rows = (
        await session.execute(
            select(KnowledgeChunk)
            .where(KnowledgeChunk.kb_id == kb_id)
            .order_by(KnowledgeChunk.quality.desc(), KnowledgeChunk.id.asc())
            .offset(max(0, offset))
            .limit(max(1, min(limit, 200)))
        )
    ).scalars().all()
    return {"chunks": [knowledge_chunk(c) for c in rows], "total": int(total)}
