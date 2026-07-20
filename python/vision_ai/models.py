"""Typed contracts for Vision AI inputs and predictions."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Mapping


def _probability(name: str, value: float) -> None:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ValueError(f"{name} must be numeric")
    if not 0 <= value <= 1:
        raise ValueError(f"{name} must be between 0 and 1")


@dataclass(frozen=True, slots=True)
class Classification:
    label: str
    confidence: float

    def __post_init__(self) -> None:
        if not self.label.strip():
            raise ValueError("classification label cannot be empty")
        _probability("confidence", self.confidence)


@dataclass(frozen=True, slots=True)
class BoundingBox:
    """Normalized XYXY bounding box."""

    x_min: float
    y_min: float
    x_max: float
    y_max: float

    def __post_init__(self) -> None:
        for name in ("x_min", "y_min", "x_max", "y_max"):
            _probability(name, getattr(self, name))
        if self.x_min >= self.x_max or self.y_min >= self.y_max:
            raise ValueError("bounding box must have positive area")

    @property
    def area(self) -> float:
        return (self.x_max - self.x_min) * (self.y_max - self.y_min)


@dataclass(frozen=True, slots=True)
class PolygonMask:
    """Normalized polygon with at least three points."""

    points: tuple[tuple[float, float], ...]

    def __post_init__(self) -> None:
        if len(self.points) < 3:
            raise ValueError("polygon must contain at least three points")
        for x, y in self.points:
            _probability("polygon x", x)
            _probability("polygon y", y)

    @property
    def area(self) -> float:
        pairs = zip(self.points, self.points[1:] + self.points[:1])
        return abs(sum(x1 * y2 - x2 * y1 for (x1, y1), (x2, y2) in pairs)) / 2


@dataclass(frozen=True, slots=True)
class DefectDetection:
    label: str
    confidence: float
    box: BoundingBox
    mask: PolygonMask | None = None
    severity: str | None = None

    def __post_init__(self) -> None:
        if not self.label.strip():
            raise ValueError("detection label cannot be empty")
        _probability("confidence", self.confidence)
        if self.severity not in (None, "low", "medium", "high"):
            raise ValueError("severity must be low, medium, high, or null")

    @property
    def affected_area(self) -> float:
        return self.mask.area if self.mask is not None else self.box.area


@dataclass(frozen=True, slots=True)
class ImageQuality:
    score: float
    acceptable: bool
    issues: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        _probability("quality score", self.score)
        if any(not issue.strip() for issue in self.issues):
            raise ValueError("quality issues cannot be empty")


@dataclass(frozen=True, slots=True)
class VisionPrediction:
    image_id: str
    model_version: str
    quality: ImageQuality
    classifications: Mapping[str, tuple[Classification, ...]] = field(default_factory=dict)
    detections: tuple[DefectDetection, ...] = ()
    metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.image_id.strip():
            raise ValueError("image_id cannot be empty")
        if not self.model_version.strip():
            raise ValueError("model_version cannot be empty")
        for task, predictions in self.classifications.items():
            if not task.strip():
                raise ValueError("classification task cannot be empty")
            if not isinstance(predictions, tuple):
                raise ValueError("classification predictions must be tuples")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "VisionPrediction":
        try:
            quality_value = value["quality"]
            classifications = {
                str(task): tuple(Classification(**item) for item in items)
                for task, items in value.get("classifications", {}).items()
            }
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
                        confidence=item["confidence"],
                        box=BoundingBox(**item["box"]),
                        mask=mask,
                        severity=item.get("severity"),
                    )
                )
            return cls(
                image_id=value["image_id"],
                model_version=value["model_version"],
                quality=ImageQuality(
                    score=quality_value["score"],
                    acceptable=quality_value["acceptable"],
                    issues=tuple(quality_value.get("issues", ())),
                ),
                classifications=classifications,
                detections=tuple(detections),
                metadata=dict(value.get("metadata", {})),
            )
        except (KeyError, TypeError) as exc:
            raise ValueError(f"invalid VisionPrediction: {exc}") from exc
