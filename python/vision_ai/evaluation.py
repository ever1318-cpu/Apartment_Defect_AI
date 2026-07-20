"""Dependency-free classification, detection, and severity evaluation."""

from __future__ import annotations

from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Iterable, Mapping

from .evaluation_models import (
    ClassificationMetrics,
    DetectionMetrics,
    EvaluationIssue,
    EvaluationReport,
    GroundTruthAnnotation,
    LabelMetrics,
    SeverityMetrics,
)
from .models import DefectDetection, VisionPrediction
from .postprocessing import intersection_over_union

_NONE_LABEL = "__none__"
_SEVERITY_LABELS = ("low", "medium", "high")


@dataclass(frozen=True, slots=True)
class EvaluationConfig:
    confidence_threshold: float = 0.25
    iou_threshold: float = 0.5
    dataset_version: str | None = None
    evaluated_at: str | None = None

    def __post_init__(self) -> None:
        for name in ("confidence_threshold", "iou_threshold"):
            value = getattr(self, name)
            if not 0 <= value <= 1:
                raise ValueError(f"{name} must be between 0 and 1")
        if self.dataset_version is not None and not self.dataset_version.strip():
            raise ValueError("dataset_version cannot be empty")


def evaluate_predictions(
    ground_truth: Iterable[GroundTruthAnnotation],
    predictions: Iterable[VisionPrediction],
    config: EvaluationConfig | None = None,
) -> EvaluationReport:
    settings = config or EvaluationConfig()
    truth_items = list(ground_truth)
    prediction_items = list(predictions)
    errors = _duplicate_issues(truth_items, prediction_items)
    warnings: list[EvaluationIssue] = []
    truth_by_id = _first_by_image_id(truth_items)
    prediction_by_id = _first_by_image_id(prediction_items)

    for image_id in sorted(set(truth_by_id) - set(prediction_by_id)):
        warnings.append(
            EvaluationIssue(
                "missing_prediction",
                "ground-truth image has no prediction and was skipped",
                image_id,
            )
        )
    for image_id in sorted(set(prediction_by_id) - set(truth_by_id)):
        warnings.append(
            EvaluationIssue(
                "unknown_image_id",
                "prediction has no ground-truth annotation and was skipped",
                image_id,
            )
        )

    paired_ids = sorted(set(truth_by_id) & set(prediction_by_id))
    pairs = [(truth_by_id[image_id], prediction_by_id[image_id]) for image_id in paired_ids]
    for _, prediction in pairs:
        if prediction.metadata.get("status") == "error":
            warnings.append(
                EvaluationIssue(
                    "prediction_error",
                    str(prediction.metadata.get("error", "inference failed")),
                    prediction.image_id,
                )
            )

    dataset_version = _dataset_version(truth_items, settings, warnings)
    model_version = _model_version(prediction_items, warnings)
    evaluated_at = settings.evaluated_at or datetime.now(timezone.utc).isoformat()

    if errors:
        classification: dict[str, ClassificationMetrics] = {}
        detection = _empty_detection()
        severity = _empty_severity()
        evaluated_images = 0
    else:
        classification = _evaluate_classification(
            pairs, settings.confidence_threshold
        )
        detection, severity = _evaluate_detection(
            pairs,
            confidence_threshold=settings.confidence_threshold,
            iou_threshold=settings.iou_threshold,
        )
        evaluated_images = len(pairs)
        if not pairs:
            errors.append(
                EvaluationIssue(
                    "no_evaluable_images",
                    "ground truth and predictions have no matching image_id",
                )
            )

    return EvaluationReport(
        dataset_version=dataset_version,
        model_version=model_version,
        evaluated_at=evaluated_at,
        thresholds={
            "confidence": settings.confidence_threshold,
            "iou": settings.iou_threshold,
        },
        evaluated_images=evaluated_images,
        classification=classification,
        detection=detection,
        severity=severity,
        errors=tuple(errors),
        warnings=tuple(warnings),
    )


