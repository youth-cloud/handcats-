from pathlib import Path
from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    app_name: str = "Hand Cats API"
    app_version: str = "0.1.0"
    debug: bool = False
    api_prefix: str = "/api/v1"
    model_name: str = "YOLO26"
    model_variant: str = "yolo26n"
    model_weights_path: str = ""
    model_confidence: float = 0.35
    model_iou_threshold: float = 0.45
    database_url: str = "postgresql+asyncpg://postgres:change-me@127.0.0.1:5432/hand_cats"
    database_echo: bool = False
    upload_dir: str = str(BACKEND_ROOT / "storage" / "photos")
    session_days: int = 30
    max_upload_bytes: int = 12 * 1024 * 1024

    model_config = SettingsConfigDict(
        env_file=BACKEND_ROOT / ".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
