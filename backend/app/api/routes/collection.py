import asyncio
import io
import uuid
import warnings
from pathlib import Path
from fastapi import APIRouter, Depends, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from PIL import Image, ImageOps, UnidentifiedImageError
from PIL.Image import open as open_uploaded_image
from pydantic import BaseModel, Field
from sqlalchemy import distinct, func, select, or_
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload
from app.api.routes.detection import service
from app.core.config import settings
from app.core.security import current_user
from app.db.models import CatType, Detection, Observation, User, UserObservation
from app.db.session import get_session
from app.services.catalog import CATALOG, code_for_label

router = APIRouter(tags=["collection"])
inference_gate = asyncio.Semaphore(1)


def normalize_image(data: bytes) -> bytes:
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            # Capture Pillow's reader before YOLO preloading patches Image.open.
            # Invalid uploads must not trigger Ultralytics' runtime pip install.
            with open_uploaded_image(io.BytesIO(data)) as source:
                if source.width * source.height > 25_000_000:
                    raise HTTPException(413, "照片像素过大，请选择小于 2500 万像素的照片")
                image = ImageOps.exif_transpose(source).convert("RGB")
                image.thumbnail((2048, 2048))
                output = io.BytesIO()
                image.save(output, "JPEG", quality=90)
                return output.getvalue()
    except (UnidentifiedImageError, OSError, ValueError, Image.DecompressionBombError, Image.DecompressionBombWarning):
        raise HTTPException(400, "图片无法读取，请选择有效的 JPG、PNG 或 WebP 照片")


async def owned(observation_id, user, db):
    row = (await db.execute(select(Observation, UserObservation).join(UserObservation).where(
        Observation.id == observation_id, UserObservation.user_id == user.id)
        .options(selectinload(Observation.detections).selectinload(Detection.cat_type)))).first()
    if not row:
        raise HTTPException(404, "这条记录不存在")
    return row


def observation_dict(obs, owner):
    selected_ids = owner.confirmed_detection_ids
    detections = [dict(id=d.id, label=d.cat_type.display_name if d.cat_type else d.raw_label, confidence=float(d.confidence),
                       bounding_box=d.bounding_box.get("xyxy", []),
                       cat_code=d.cat_type.code if d.cat_type else None,
                       confirmed=bool(owner.confirmed and d.cat_type_id and (selected_ids is None or d.id in selected_ids))) for d in obs.detections]
    # Stable numbering in photo overlays, cards, and saved selection.
    detections.sort(key=lambda d: (-d["confidence"], d["id"]))
    if not detections:
        message = "没有发现清晰的猫咪，试试更近一些或换个角度。"
    elif not any(d["cat_code"] for d in detections):
        message = "发现猫咪了！当前模型还不能确定图鉴类别，这次相遇已保存。"
    else:
        message = "请按照片编号逐一核对猫咪与类别，只勾选正确的结果收进图鉴。" if len(detections) > 1 else "识别完成。请核对外观，确认后即可收进图鉴。"
    return dict(id=str(obs.id), created_at=obs.created_at, status=obs.status,
                image_url=f"{settings.api_prefix}/observations/{obs.id}/image",
                note=owner.note, favorite=owner.favorite, confirmed=owner.confirmed,
                confirmed_detection_ids=selected_ids,
                model_version=obs.model_version, detections=detections, message=message)


