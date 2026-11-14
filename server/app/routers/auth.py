from __future__ import annotations

import re

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel, Field
from sqlalchemy import func, select

from ..ai.engine import CompanionEngine
from ..bootstrap import create_default_ai_friend
from ..config import settings
from ..deps import SessionDep, UserDep
from ..models import AiFriend, FriendRequest, Friendship, Persona, User
from ..security import create_token, hash_password, verify_password
from ..serializers import ai_friend as serialize_ai_friend
from ..serializers import user_full
from ..services.logger import write_log
from ..services.settings_service import get_settings_map
from ..utils import utcnow

router = APIRouter(prefix="/api/v1/auth", tags=["认证"])
engine = CompanionEngine()

USERNAME_RE = re.compile(r"^[A-Za-z0-9_]{3,32}$")
_failed_logins: dict[str, list[float]] = {}


class RegisterIn(BaseModel):
    username: str = Field(min_length=3, max_length=32)
    password: str = Field(min_length=6, max_length=72)
    nickname: str | None = Field(default=None, max_length=32)


class LoginIn(BaseModel):
    username: str
    password: str


class ProfileIn(BaseModel):
    nickname: str | None = Field(default=None, max_length=32)
    avatar: str | None = None
    bio: str | None = Field(default=None, max_length=255)
    city: str | None = Field(default=None, max_length=64)
    gender: str | None = Field(default=None, max_length=16)
    tags: list[str] | None = None
    avatar_frame: str | None = Field(default=None, max_length=4000)


class ProfileReviewIn(BaseModel):
    field: str = "bio"
    text: str = Field(min_length=1, max_length=500)


class PasswordIn(BaseModel):
    old_password: str
    new_password: str = Field(min_length=6, max_length=72)


@router.post("/register", summary="注册账号")
async def register(payload: RegisterIn, session: SessionDep, request: Request):
    app_settings = await get_settings_map(session)
    if not app_settings.get("registration_enabled", True) or not settings.registration_enabled:
        raise HTTPException(status_code=403, detail="当前未开放注册，请联系管理员")
    username = payload.username.strip()
    if not USERNAME_RE.match(username):
        raise HTTPException(status_code=400, detail="用户名只能包含字母、数字、下划线，长度 3-32")
    exists = (await session.execute(select(User).where(User.username == username))).scalars().first()
    if exists:
        raise HTTPException(status_code=409, detail="用户名已被占用")

    user = User(
        username=username,
        password_hash=hash_password(payload.password),
        nickname=(payload.nickname or username).strip()[:32],
        role="user",
        status="active",
    )
    session.add(user)
    await session.commit()
    await session.refresh(user)

    ai = await create_default_ai_friend(session, user)
    if ai is not None:
        try:
            await engine.create_greeting(session, ai, user, app_settings)
        except Exception as exc:  # greeting is best effort
            await write_log("warning", "auth", f"欢迎语生成失败: {exc}", user_id=user.id)

    token = create_token(user.id, user.role)
    await write_log("info", "auth", f"新用户注册: {username}", user_id=user.id, ip=request.client.host if request.client else "")
    data = {"token": token, "user": user_full(user)}
    if ai is not None:
        persona = await session.get(Persona, ai.persona_id)
        conv = await engine.ensure_conversation(session, ai, user)
        data["ai_friend"] = serialize_ai_friend(ai, persona_obj=persona, conversation_id=conv.id)
    return data


@router.post("/login", summary="登录")
async def login(payload: LoginIn, session: SessionDep, request: Request):
    key = f"{payload.username}:{request.client.host if request.client else '-'}"
    now_ts = utcnow().timestamp()
    attempts = [t for t in _failed_logins.get(key, []) if now_ts - t < 600]
    if len(attempts) >= 8:
        raise HTTPException(status_code=429, detail="尝试次数过多，请 10 分钟后再试")
    user = (
        await session.execute(select(User).where(User.username == payload.username.strip()))
    ).scalars().first()
    if user is None or not verify_password(payload.password, user.password_hash):
        attempts.append(now_ts)
        _failed_logins[key] = attempts
        raise HTTPException(status_code=401, detail="用户名或密码错误")
    if user.status != "active":
        raise HTTPException(status_code=403, detail="账号已被禁用")
    _failed_logins.pop(key, None)
    user.last_seen_at = utcnow()
    await session.commit()
    token = create_token(user.id, user.role)
    await write_log("info", "auth", f"用户登录: {user.username}", user_id=user.id)
    return {"token": token, "user": user_full(user)}


@router.get("/me", summary="当前账号信息")
async def me(user: UserDep, session: SessionDep):
    friend_count = (
        await session.execute(select(func.count(Friendship.id)).where(Friendship.user_id == user.id))
    ).scalar() or 0
    pending = (
        await session.execute(
            select(func.count(FriendRequest.id)).where(FriendRequest.to_user_id == user.id, FriendRequest.status == "pending")
        )
    ).scalar() or 0
    ai_count = (
        await session.execute(select(func.count(AiFriend.id)).where(AiFriend.user_id == user.id))
    ).scalar() or 0
    return {
        "user": user_full(user),
        "stats": {"friends": friend_count, "pending_requests": pending, "ai_friends": ai_count},
    }


@router.put("/profile", summary="修改资料")
async def update_profile(payload: ProfileIn, user: UserDep, session: SessionDep):
    if payload.nickname is not None:
        user.nickname = payload.nickname.strip()[:32] or user.nickname
    if payload.avatar is not None:
        user.avatar = payload.avatar
    if payload.bio is not None:
        user.bio = payload.bio
    if payload.city is not None:
        user.city = payload.city.strip()[:64]
    extra = dict(user.extra or {})
    if payload.gender is not None:
        gender = payload.gender.strip().lower()
        extra["gender"] = gender if gender in ("male", "female", "secret") else "secret"
    if payload.tags is not None:
        clean: list[str] = []
        for tag in payload.tags[:8]:
            value = str(tag).strip()[:16]
            if value and value not in clean:
                clean.append(value)
        extra["tags"] = clean
    if payload.avatar_frame is not None:
        frame = payload.avatar_frame.strip()
        if frame and frame != "{}":
            extra["avatar_frame"] = frame[:4000]
        else:
            extra.pop("avatar_frame", None)
    user.extra = extra
    await session.commit()
    return {"user": user_full(user)}


@router.post("/profile/review", summary="个人简介/标签直接通过")
async def review_profile(payload: ProfileReviewIn, user: UserDep, session: SessionDep):
    # Endpoint kept for old clients; profile text no longer uses AI moderation.
    return {"ok": True, "reason": "", "suggestion": payload.text}


@router.put("/password", summary="修改密码")
async def change_password(payload: PasswordIn, user: UserDep, session: SessionDep):
    if not verify_password(payload.old_password, user.password_hash):
        raise HTTPException(status_code=400, detail="原密码不正确")
    user.password_hash = hash_password(payload.new_password)
    user.must_change_password = False
    await session.commit()
    await write_log("info", "auth", f"用户修改密码: {user.username}", user_id=user.id)
    return {"ok": True}