def _duplicate_issues(
    truth: list[GroundTruthAnnotation], predictions: list[VisionPrediction]
) -> list[EvaluationIssue]:
    issues: list[EvaluationIssue] = []
    for source, items in (("ground_truth", truth), ("prediction", predictions)):
        counts = Counter(item.image_id for item in items)
        for image_id, count in sorted(counts.items()):
            if count > 1:
                issues.append(
                    EvaluationIssue(
                        f"duplicate_{source}_image_id",
                        f"image_id appears {count} times",
                        image_id,
                    )
                )
    return issues


def _first_by_image_id(items):
    result = {}
    for item in items:
        result.setdefault(item.image_id, item)
    return result


def _dataset_version(
    truth: list[GroundTruthAnnotation],
    settings: EvaluationConfig,
    warnings: list[EvaluationIssue],
) -> str:
    if settings.dataset_version is not None:
        return settings.dataset_version
    versions = sorted(
        {
            item.dataset_version
            for item in truth
            if item.dataset_version != "unknown"
        }
    )
    if len(versions) == 1:
        return versions[0]
    if len(versions) > 1:
        warnings.append(
            EvaluationIssue(
                "mixed_dataset_versions",
                f"annotations contain dataset versions {versions}",
            )
        )
        return "mixed"
    return "unknown"


def _model_version(
    predictions: list[VisionPrediction], warnings: list[EvaluationIssue]
) -> str:
    versions = sorted({item.model_version for item in predictions})
    if len(versions) == 1:
        return versions[0]
    if len(versions) > 1:
        warnings.append(
            EvaluationIssue(
                "mixed_model_versions",
                f"predictions contain model versions {versions}",
            )
        )
        return "mixed"
    return "unknown"


def _evaluate_classification(
    pairs: list[tuple[GroundTruthAnnotation, VisionPrediction]],
    confidence_threshold: float,
) -> dict[str, ClassificationMetrics]:
    tasks = sorted(
        {task for truth, _ in pairs for task in truth.classifications}
    )
    result: dict[str, ClassificationMetrics] = {}
    for task in tasks:
        outcomes: list[tuple[str, str]] = []
        for truth, prediction in pairs:
            actual = truth.classifications.get(task)
            if actual is None:
                continue
            eligible = [
                item
                for item in prediction.classifications.get(task, ())
                if item.confidence >= confidence_threshold
            ]
            predicted = (
                sorted(eligible, key=lambda item: (-item.confidence, item.label))[0].label
                if eligible
                else _NONE_LABEL
            )
            outcomes.append((actual, predicted))
        result[task] = _classification_metrics(outcomes)
    return result


def _classification_metrics(
    outcomes: list[tuple[str, str]],
    *,
    label_order: tuple[str, ...] | None = None,
) -> ClassificationMetrics:
    labels = (
        list(label_order)
        if label_order is not None
        else sorted(
            {label for outcome in outcomes for label in outcome if label != _NONE_LABEL}
        )
    )
    confusion: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for actual, predicted in outcomes:
        confusion[actual][predicted] += 1
    metrics = {
        label: _label_metrics(label, outcomes)
        for label in labels
    }
    evaluated = len(outcomes)
    correct = sum(actual == predicted for actual, predicted in outcomes)
    return ClassificationMetrics(
        evaluated=evaluated,
        accuracy=_divide(correct, evaluated),
        macro_precision=_mean(item.precision for item in metrics.values()),
        macro_recall=_mean(item.recall for item in metrics.values()),
        macro_f1=_mean(item.f1 for item in metrics.values()),
        confusion_matrix={
            actual: dict(sorted(predicted.items()))
            for actual, predicted in sorted(confusion.items())
        },
        labels=metrics,
    )


def _label_metrics(label: str, outcomes: list[tuple[str, str]]) -> LabelMetrics:
    true_positive = sum(actual == label and predicted == label for actual, predicted in outcomes)
    false_positive = sum(actual != label and predicted == label for actual, predicted in outcomes)
    false_negative = sum(actual == label and predicted != label for actual, predicted in outcomes)
    support = sum(actual == label for actual, _ in outcomes)
    return _metric_counts(true_positive, false_positive, false_negative, support)


