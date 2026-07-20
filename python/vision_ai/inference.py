"""Reliable batch execution around the stable Vision pipeline."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from data_engineering.models import ImageRecord

from .models import VisionPrediction
from .pipeline import VisionPipeline


@dataclass(frozen=True, slots=True)
class InferenceFailure:
    image_id: str
    error_type: str
    message: str

    def to_dict(self) -> dict[str, str]:
        return {
            "image_id": self.image_id,
            "error_type": self.error_type,
            "message": self.message,
        }


@dataclass(frozen=True, slots=True)
class InferenceSummary:
    total: int
    completed: int
    rejected_quality: int
    failed: int

    def to_dict(self) -> dict[str, int]:
        return {
            "total": self.total,
            "completed": self.completed,
            "rejected_quality": self.rejected_quality,
            "failed": self.failed,
        }


@dataclass(frozen=True, slots=True)
class InferenceResult:
    predictions: tuple[VisionPrediction, ...]
    failures: tuple[InferenceFailure, ...]
    summary: InferenceSummary


class InferenceRunner:
    """Run records deterministically with optional record-level error isolation."""

    def __init__(self, pipeline: VisionPipeline, *, fail_fast: bool = False):
        self.pipeline = pipeline
        self.fail_fast = fail_fast

    def run(self, records: Iterable[ImageRecord]) -> InferenceResult:
        predictions: list[VisionPrediction] = []
        failures: list[InferenceFailure] = []
        seen: set[str] = set()
        total = 0
        for record in records:
            total += 1
            try:
                if record.image_id in seen:
                    raise ValueError(f"duplicate image_id: {record.image_id}")
                seen.add(record.image_id)
                predictions.append(self.pipeline.predict(record))
            except Exception as exc:
                if self.fail_fast:
                    raise
                failures.append(
                    InferenceFailure(
                        image_id=record.image_id,
                        error_type=type(exc).__name__,
                        message=str(exc),
                    )
                )
        rejected = sum(
            item.metadata.get("status") == "rejected_quality" for item in predictions
        )
        summary = InferenceSummary(
            total=total,
            completed=len(predictions) - rejected,
            rejected_quality=rejected,
            failed=len(failures),
        )
        return InferenceResult(tuple(predictions), tuple(failures), summary)
