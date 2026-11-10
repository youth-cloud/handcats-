from pydantic import BaseModel, Field


class ReviewSuggestion(BaseModel):
    label: str
    cat_code: str | None = None
    confidence: float = Field(ge=0, le=1)
    bounding_box: list[float] = Field(default_factory=list)


class ReviewResponse(BaseModel):
    success: bool
    model: str
    review_id: str | None = None
    suggestions: list[ReviewSuggestion] = Field(default_factory=list)
    explanation: str = ""
    message: str
    review_attempts: int = 0
    review_limit: int = 3
    automatic: bool = False
