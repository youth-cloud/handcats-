import asyncio
from fastapi import APIRouter, Depends, File, HTTPException, UploadFile
from app.core.security import current_user
from app.core.config import settings

from app.schemas.detection import DetectCatResponse
from app.services.detection_service import DetectionService
from app.services.work_gate import inference_gate, upload_throttle
from app.db.session import get_session

router = APIRouter(prefix="/detection", tags=["detection"])
service = DetectionService()


@router.post("/cat", response_model=DetectCatResponse)
async def detect_cat(file: UploadFile = File(...), user=Depends(current_user), db=Depends(get_session)) -> DetectCatResponse:
    from app.api.routes.collection import normalize_image
    upload_throttle.check(user.id)
    await db.commit()
    image_bytes = await file.read(settings.max_upload_bytes + 1)
    await file.close()
    if len(image_bytes) > settings.max_upload_bytes:
        raise HTTPException(413, "照片超过 12 MB")
    def process():
        return service.detect_cat(normalize_image(image_bytes), file.filename)
    return await inference_gate.run_sync(process)
