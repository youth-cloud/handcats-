"""Create an invited tester without opening public registration."""
import asyncio
import getpass
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.api.routes.accounts import Registration
from app.core.security import hash_password
from app.db.models import User
from app.db.session import SessionFactory, engine


async def main():
    username = input("Username: ").strip()
    nickname = input("Nickname: ").strip()
    password = getpass.getpass("Password (not echoed): ")
    if password != getpass.getpass("Repeat password: "):
        raise SystemExit("Passwords do not match")
    body = Registration(username=username, nickname=nickname, password=password)
    try:
        async with SessionFactory() as db:
            db.add(User(username=body.username,nickname=body.nickname,password_hash=hash_password(body.password)))
            await db.commit()
        print("Tester created")
    finally:
        await engine.dispose()


if __name__ == "__main__":
    asyncio.run(main())
