"""Dataset-level validation for serialized Vision AI predictions."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from typing import Iterable

from .models import VisionPrediction


@dataclass(frozen=True, slots=True)
class PredictionIssue:
    code: str
    message: str
    image_id: str | None = None


def validate_predictions(
    predictions: Iterable[VisionPrediction],
    *,
    expected_image_ids: set[str] | None = None,
) -> list[PredictionIssue]:
    items = list(predictions)
    issues: list[PredictionIssue] = []
    counts = Counter(item.image_id for item in items)
    for image_id, count in sorted(counts.items()):
        if count > 1:
            issues.append(
                PredictionIssue("duplicate_image_id", f"appears {count} times", image_id)
            )
    if expected_image_ids is not None:
        actual = set(counts)
        for image_id in sorted(expected_image_ids - actual):
            issues.append(PredictionIssue("missing_prediction", "prediction is missing", image_id))
        for image_id in sorted(actual - expected_image_ids):
            issues.append(PredictionIssue("unknown_image_id", "image is not in manifest", image_id))
    return issues
