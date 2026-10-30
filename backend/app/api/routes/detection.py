import asyncio
from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from app.core.security import current_user
from app.core.config import settings

from app.schemas.detection import DetectCatResponse
from app.services.detection_service import DetectionService

router = APIRouter(prefix="/detection", tags=["detection"])
service = DetectionService()


@router.post("/cat", response_model=DetectCatResponse)
async def detect_cat(file: UploadFile = File(...), user=Depends(current_user)) -> DetectCatResponse:
    from app.api.routes.collection import normalize_image, inference_gate
    image_bytes = await file.read(settings.max_upload_bytes + 1)
    await file.close()
    if len(image_bytes) > settings.max_upload_bytes:
        raise HTTPException(413, "照片超过 12 MB")
    image_bytes = await asyncio.to_thread(normalize_image, image_bytes)
    async with inference_gate:
        return await asyncio.to_thread(service.detect_cat,
        image_bytes=image_bytes,
        filename=file.filename,
        )