def _evaluate_detection(
    pairs: list[tuple[GroundTruthAnnotation, VisionPrediction]],
    *,
    confidence_threshold: float,
    iou_threshold: float,
) -> tuple[DetectionMetrics, SeverityMetrics]:
    label_counts: dict[str, list[int]] = defaultdict(lambda: [0, 0, 0, 0])
    severity_outcomes: list[tuple[str, str]] = []
    ignored_missing = 0

    for truth, prediction in pairs:
        ground = list(truth.detections)
        predicted = sorted(
            (
                item
                for item in prediction.detections
                if item.confidence >= confidence_threshold
            ),
            key=lambda item: (-item.confidence, item.label),
        )
        matched_ground: set[int] = set()
        for candidate in predicted:
            match = _best_detection_match(
                candidate, ground, matched_ground, iou_threshold
            )
            if match is None:
                label_counts[candidate.label][1] += 1
                continue
            matched_ground.add(match)
            actual = ground[match]
            label_counts[candidate.label][0] += 1
            if actual.severity is None or candidate.severity is None:
                ignored_missing += 1
            else:
                severity_outcomes.append((actual.severity, candidate.severity))
        for index, actual in enumerate(ground):
            if index not in matched_ground:
                label_counts[actual.label][2] += 1
            label_counts[actual.label][3] += 1

    labels = {
        label: _metric_counts(*counts)
        for label, counts in sorted(label_counts.items())
    }
    true_positive = sum(item.true_positive for item in labels.values())
    false_positive = sum(item.false_positive for item in labels.values())
    false_negative = sum(item.false_negative for item in labels.values())
    detection = DetectionMetrics(
        precision=_divide(true_positive, true_positive + false_positive),
        recall=_divide(true_positive, true_positive + false_negative),
        f1=_f1(
            _divide(true_positive, true_positive + false_positive),
            _divide(true_positive, true_positive + false_negative),
        ),
        true_positive=true_positive,
        false_positive=false_positive,
        false_negative=false_negative,
        labels=labels,
    )
    severity_classification = _classification_metrics(
        severity_outcomes, label_order=_SEVERITY_LABELS
    )
    severity = SeverityMetrics(
        evaluated=severity_classification.evaluated,
        ignored_missing=ignored_missing,
        accuracy=severity_classification.accuracy,
        macro_precision=severity_classification.macro_precision,
        macro_recall=severity_classification.macro_recall,
        macro_f1=severity_classification.macro_f1,
        confusion_matrix=severity_classification.confusion_matrix,
        labels=severity_classification.labels,
    )
    return detection, severity


def _best_detection_match(
    prediction: DefectDetection,
    ground: list[DefectDetection],
    matched: set[int],
    iou_threshold: float,
) -> int | None:
    candidates = [
        (intersection_over_union(prediction.box, item.box), index)
        for index, item in enumerate(ground)
        if index not in matched and item.label == prediction.label
    ]
    if not candidates:
        return None
    iou, index = max(candidates, key=lambda item: (item[0], -item[1]))
    return index if iou >= iou_threshold else None


def _metric_counts(
    true_positive: int,
    false_positive: int,
    false_negative: int,
    support: int,
) -> LabelMetrics:
    precision = _divide(true_positive, true_positive + false_positive)
    recall = _divide(true_positive, true_positive + false_negative)
    return LabelMetrics(
        precision=precision,
        recall=recall,
        f1=_f1(precision, recall),
        support=support,
        true_positive=true_positive,
        false_positive=false_positive,
        false_negative=false_negative,
    )


def _divide(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 0.0


def _mean(values) -> float:
    items = list(values)
    return sum(items) / len(items) if items else 0.0


def _f1(precision: float, recall: float) -> float:
    return 2 * precision * recall / (precision + recall) if precision + recall else 0.0


def _empty_detection() -> DetectionMetrics:
    return DetectionMetrics(0.0, 0.0, 0.0, 0, 0, 0, {})


def _empty_severity() -> SeverityMetrics:
    classification = _classification_metrics([], label_order=_SEVERITY_LABELS)
    return SeverityMetrics(
        evaluated=0,
        ignored_missing=0,
        accuracy=classification.accuracy,
        macro_precision=classification.macro_precision,
        macro_recall=classification.macro_recall,
        macro_f1=classification.macro_f1,
        confusion_matrix=classification.confusion_matrix,
        labels=classification.labels,
    )
