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
        await conn.execute(text("ALTER TABLE observations ADD COLUMN IF NOT EXISTS review_attempts INTEGER NOT NULL DEFAULT 0"))
        await conn.execute(text("ALTER TABLE observations ADD COLUMN IF NOT EXISTS auto_review_attempted BOOLEAN NOT NULL DEFAULT false"))
        await conn.execute(text("ALTER TABLE observations ADD COLUMN IF NOT EXISTS review_request_id UUID"))
        await conn.execute(text("ALTER TABLE observations ADD COLUMN IF NOT EXISTS review_busy_until TIMESTAMPTZ"))
        await conn.execute(text("ALTER TABLE detections ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT true"))
        await conn.execute(text("ALTER TABLE observations ADD COLUMN IF NOT EXISTS review_layout_version INTEGER NOT NULL DEFAULT 0"))
        # Keep old model output for diagnosis, but only the latest accepted batch
        # participates in display, selection and catalog queries. Safe to rerun.
        await conn.execute(text("""
            WITH batches AS (
                SELECT observation_id, bounding_box->>'review_id' AS rid, max(id) AS last_id
                FROM detections WHERE bounding_box->>'review_id' IS NOT NULL
                GROUP BY observation_id, bounding_box->>'review_id'
            ), latest AS (
                SELECT DISTINCT ON (observation_id) observation_id, rid
                FROM batches ORDER BY observation_id, last_id DESC
            )
            UPDATE detections d SET active = COALESCE(d.bounding_box->>'review_id' = l.rid, false)
            FROM latest l, observations o WHERE d.observation_id = l.observation_id
            AND o.id = d.observation_id AND o.review_layout_version = 0
        """))
        await conn.execute(text("""
            UPDATE observations o SET review_attempts = GREATEST(o.review_attempts, LEAST(3, (
                SELECT count(DISTINCT rid)::int FROM (
                    SELECT d.bounding_box->>'review_id' AS rid FROM detections d WHERE d.observation_id = o.id
                    UNION SELECT o.review_result->>'review_id'
                ) history WHERE rid IS NOT NULL
            ))) WHERE o.review_layout_version = 0
        """))
        await conn.execute(text("""
            UPDATE user_observations u SET confirmed_detection_ids = COALESCE((
                SELECT jsonb_agg(d.id ORDER BY d.id) FROM detections d
                WHERE d.observation_id = u.observation_id AND d.active AND d.cat_type_id IS NOT NULL
                AND (u.confirmed_detection_ids IS NULL OR u.confirmed_detection_ids @> jsonb_build_array(d.id))
            ), '[]'::jsonb)
            WHERE u.confirmed AND EXISTS (
                SELECT 1 FROM detections d WHERE d.observation_id = u.observation_id AND NOT d.active)
        """))
        await conn.execute(text("UPDATE user_observations SET confirmed = false WHERE confirmed_detection_ids = '[]'::jsonb"))
        await conn.execute(text("UPDATE observations SET review_layout_version = 1 WHERE review_layout_version = 0"))
        for code, name, _, description, *_ in CATALOG:
            await conn.execute(insert(CatType).values(code=code, display_name=name, description=description)
                               .on_conflict_do_update(index_elements=[CatType.code],
                                   set_={"display_name": name, "description": description}))
