"""Deterministic Vision AI post-processing."""

from __future__ import annotations

from dataclasses import replace
from typing import Iterable, Mapping, Sequence

from .models import BoundingBox, Classification, DefectDetection


def top_k_classifications(
    predictions: Iterable[Classification],
    *,
    minimum_confidence: float = 0,
    limit: int = 3,
) -> tuple[Classification, ...]:
    if limit <= 0:
        raise ValueError("limit must be positive")
    if not 0 <= minimum_confidence <= 1:
        raise ValueError("minimum_confidence must be between 0 and 1")
    eligible = (item for item in predictions if item.confidence >= minimum_confidence)
    return tuple(sorted(eligible, key=lambda item: (-item.confidence, item.label))[:limit])


def intersection_over_union(first: BoundingBox, second: BoundingBox) -> float:
    width = max(0.0, min(first.x_max, second.x_max) - max(first.x_min, second.x_min))
    height = max(0.0, min(first.y_max, second.y_max) - max(first.y_min, second.y_min))
    intersection = width * height
    union = first.area + second.area - intersection
    return intersection / union if union else 0.0


def non_maximum_suppression(
    detections: Iterable[DefectDetection],
    *,
    confidence_threshold: float = 0.25,
    iou_threshold: float = 0.5,
) -> tuple[DefectDetection, ...]:
    for name, value in (
        ("confidence_threshold", confidence_threshold),
        ("iou_threshold", iou_threshold),
    ):
        if not 0 <= value <= 1:
            raise ValueError(f"{name} must be between 0 and 1")
    ranked = sorted(
        (item for item in detections if item.confidence >= confidence_threshold),
        key=lambda item: (-item.confidence, item.label, item.box.x_min, item.box.y_min),
    )
    kept: list[DefectDetection] = []
    for candidate in ranked:
        if any(
            candidate.label == previous.label
            and intersection_over_union(candidate.box, previous.box) > iou_threshold
            for previous in kept
        ):
            continue
        kept.append(candidate)
    return tuple(kept)


def assign_severity(
    detection: DefectDetection,
    thresholds: Mapping[str, float] | None = None,
) -> DefectDetection:
    values = {"medium": 0.02, "high": 0.10, **(thresholds or {})}
    medium, high = values["medium"], values["high"]
    if not 0 <= medium < high <= 1:
        raise ValueError("severity thresholds must satisfy 0 <= medium < high <= 1")
    area = detection.affected_area
    severity = "high" if area >= high else "medium" if area >= medium else "low"
    return replace(detection, severity=severity)
