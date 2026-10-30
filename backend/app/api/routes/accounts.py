import asyncio
import secrets
import time
from collections import defaultdict, deque
from datetime import datetime, timedelta, timezone
from pathlib import Path
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from app.core.config import settings
from app.core.security import bearer, current_user, hash_password, token_hash, verify_password
from app.db.models import Detection, Observation, User, UserObservation, UserSession
from app.db.session import get_session

router = APIRouter(prefix="/auth", tags=["accounts"])
attempts: dict[str, deque] = defaultdict(deque)


class Credentials(BaseModel):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[a-zA-Z0-9_]+$")
    password: str = Field(min_length=8, max_length=128)

    @field_validator("username")
    @classmethod
    def username_lower(cls, value):
        return value.lower()


class Registration(Credentials):
    nickname: str = Field(min_length=1, max_length=30)

    @field_validator("nickname")
    @classmethod
    def nickname_clean(cls, value):
        if not value.strip():
            raise ValueError("昵称不能为空")
        return value.strip()


class Profile(BaseModel):
    nickname: str = Field(min_length=1, max_length=30)


class PasswordChange(BaseModel):
    old_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=8, max_length=128)


class AccountRemoval(BaseModel):
    password: str = Field(min_length=1, max_length=128)


def user_dict(user):
    return dict(id=str(user.id), username=user.username, nickname=user.nickname, created_at=user.created_at)


def limit_auth(request: Request):
    now = time.monotonic()
    # In-process throttle for this single-server MVP; bound memory and expire old keys.
    for key in list(attempts):
        if not attempts[key] or now - attempts[key][-1] > 60:
            del attempts[key]
    key = request.client.host if request.client else "local"
    queue = attempts[key]
    while queue and now - queue[0] > 60:
        queue.popleft()
    if len(queue) >= 15:
        raise HTTPException(429, "尝试次数较多，请一分钟后重试")
    queue.append(now)


async def issue_session(user, db):
    token = secrets.token_urlsafe(32)
    await db.execute(delete(UserSession).where(UserSession.expires_at < datetime.now(timezone.utc)))
    db.add(UserSession(token_hash=token_hash(token), user_id=user.id,
                       expires_at=datetime.now(timezone.utc) + timedelta(days=settings.session_days)))
    await db.commit()
    return dict(token=token, user=user_dict(user))


@router.post("/register", status_code=201)
async def register(body: Registration, request: Request, db: AsyncSession = Depends(get_session)):
    limit_auth(request)
    if not settings.registration_enabled:
        raise HTTPException(403, "当前为小范围测试，暂未开放注册")
    user = User(username=body.username, nickname=body.nickname,
                password_hash=await asyncio.to_thread(hash_password, body.password))
    db.add(user)
    try:
        await db.flush()
    except IntegrityError:
        await db.rollback()
        raise HTTPException(409, "这个账号已被使用，换一个试试")
    return await issue_session(user, db)


@router.post("/login")
async def login(body: Credentials, request: Request, db: AsyncSession = Depends(get_session)):
    limit_auth(request)
    user = await db.scalar(select(User).where(User.username == body.username))
    # Run the same expensive check for unknown users.
    stored = user.password_hash if user else "scrypt$" + "00" * 16 + "$" + "00" * 64
    valid = await asyncio.to_thread(verify_password, body.password, stored)
    if not user or not valid:
        raise HTTPException(401, "账号或密码不正确")
    return await issue_session(user, db)


@router.get("/me")
async def me(user: User = Depends(current_user)):
    return user_dict(user)


@router.patch("/me")
async def profile(body: Profile, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    if not body.nickname.strip():
        raise HTTPException(422, "昵称不能为空")
    user.nickname = body.nickname.strip()
    await db.commit()
    return user_dict(user)


@router.post("/logout", status_code=204)
async def logout(credentials=Depends(bearer), user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    await db.execute(delete(UserSession).where(UserSession.token_hash == token_hash(credentials.credentials)))
    await db.commit()


@router.post("/password", status_code=204)
async def change_password(body: PasswordChange, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    if not await asyncio.to_thread(verify_password, body.old_password, user.password_hash):
        raise HTTPException(400, "原密码不正确")
    user.password_hash = await asyncio.to_thread(hash_password, body.new_password)
    await db.execute(delete(UserSession).where(UserSession.user_id == user.id))
    await db.commit()


@router.post("/delete-account", status_code=204)
async def delete_account(body: AccountRemoval, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    if not await asyncio.to_thread(verify_password, body.password, user.password_hash):
        raise HTTPException(400, "密码不正确，账号未注销")
    if await db.scalar(select(User.id).where(User.id == user.id).with_for_update()) is None:
        raise HTTPException(401, "账号已注销")
    records = (await db.execute(select(Observation.id, Observation.image_path)
                              .join(UserObservation).where(UserObservation.user_id == user.id))).all()
    ids = [record.id for record in records]
    if ids:
        await db.execute(delete(Detection).where(Detection.observation_id.in_(ids)))
        await db.execute(delete(Observation).where(Observation.id.in_(ids)))
    await db.delete(user)
    await db.commit()
    for record in records:
        path = Path(settings.upload_dir) / Path(record.image_path).name
        await asyncio.to_thread(path.unlink, missing_ok=True)
