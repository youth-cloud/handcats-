"""Create Hand Cats tables and seed the current 12 cat types."""
from __future__ import annotations

import asyncio

from sqlalchemy import select

from app.db.base import Base
from app.db.models import CatType
from app.db.session import SessionFactory, engine

CAT_TYPES = (
    ("birman", "伯曼猫"),
    ("british_shorthair", "英国短毛猫"),
    ("maine_coon", "缅因猫"),
    ("persian", "波斯猫"),
    ("ragdoll", "布偶猫"),
    ("russian_blue", "俄罗斯蓝猫"),
    ("siamese", "暹罗猫"),
    ("sphynx", "斯芬克斯猫"),
    ("domestic_tabby", "中华田园猫（狸花）"),
    ("domestic_orange", "中华田园猫（橘）"),
    ("domestic_white", "中华田园猫（白）"),
    ("domestic_calico", "中华田园猫（三花）"),
)


async def main() -> None:
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    async with SessionFactory() as session:
        existing = {row[0] for row in (await session.execute(select(CatType.code))).all()}
        session.add_all(
            CatType(code=code, display_name=name)
            for code, name in CAT_TYPES
            if code not in existing
        )
        await session.commit()
    await engine.dispose()
    print("Database tables are ready; cat_types seed is complete.")


if __name__ == "__main__":
    asyncio.run(main())
