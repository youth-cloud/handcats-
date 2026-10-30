from __future__ import annotations

import logging
import os
import tempfile
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.schemas.detection import DetectCatResponse, DetectionItem
from app.services.catalog import code_for_label

logger = logging.getLogger(__name__)


class DetectionService:
    """Run YOLO26 inference for uploaded cat photos."""

    def __init__(self) -> None:
        self._model: Any | None = None
        self._model_load_error: str | None = None

    def detect_cat(self, image_bytes: bytes, filename: str | None = None) -> DetectCatResponse:
        if not image_bytes:
            return DetectCatResponse(
                success=False,
                message="上传图片为空，无法执行识别。",
            )

        model = self._get_model()
        if model is None:
            return DetectCatResponse(
                success=False,
                message=self._model_load_error or "YOLO26 模型未能成功加载。",
            )

        temp_image_path = self._save_temp_image(image_bytes=image_bytes, filename=filename)
        try:
            results = model.predict(
                source=temp_image_path,
                conf=settings.model_confidence,
                iou=settings.model_iou_threshold,
                device="cpu",
                verbose=False,
                imgsz=settings.model_image_size,
            )
            return self._build_response(results=results)
        except Exception as exc:  # pragma: no cover - depends on runtime model and image input
            logger.exception("YOLO26 inference failed: %s", exc)
            return DetectCatResponse(
                success=False,
                message=f"YOLO26 推理失败：{exc}",
            )
        finally:
            try:
                os.remove(temp_image_path)
            except OSError:
                logger.warning("Failed to delete temporary image: %s", temp_image_path)

    def preload_model(self) -> None:
        """Warm up the model during application startup to avoid first-request timeouts."""
        model = self._get_model()
        if model is None:
            if settings.production:
                raise RuntimeError("Production model failed to load; check weights and runtime")
            logger.warning(
                "YOLO26 preload skipped because the model is unavailable: %s",
                self._model_load_error,
            )
        else:
            if settings.production:
                import numpy as np
                model.predict(source=np.zeros((settings.model_image_size, settings.model_image_size, 3), dtype=np.uint8),
                              imgsz=settings.model_image_size, device="cpu", verbose=False)
            logger.info("YOLO26 model preloaded successfully.")

    def _get_model(self) -> Any | None:
        if self._model is not None:
            return self._model
        if self._model_load_error is not None:
            return None

        try:
            if settings.production:
                os.environ["YOLO_AUTOINSTALL"] = "false"
            os.environ.setdefault("OMP_NUM_THREADS", str(settings.model_cpu_threads))
            import torch
            torch.set_num_threads(settings.model_cpu_threads)
            from ultralytics import YOLO

            model_source = self._resolve_model_source()
            logger.info("Loading YOLO26 model from %s", model_source)
            self._model = YOLO(model_source)
            self._model_load_error = None
            return self._model
        except Exception as exc:  # pragma: no cover - depends on installed runtime and weights
            logger.exception("Failed to load YOLO26 model: %s", exc)
            self._model_load_error = (
                "YOLO26 模型加载失败，请确认已执行 `pip install -r requirements.txt`，"
                "并检查模型权重路径或网络下载权限。"
            )
            return None

    def _resolve_model_source(self) -> str:
        if settings.production and not Path(settings.model_weights_path).is_file():
            raise ValueError("Production requires an explicit existing model file")
        if settings.model_weights_path.strip():
            return settings.model_weights_path.strip()

        variant = settings.model_variant.strip()
        return variant if variant.endswith(".pt") else f"{variant}.pt"

    def _save_temp_image(self, image_bytes: bytes, filename: str | None) -> str:
        suffix = Path(filename or "upload.jpg").suffix or ".jpg"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
            temp_file.write(image_bytes)
            return temp_file.name

    def _build_response(self, results: list[Any]) -> DetectCatResponse:
        detections: list[DetectionItem] = []

        for result in results:
            boxes = getattr(result, "boxes", None)
            if boxes is None or len(boxes) == 0:
                continue

            names = getattr(result, "names", {}) or {}
            orig_height, orig_width = getattr(result, "orig_shape", (1, 1))
            xyxy_values = boxes.xyxy.tolist()
            confidence_values = boxes.conf.tolist()
            class_values = boxes.cls.tolist()

            for index, coordinates in enumerate(xyxy_values):
                class_index = int(class_values[index])
                label = str(names.get(class_index, class_index))
                detections.append(
                    DetectionItem(
                        label=label,
                        confidence=float(confidence_values[index]),
                        bounding_box=self._normalize_box(
                            coordinates=coordinates,
                            width=orig_width,
                            height=orig_height,
                        ),
                    )
                )

        cat_detections = self._filter_cat_labels(detections)
        if not detections:
            return DetectCatResponse(
                success=True,
                message="YOLO26 推理完成，但没有识别到猫目标。",
            )

        if not cat_detections:
            return DetectCatResponse(
                success=True,
                message="YOLO26 推理完成，但当前结果里没有猫类别。",
            )

        return DetectCatResponse(
            success=True,
            message=f"YOLO26 推理完成，识别到 {len(cat_detections)} 个猫目标。",
            detections=cat_detections,
        )

    def _filter_cat_labels(self, detections: list[DetectionItem]) -> list[DetectionItem]:
        return [
            detection
            for detection in detections
            if "cat" in detection.label.lower() or "猫" in detection.label or code_for_label(detection.label)
        ]

    def _normalize_box(
        self,
        coordinates: list[float],
        width: int,
        height: int,
    ) -> list[float]:
        safe_width = max(width, 1)
        safe_height = max(height, 1)
        x1, y1, x2, y2 = coordinates
        return [
            round(float(x1) / safe_width, 4),
            round(float(y1) / safe_height, 4),
            round(float(x2) / safe_width, 4),
            round(float(y2) / safe_height, 4),
        ]
