from pydantic import BaseModel, Field


class DetectionItem(BaseModel):
    label: str
    confidence: float = Field(ge=0.0, le=1.0)
    bounding_box: list[float] = Field(default_factory=list)


class DetectCatResponse(BaseModel):
    success: bool
    message: str
    detections: list[DetectionItem] = Field(default_factory=list)
