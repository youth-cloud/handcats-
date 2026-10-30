from pathlib import Path
import os
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
    model_image_size: int = 640
    model_cpu_threads: int = 2
    production: bool = False
    registration_enabled: bool = True
    inference_capacity: int = 5
    inference_queue_seconds: float = 25.0
    review_capacity: int = 4
    database_url: str = "postgresql+asyncpg://postgres:change-me@127.0.0.1:5432/hand_cats"
    database_echo: bool = False
    upload_dir: str = str(BACKEND_ROOT / "storage" / "photos")
    session_days: int = 30
    max_upload_bytes: int = 12 * 1024 * 1024
    # Optional visual second opinion. Keep the key on the server only.
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_model: str = "deepseek-flash"
    deepseek_timeout_seconds: float = 45.0

    model_config = SettingsConfigDict(
        # systemd can decrypt a credential file into a private runtime directory.
        env_file=os.environ.get("HANDCATS_ENV_FILE", str(BACKEND_ROOT / ".env")),
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
