from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import Boolean, DateTime, ForeignKey, Identity, Integer, Numeric, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base


class CatType(Base):
    __tablename__ = "cat_types"

    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    code: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    display_name: Mapped[str] = mapped_column(String(100))
    description: Mapped[str | None] = mapped_column(Text)
    cover_image_path: Mapped[str | None] = mapped_column(String(500))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class Observation(Base):
    __tablename__ = "observations"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    image_path: Mapped[str] = mapped_column(String(500))
    status: Mapped[str] = mapped_column(String(32), default="processed", index=True)
    model_version: Mapped[str | None] = mapped_column(String(100))
    captured_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    detections: Mapped[list[Detection]] = relationship(back_populates="observation", cascade="all, delete-orphan")


class Detection(Base):
    __tablename__ = "detections"

    id: Mapped[int] = mapped_column(Integer, Identity(), primary_key=True)
    observation_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("observations.id", ondelete="CASCADE"), index=True)
    cat_type_id: Mapped[int | None] = mapped_column(ForeignKey("cat_types.id", ondelete="SET NULL"), index=True)
    raw_label: Mapped[str] = mapped_column(String(100))
    confidence: Mapped[Decimal] = mapped_column(Numeric(6, 5))
    bounding_box: Mapped[dict] = mapped_column(JSONB)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())
    observation: Mapped[Observation] = relationship(back_populates="detections")
    cat_type: Mapped[CatType | None] = relationship()


class User(Base):
    __tablename__ = "users"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    username: Mapped[str] = mapped_column(String(32), unique=True, index=True)
    nickname: Mapped[str] = mapped_column(String(30))
    password_hash: Mapped[str] = mapped_column(String(256))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now())


class UserSession(Base):
    __tablename__ = "user_sessions"

    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True))


class UserObservation(Base):
    """Ownership is additive, keeping pre-account observations intact and private."""
    __tablename__ = "user_observations"

    observation_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("observations.id", ondelete="CASCADE"), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    request_id: Mapped[str] = mapped_column(String(100), unique=True)
    note: Mapped[str] = mapped_column(String(500), default="")
    favorite: Mapped[bool] = mapped_column(Boolean, default=False)
    confirmed: Mapped[bool] = mapped_column(Boolean, default=False)
    # NULL preserves legacy whole-photo confirmation; [] explicitly selects none.
    confirmed_detection_ids: Mapped[list[int] | None] = mapped_column(JSONB(none_as_null=True), nullable=True)
