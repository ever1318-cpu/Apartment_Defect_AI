"""Serializable contracts for Vision AI ground truth and evaluation reports."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Mapping

from .models import BoundingBox, DefectDetection, PolygonMask


@dataclass(frozen=True, slots=True)
class GroundTruthAnnotation:
    image_id: str
    classifications: Mapping[str, str] = field(default_factory=dict)
    detections: tuple[DefectDetection, ...] = ()
    dataset_version: str = "unknown"

    def __post_init__(self) -> None:
        if not self.image_id.strip():
            raise ValueError("image_id cannot be empty")
        if not self.dataset_version.strip():
            raise ValueError("dataset_version cannot be empty")
        if any(
            not task.strip() or not label.strip()
            for task, label in self.classifications.items()
        ):
            raise ValueError("classification tasks and labels cannot be empty")

    def to_dict(self) -> dict[str, Any]:
        return {
            "image_id": self.image_id,
            "dataset_version": self.dataset_version,
            "classifications": dict(self.classifications),
            "detections": [
                {
                    "label": item.label,
                    "box": asdict(item.box),
                    "mask": asdict(item.mask) if item.mask is not None else None,
                    "severity": item.severity,
                }
                for item in self.detections
            ],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "GroundTruthAnnotation":
        try:
            detections = []
            for item in value.get("detections", ()):
                mask_value = item.get("mask")
                mask = (
                    PolygonMask(tuple(tuple(point) for point in mask_value["points"]))
                    if mask_value is not None
                    else None
                )
                detections.append(
                    DefectDetection(
                        label=item["label"],
                        confidence=1.0,
                        box=BoundingBox(**item["box"]),
                        mask=mask,
                        severity=item.get("severity"),
                    )
                )
            return cls(
                image_id=value["image_id"],
                classifications={
                    str(task): str(label)
                    for task, label in value.get("classifications", {}).items()
                },
                detections=tuple(detections),
                dataset_version=value.get("dataset_version", "unknown"),
            )
        except (KeyError, TypeError) as exc:
            raise ValueError(f"invalid GroundTruthAnnotation: {exc}") from exc


@dataclass(frozen=True, slots=True)
class EvaluationIssue:
    code: str
    message: str
    image_id: str | None = None


@dataclass(frozen=True, slots=True)
class LabelMetrics:
    precision: float
    recall: float
    f1: float
    support: int
    true_positive: int
    false_positive: int
    false_negative: int


@dataclass(frozen=True, slots=True)
class ClassificationMetrics:
    evaluated: int
    accuracy: float
    macro_precision: float
    macro_recall: float
    macro_f1: float
    confusion_matrix: Mapping[str, Mapping[str, int]]
    labels: Mapping[str, LabelMetrics]


@dataclass(frozen=True, slots=True)
class DetectionMetrics:
    precision: float
    recall: float
    f1: float
    true_positive: int
    false_positive: int
    false_negative: int
    labels: Mapping[str, LabelMetrics]


@dataclass(frozen=True, slots=True)
class SeverityMetrics:
    evaluated: int
    ignored_missing: int
    accuracy: float
    macro_precision: float
    macro_recall: float
    macro_f1: float
    confusion_matrix: Mapping[str, Mapping[str, int]]
    labels: Mapping[str, LabelMetrics]
    missing_policy: str = "exclude_matched_pairs_when_either_severity_is_missing"


@dataclass(frozen=True, slots=True)
class EvaluationReport:
    dataset_version: str
    model_version: str
    evaluated_at: str
    thresholds: Mapping[str, float]
    evaluated_images: int
    classification: Mapping[str, ClassificationMetrics]
    detection: DetectionMetrics
    severity: SeverityMetrics
    errors: tuple[EvaluationIssue, ...] = ()
    warnings: tuple[EvaluationIssue, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "EvaluationReport":
        def label_metrics(items: Mapping[str, Any]) -> dict[str, LabelMetrics]:
            return {label: LabelMetrics(**metric) for label, metric in items.items()}

        def classification_metric(item: Mapping[str, Any]) -> ClassificationMetrics:
            return ClassificationMetrics(
                evaluated=item["evaluated"],
                accuracy=item["accuracy"],
                macro_precision=item["macro_precision"],
                macro_recall=item["macro_recall"],
                macro_f1=item["macro_f1"],
                confusion_matrix={
                    actual: dict(predicted)
                    for actual, predicted in item["confusion_matrix"].items()
                },
                labels=label_metrics(item["labels"]),
            )

        detection_value = value["detection"]
        severity_value = value["severity"]
        return cls(
            dataset_version=value["dataset_version"],
            model_version=value["model_version"],
            evaluated_at=value["evaluated_at"],
            thresholds=dict(value["thresholds"]),
            evaluated_images=value["evaluated_images"],
            classification={
                task: classification_metric(metric)
                for task, metric in value["classification"].items()
            },
            detection=DetectionMetrics(
                precision=detection_value["precision"],
                recall=detection_value["recall"],
                f1=detection_value["f1"],
                true_positive=detection_value["true_positive"],
                false_positive=detection_value["false_positive"],
                false_negative=detection_value["false_negative"],
                labels=label_metrics(detection_value["labels"]),
            ),
            severity=SeverityMetrics(
                evaluated=severity_value["evaluated"],
                ignored_missing=severity_value["ignored_missing"],
                accuracy=severity_value["accuracy"],
                macro_precision=severity_value["macro_precision"],
                macro_recall=severity_value["macro_recall"],
                macro_f1=severity_value["macro_f1"],
                confusion_matrix={
                    actual: dict(predicted)
                    for actual, predicted in severity_value["confusion_matrix"].items()
                },
                labels=label_metrics(severity_value["labels"]),
                missing_policy=severity_value["missing_policy"],
            ),
            errors=tuple(EvaluationIssue(**item) for item in value.get("errors", ())),
            warnings=tuple(
                EvaluationIssue(**item) for item in value.get("warnings", ())
            ),
        )
