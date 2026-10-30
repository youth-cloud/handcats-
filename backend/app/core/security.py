import hashlib
import hmac
import secrets
from datetime import datetime, timezone
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.db.models import User, UserSession
from app.db.session import get_session

bearer = HTTPBearer(auto_error=False)


def hash_password(password: str) -> str:
    salt = secrets.token_hex(16)
    digest = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()
    return f"scrypt${salt}${digest}"


def verify_password(password: str, stored: str) -> bool:
    try:
        _, salt, expected = stored.split("$")
        actual = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=16384, r=8, p=1).hex()
        return hmac.compare_digest(actual, expected)
    except (ValueError, TypeError):
        return False


def token_hash(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


async def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer),
                       db: AsyncSession = Depends(get_session)) -> User:
    if credentials:
        user = await db.scalar(select(User).join(UserSession).where(
            UserSession.token_hash == token_hash(credentials.credentials),
            UserSession.expires_at > datetime.now(timezone.utc)))
        if user:
            return user
    raise HTTPException(401, "登录已过期，请重新登录", headers={"WWW-Authenticate": "Bearer"})
