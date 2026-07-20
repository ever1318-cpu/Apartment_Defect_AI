"""Reliable batch execution around the stable Vision pipeline."""

from __future__ import annotations

from dataclasses import dataclass, replace
from pathlib import Path
from time import perf_counter_ns
from typing import Iterable

from data_engineering.models import ImageRecord

from .image_io import inspect_image_file
from .models import ImageQuality, VisionPrediction
from .pipeline import VisionPipeline


@dataclass(frozen=True, slots=True)
class InferenceFailure:
    image_id: str
    error_type: str
    message: str
    duration_ms: float = 0.0
    backend_name: str = "unknown"
    model_version: str = "unknown"

    def to_dict(self) -> dict[str, str | float]:
        return {
            "image_id": self.image_id,
            "error_type": self.error_type,
            "message": self.message,
            "duration_ms": self.duration_ms,
            "backend_name": self.backend_name,
            "model_version": self.model_version,
        }

    def to_prediction(self) -> VisionPrediction:
        return VisionPrediction(
            image_id=self.image_id,
            model_version=self.model_version,
            quality=ImageQuality(0.0, False, (self.error_type,)),
            metadata={
                "status": "error",
                "error_type": self.error_type,
                "error": self.message,
                "duration_ms": self.duration_ms,
                "backend_name": self.backend_name,
                "model_version": self.model_version,
            },
        )


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
    outputs: tuple[VisionPrediction, ...] = ()


class InferenceRunner:
    """Run records deterministically with optional record-level error isolation."""

    def __init__(
        self,
        pipeline: VisionPipeline,
        *,
        fail_fast: bool = False,
        validate_images: bool = False,
        root: str | Path | None = None,
    ):
        self.pipeline = pipeline
        self.fail_fast = fail_fast
        self.validate_images = validate_images
        self.root = Path(root) if root is not None else None

    def run(self, records: Iterable[ImageRecord]) -> InferenceResult:
        predictions: list[VisionPrediction] = []
        failures: list[InferenceFailure] = []
        outputs: list[VisionPrediction] = []
        seen: set[str] = set()
        total = 0
        for record in records:
            total += 1
            started = perf_counter_ns()
            try:
                if record.image_id in seen:
                    raise ValueError(f"duplicate image_id: {record.image_id}")
                seen.add(record.image_id)
                prepared = self._prepare_record(record)
                prediction = self.pipeline.predict(prepared)
                duration_ms = (perf_counter_ns() - started) / 1_000_000
                prediction = replace(
                    prediction,
                    metadata={
                        **prediction.metadata,
                        "duration_ms": duration_ms,
                        "backend_name": self.backend_name,
                        "model_version": self.model_version,
                    },
                )
                predictions.append(prediction)
                outputs.append(prediction)
            except Exception as exc:
                if self.fail_fast:
                    raise
                failure = InferenceFailure(
                    image_id=record.image_id,
                    error_type=type(exc).__name__,
                    message=str(exc),
                    duration_ms=(perf_counter_ns() - started) / 1_000_000,
                    backend_name=self.backend_name,
                    model_version=self.model_version,
                )
                failures.append(failure)
                outputs.append(failure.to_prediction())
        rejected = sum(
            item.metadata.get("status") == "rejected_quality" for item in predictions
        )
        summary = InferenceSummary(
            total=total,
            completed=len(predictions) - rejected,
            rejected_quality=rejected,
            failed=len(failures),
        )
        return InferenceResult(
            tuple(predictions), tuple(failures), summary, tuple(outputs)
        )

    def predict_image(
        self,
        image_path: str | Path,
        *,
        image_id: str | None = None,
        group_id: str = "single-image",
        label: str = "unknown",
    ) -> InferenceResult:
        path = Path(image_path)
        record = ImageRecord(
            image_id=image_id or path.stem,
            image_path=str(path),
            group_id=group_id,
            label=label,
        )
        return self.run((record,))

    @property
    def backend_name(self) -> str:
        backend = self.pipeline.backend
        return str(getattr(backend, "backend_name", type(backend).__name__))

    @property
    def model_version(self) -> str:
        return self.pipeline.backend.model_version

    def _prepare_record(self, record: ImageRecord) -> ImageRecord:
        if not self.validate_images:
            return record
        info = inspect_image_file(record.image_path, root=self.root)
        return replace(record, image_path=str(info.path))
