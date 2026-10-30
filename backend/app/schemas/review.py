from pydantic import BaseModel, Field


class ReviewSuggestion(BaseModel):
    label: str
    cat_code: str | None = None
    confidence: float = Field(ge=0, le=1)


class ReviewResponse(BaseModel):
    success: bool
    model: str
    review_id: str | None = None
    suggestions: list[ReviewSuggestion] = Field(default_factory=list)
    explanation: str = ""
    message: str
