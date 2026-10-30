from __future__ import annotations

import base64
import json
import logging
from typing import Any

import httpx

from app.core.config import settings
from app.schemas.review import ReviewResponse, ReviewSuggestion
from app.services.catalog import CATALOG, code_for_label

logger = logging.getLogger(__name__)


class DeepSeekReviewService:
    """Ask DeepSeek to provide a constrained visual second opinion."""

    async def review(self, image: bytes, yolo_labels: list[str]) -> ReviewResponse:
        if not settings.deepseek_api_key:
            return ReviewResponse(success=False, model=settings.deepseek_model,
                                  message="智能复核尚未配置，请在服务器 .env 中设置 DEEPSEEK_API_KEY")

        allowed = [item[1] for item in CATALOG]
        prompt = (
            "你是拾猫应用的图像复核器。请观察图片中的猫，只能从给定图鉴类别中选择，"
            "不要输出图鉴之外的品种，也不要做血统鉴定。图片中有几只猫就返回几个 cats，"
            "按从左到右、从上到下排列。若无法判断，返回空数组。\n"
            f"图鉴类别：{json.dumps(allowed, ensure_ascii=False)}\n"
            f"YOLO 当前结果（仅供参考）：{json.dumps(yolo_labels, ensure_ascii=False)}\n"
            '只返回 JSON：{"cats":[{"label":"图鉴名称","confidence":0.0}],"explanation":"简短说明"}'
        )
        encoded = base64.b64encode(image).decode("ascii")
        payload = {
            "model": settings.deepseek_model,
            "messages": [{"role": "user", "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{encoded}", "detail": "high"}},
            ]}],
            "temperature": 0,
            "stream": False,
        }
        try:
            async with httpx.AsyncClient(timeout=settings.deepseek_timeout_seconds) as client:
                response = await client.post(
                    f"{settings.deepseek_base_url.rstrip('/')}/chat/completions",
                    headers={"Authorization": f"Bearer {settings.deepseek_api_key}", "Content-Type": "application/json"},
                    json=payload,
                )
                response.raise_for_status()
                body = response.json()
                content = body["choices"][0]["message"]["content"]
            data = self._json_object(content)
            suggestions: list[ReviewSuggestion] = []
            for item in data.get("cats", []):
                if not isinstance(item, dict):
                    continue
                code = code_for_label(str(item.get("label", "")))
                if not code:
                    continue
                canonical = next(name for c, name, *_ in CATALOG if c == code)
                try:
                    confidence = max(0.0, min(1.0, float(item.get("confidence", 0))))
                except (TypeError, ValueError):
                    confidence = 0.0
                suggestions.append(ReviewSuggestion(label=canonical, cat_code=code, confidence=confidence))
            explanation = str(data.get("explanation", "")).strip()[:500]
            return ReviewResponse(success=True, model=settings.deepseek_model, suggestions=suggestions,
                                  explanation=explanation,
                                  message="智能复核完成，请结合照片确认结果。" if suggestions else "暂时无法从图鉴中确认类别，请换一张更清晰的照片。")
        except (httpx.HTTPError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            logger.warning("DeepSeek review failed: %s", type(exc).__name__)
            return ReviewResponse(success=False, model=settings.deepseek_model,
                                  message="智能复核暂时不可用，请稍后重试。")

    @staticmethod
    def _json_object(content: Any) -> dict[str, Any]:
        if isinstance(content, list):
            content = "".join(str(part.get("text", "")) if isinstance(part, dict) else str(part) for part in content)
        text = str(content).strip()
        if text.startswith("```"):
            text = text.strip("`").removeprefix("json").strip()
        start, end = text.find("{"), text.rfind("}")
        if start < 0 or end <= start:
            raise ValueError("DeepSeek did not return a JSON object")
        value = json.loads(text[start:end + 1])
        if not isinstance(value, dict):
            raise ValueError("DeepSeek JSON result is not an object")
        return value