@router.get("/catalog")
async def catalog(user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    counts = dict((await db.execute(select(CatType.code, func.count(distinct(Observation.id)))
        .join(Detection, Detection.cat_type_id == CatType.id)
        .join(Observation, Observation.id == Detection.observation_id)
        .join(UserObservation).where(UserObservation.user_id == user.id, UserObservation.confirmed.is_(True),
            or_(UserObservation.confirmed_detection_ids.is_(None),
                UserObservation.confirmed_detection_ids.op("@>")(func.jsonb_build_array(Detection.id))))
        .group_by(CatType.code))).all())
    return [dict(code=c[0], name=c[1], subtitle=c[2], description=c[3], tip=c[4], group=c[5],
                 collected=counts.get(c[0], 0) > 0, encounters=counts.get(c[0], 0)) for c in CATALOG]


@router.get("/observations")
async def observations(offset: int = 0, limit: int = 30, cat_code: str | None = None,
                       user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    if offset < 0 or not 1 <= limit <= 100:
        raise HTTPException(422, "分页参数不正确")
    query = select(Observation, UserObservation).join(UserObservation).where(UserObservation.user_id == user.id)
    if cat_code is not None:
        if cat_code not in {c[0] for c in CATALOG}:
            raise HTTPException(422, "图鉴类别不存在")
        # EXISTS keeps one row per photo even when several boxes match this category.
        matches_category = (select(Detection.id).join(CatType, Detection.cat_type_id == CatType.id)
                            .where(Detection.observation_id == Observation.id, CatType.code == cat_code).exists())
        query = query.where(matches_category)
    total = await db.scalar(select(func.count()).select_from(query.subquery()))
    rows = (await db.execute(query.options(selectinload(Observation.detections).selectinload(Detection.cat_type))
                            .order_by(Observation.created_at.desc(), Observation.id).offset(offset).limit(limit))).all()
    return dict(items=[observation_dict(*row) for row in rows], total=total)


@router.post("/observations", status_code=201)
async def upload(file: UploadFile = File(...), x_request_id: uuid.UUID = Header(...),
                 user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    request_key = f"{user.id}:{x_request_id}"
    prior = await db.scalar(select(UserObservation).where(UserObservation.request_id == request_key))
    if prior:
        return observation_dict(*await owned(prior.observation_id, user, db))
    raw = await file.read(settings.max_upload_bytes + 1)
    await file.close()
    if len(raw) > settings.max_upload_bytes:
        raise HTTPException(413, "照片超过 12 MB，请压缩后再试")
    image = await asyncio.to_thread(normalize_image, raw)
    async with inference_gate:
        result = await asyncio.to_thread(service.detect_cat, image, "photo.jpg")
    if not result.success:
        raise HTTPException(503, "识别服务暂时不可用，请稍后重试；照片尚未保存")
    # Coordinate with account removal before persisting an in-flight inference.
    if await db.scalar(select(User.id).where(User.id == user.id).with_for_update()) is None:
        raise HTTPException(401, "账号已注销，照片未保存")
    categories = {c.code: c for c in (await db.scalars(select(CatType))).all()}
    observation_id = uuid.uuid4()
    directory = Path(settings.upload_dir)
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{observation_id}.jpg"
    await asyncio.to_thread(path.write_bytes, image)
    obs = Observation(id=observation_id, image_path=path.name, status="processed",
                      model_version=Path(settings.model_weights_path or settings.model_variant).name)
    for item in result.detections:
        category = categories.get(code_for_label(item.label))
        obs.detections.append(Detection(raw_label=item.label, confidence=item.confidence,
                             cat_type=category, bounding_box={"xyxy": item.bounding_box}))
    owner = UserObservation(observation_id=obs.id, user_id=user.id, request_id=request_key)
    try:
        db.add(obs)
        await db.flush()
        db.add(owner)
        await db.commit()
    except IntegrityError:
        await db.rollback()
        path.unlink(missing_ok=True)
        prior = await db.scalar(select(UserObservation).where(UserObservation.request_id == request_key))
        if prior:
            return observation_dict(*await owned(prior.observation_id, user, db))
        raise
    except Exception:
        await db.rollback()
        path.unlink(missing_ok=True)
        raise
    return observation_dict(*await owned(obs.id, user, db))


@router.get("/observations/{observation_id}")
async def detail(observation_id: uuid.UUID, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    return observation_dict(*await owned(observation_id, user, db))


@router.get("/observations/{observation_id}/image")
async def photo(observation_id: uuid.UUID, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    obs, _ = await owned(observation_id, user, db)
    path = Path(settings.upload_dir) / Path(obs.image_path).name
    if not path.is_file():
        raise HTTPException(404, "照片文件不存在")
    return FileResponse(path, media_type="image/jpeg", headers={"Cache-Control": "private, no-store"})


class ObservationEdit(BaseModel):
    note: str | None = Field(default=None, max_length=500)
    favorite: bool | None = None
    confirmed: bool | None = None
    confirmed_detection_ids: list[int] | None = Field(default=None, max_length=300)


@router.patch("/observations/{observation_id}")
async def edit(observation_id: uuid.UUID, body: ObservationEdit, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    obs, owner = await owned(observation_id, user, db)
    if body.confirmed_detection_ids is not None:
        valid_ids = {d.id for d in obs.detections if d.cat_type_id is not None}
        selected = sorted(set(body.confirmed_detection_ids))
        if not set(selected).issubset(valid_ids):
            raise HTTPException(400, "只能收集这张照片中已识别类别的猫咪")
        if (body.confirmed is False and selected) or (body.confirmed is True and not selected):
            raise HTTPException(400, "收集状态与所选猫咪不一致")
        owner.confirmed_detection_ids = selected
        owner.confirmed = bool(selected)
    elif body.confirmed is not None:
        valid_ids = sorted(d.id for d in obs.detections if d.cat_type_id is not None)
        if body.confirmed and not valid_ids:
            raise HTTPException(400, "还没有识别到可收集的类别，暂时不能点亮图鉴")
        owner.confirmed_detection_ids = valid_ids if body.confirmed else []
        owner.confirmed = body.confirmed
    for key, value in body.model_dump(exclude_none=True, exclude={"confirmed", "confirmed_detection_ids"}).items():
        setattr(owner, key, value)
    await db.commit()
    return observation_dict(obs, owner)


@router.delete("/observations/{observation_id}", status_code=204)
async def remove(observation_id: uuid.UUID, user: User = Depends(current_user), db: AsyncSession = Depends(get_session)):
    obs, owner = await owned(observation_id, user, db)
    image_path = Path(settings.upload_dir) / Path(obs.image_path).name
    await db.delete(owner)
    await db.delete(obs)
    await db.commit()
    await asyncio.to_thread(image_path.unlink, missing_ok=True)
