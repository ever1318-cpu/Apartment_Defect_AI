"""Backend-neutral orchestration for multi-stage Vision AI inference."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping, Protocol, Sequence

from data_engineering.models import ImageRecord

from .models import Classification, DefectDetection, ImageQuality, VisionPrediction
from .postprocessing import assign_severity, non_maximum_suppression, top_k_classifications


class VisionBackend(Protocol):
    """Adapter boundary for PyTorch, ONNX, hosted, or test backends."""

    @property
    def model_version(self) -> str: ...

    def assess_quality(self, image_path: str) -> ImageQuality: ...

    def classify(
        self, image_path: str, task: str
    ) -> Sequence[Classification]: ...

    def detect(self, image_path: str) -> Sequence[DefectDetection]: ...


@dataclass(frozen=True, slots=True)
class PipelineConfig:
    classification_tasks: tuple[str, ...] = ("space", "trade", "component")
    classification_threshold: float = 0.25
    classification_top_k: int = 3
    detection_threshold: float = 0.25
    nms_iou_threshold: float = 0.5
    reject_low_quality: bool = True
    severity_medium_area: float = 0.02
    severity_high_area: float = 0.10

    def __post_init__(self) -> None:
        if not self.classification_tasks or any(
            not task.strip() for task in self.classification_tasks
        ):
            raise ValueError("classification_tasks must contain non-empty tasks")
        if len(set(self.classification_tasks)) != len(self.classification_tasks):
            raise ValueError("classification_tasks cannot contain duplicates")
        if self.classification_top_k <= 0:
            raise ValueError("classification_top_k must be positive")
        for name in (
            "classification_threshold",
            "detection_threshold",
            "nms_iou_threshold",
        ):
            value = getattr(self, name)
            if not 0 <= value <= 1:
                raise ValueError(f"{name} must be between 0 and 1")
        if not 0 <= self.severity_medium_area < self.severity_high_area <= 1:
            raise ValueError(
                "severity areas must satisfy 0 <= medium < high <= 1"
            )


class VisionPipeline:
    def __init__(self, backend: VisionBackend, config: PipelineConfig | None = None):
        self.backend = backend
        self.config = config or PipelineConfig()

    def predict(self, record: ImageRecord) -> VisionPrediction:
        quality = self.backend.assess_quality(record.image_path)
        if self.config.reject_low_quality and not quality.acceptable:
            return VisionPrediction(
                image_id=record.image_id,
                model_version=self.backend.model_version,
                quality=quality,
                metadata={"status": "rejected_quality"},
            )

        classifications: dict[str, tuple[Classification, ...]] = {}
        for task in self.config.classification_tasks:
            classifications[task] = top_k_classifications(
                self.backend.classify(record.image_path, task),
                minimum_confidence=self.config.classification_threshold,
                limit=self.config.classification_top_k,
            )
        detections = non_maximum_suppression(
            self.backend.detect(record.image_path),
            confidence_threshold=self.config.detection_threshold,
            iou_threshold=self.config.nms_iou_threshold,
        )
        severity_thresholds = {
            "medium": self.config.severity_medium_area,
            "high": self.config.severity_high_area,
        }
        detections = tuple(assign_severity(item, severity_thresholds) for item in detections)
        return VisionPrediction(
            image_id=record.image_id,
            model_version=self.backend.model_version,
            quality=quality,
            classifications=classifications,
            detections=detections,
            metadata={"status": "completed"},
        )

    def predict_many(self, records: Sequence[ImageRecord]) -> list[VisionPrediction]:
        seen: set[str] = set()
        predictions: list[VisionPrediction] = []
        for record in records:
            if record.image_id in seen:
                raise ValueError(f"duplicate image_id: {record.image_id}")
            seen.add(record.image_id)
            predictions.append(self.predict(record))
        return predictions
