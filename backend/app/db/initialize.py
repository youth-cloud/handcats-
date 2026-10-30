from sqlalchemy import select, text
from sqlalchemy.dialects.postgresql import insert
from app.db.base import Base
from app.db.models import CatType
from app.db.session import engine
from app.services.catalog import CATALOG


async def initialize_database():
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        await conn.execute(text("ALTER TABLE user_observations ADD COLUMN IF NOT EXISTS confirmed_detection_ids JSONB"))
        await conn.execute(text("ALTER TABLE observations ADD COLUMN IF NOT EXISTS review_result JSONB"))
        for code, name, _, description, *_ in CATALOG:
            await conn.execute(insert(CatType).values(code=code, display_name=name, description=description)
                               .on_conflict_do_update(index_elements=[CatType.code],
                                   set_={"display_name": name, "description": description}))
