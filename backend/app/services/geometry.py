"""Shared validation for normalized photo coordinates from external models."""
import math


def valid_box(value) -> list[float]:
    if not isinstance(value, (list, tuple)) or len(value) != 4:
        return []
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in value):
        return []
    x1, y1, x2, y2 = value
    if not (0 <= x1 < x2 <= 1 and 0 <= y1 < y2 <= 1):
        return []
    return [float(v) for v in value]
