"""Serializable contracts for field-image ingestion and annotation operations."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Mapping

from .evaluation_models import GroundTruthAnnotation


@dataclass(frozen=True, slots=True)
class IngestedImage:
    image_id: str
    content_sha256: str
    stored_path: str
    original_filename: str
    original_relative_path: str
    format: str
    size_bytes: int
    ingested_at: str
    source_batch: str
    operator: str = "unknown"
    device_metadata: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if len(self.content_sha256) != 64:
            raise ValueError("content_sha256 must be a SHA-256 hex digest")
        for value in (self.stored_path, self.original_relative_path):
            if value.startswith(("/", "\\")) or ":" in value or ".." in value.split("/"):
                raise ValueError("ingestion paths must be safe relative paths")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "IngestedImage":
        return cls(**dict(value))


@dataclass(frozen=True, slots=True)
class QualityResult:
    image_id: str
    status: str
    width: int | None
    height: int | None
    size_bytes: int
    aspect_ratio: float | None
    blur_score: float | None
    brightness: float | None
    contrast: float | None
    issues: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.status not in {"pass", "warning", "fail"}:
            raise ValueError("quality status must be pass, warning, or fail")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["issues"] = list(self.issues)
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "QualityResult":
        return cls(**{**dict(value), "issues": tuple(value.get("issues", ()))})


@dataclass(frozen=True, slots=True)
class DuplicateGroup:
    group_id: str
    kind: str
    canonical_image_id: str
    image_ids: tuple[str, ...]
    similarity: float
    policy: str = "exclude_non_canonical"

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["image_ids"] = list(self.image_ids)
        return value


@dataclass(frozen=True, slots=True)
class PrivacyMask:
    mask_id: str
    image_id: str
    category: str
    polygon: tuple[tuple[float, float], ...]
    provenance: str
    created_by: str
    created_at: str
    status: str = "pending_review"
    derivative_path: str | None = None

    def __post_init__(self) -> None:
        if self.category not in {"face", "license_plate", "document", "name_tag", "other"}:
            raise ValueError("unsupported privacy mask category")
        if len(self.polygon) < 3 or any(
            not 0 <= coordinate <= 1 for point in self.polygon for coordinate in point
        ):
            raise ValueError("privacy polygon must have normalized coordinates")
        if self.status not in {"pending_review", "approved", "rejected"}:
            raise ValueError("invalid privacy mask status")
        if self.derivative_path is not None and (
            self.derivative_path.startswith(("/", "\\"))
            or ":" in self.derivative_path
            or ".." in self.derivative_path.replace("\\", "/").split("/")
        ):
            raise ValueError("privacy derivative path must be relative and traversal-safe")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["polygon"] = [list(point) for point in self.polygon]
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "PrivacyMask":
        return cls(
            **{
                **dict(value),
                "polygon": tuple(tuple(point) for point in value["polygon"]),
            }
        )


TASK_TYPES = (
    "classification",
    "detection",
    "segmentation",
    "severity",
    "privacy_mask_review",
)
TASK_STATUSES = ("pending", "in_progress", "submitted", "approved", "rejected")


@dataclass(frozen=True, slots=True)
class LabelingTask:
    task_id: str
    image_id: str
    task_type: str
    status: str
    priority: int
    created_at: str
    updated_at: str
    source_batch: str
    instructions_version: str
    label_vocabulary_version: str
    assignee: str | None = None

    def __post_init__(self) -> None:
        if self.task_type not in TASK_TYPES or self.status not in TASK_STATUSES:
            raise ValueError("invalid labeling task type or status")
        if self.priority < 0:
            raise ValueError("priority cannot be negative")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "LabelingTask":
        return cls(**dict(value))


@dataclass(frozen=True, slots=True)
class AnnotationRevision:
    annotation: GroundTruthAnnotation
    status: str
    annotator: str
    reviewer: str | None
    confidence: float
    notes: str
    revision: int
    created_at: str
    updated_at: str
    rejected_reason: str | None = None
    audit_history: tuple[Mapping[str, Any], ...] = ()

    def __post_init__(self) -> None:
        if self.status not in {"submitted", "approved", "rejected"}:
            raise ValueError("invalid annotation revision status")
        if not 0 <= self.confidence <= 1 or self.revision <= 0:
            raise ValueError("invalid annotation confidence or revision")
        if self.status == "approved" and not self.reviewer:
            raise ValueError("approved annotation requires a reviewer")
        if self.status == "rejected" and not self.rejected_reason:
            raise ValueError("rejected annotation requires a reason")

    @property
    def image_id(self) -> str:
        return self.annotation.image_id

    def to_dict(self) -> dict[str, Any]:
        return {
            "annotation": self.annotation.to_dict(),
            "status": self.status,
            "annotator": self.annotator,
            "reviewer": self.reviewer,
            "confidence": self.confidence,
            "notes": self.notes,
            "revision": self.revision,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "rejected_reason": self.rejected_reason,
            "audit_history": [dict(item) for item in self.audit_history],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "AnnotationRevision":
        return cls(
            annotation=GroundTruthAnnotation.from_dict(value["annotation"]),
            status=value["status"],
            annotator=value["annotator"],
            reviewer=value.get("reviewer"),
            confidence=float(value["confidence"]),
            notes=value.get("notes", ""),
            revision=int(value["revision"]),
            created_at=value["created_at"],
            updated_at=value["updated_at"],
            rejected_reason=value.get("rejected_reason"),
            audit_history=tuple(value.get("audit_history", ())),
        )


@dataclass(frozen=True, slots=True)
class AnnotationIssue:
    code: str
    severity: str
    message: str
    image_id: str


@dataclass(frozen=True, slots=True)
class AnnotationQAReport:
    valid: bool
    issues: tuple[AnnotationIssue, ...]
    label_distribution: Mapping[str, int]
    agreement: float | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "issues": [asdict(item) for item in self.issues],
            "label_distribution": dict(self.label_distribution),
            "agreement": self.agreement,
        }
