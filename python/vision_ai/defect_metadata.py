"""Hierarchical defect metadata contracts and review policy."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Mapping

from .models import Classification, VisionPrediction

DEFECT_TASKS = (
    "visual_defect",
    "area",
    "part",
    "part_detail",
    "work_kind",
    "cause",
)


@dataclass(frozen=True, slots=True)
class ConfidencePolicy:
    auto_accept: float = 0.90
    confirm: float = 0.70
    suggest: float = 0.50
    task_thresholds: Mapping[str, float] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not 0 <= self.suggest <= self.confirm <= self.auto_accept <= 1:
            raise ValueError("confidence thresholds must be ordered within [0, 1]")
        if any(task not in DEFECT_TASKS for task in self.task_thresholds):
            raise ValueError("task_thresholds contains an unknown defect task")
        if any(not 0 <= value <= 1 for value in self.task_thresholds.values()):
            raise ValueError("task thresholds must be within [0, 1]")


@dataclass(frozen=True, slots=True)
class DefectTaxonomy:
    """Allowed child labels keyed by ``task:parent-label``."""

    children: Mapping[str, tuple[str, ...]] = field(default_factory=dict)

    def allows(self, parent_task: str, parent: str, child: str) -> bool:
        allowed = self.children.get(f"{parent_task}:{parent}")
        return allowed is None or child in allowed


@dataclass(frozen=True, slots=True)
class MetadataCandidate:
    label: str
    confidence: float


@dataclass(frozen=True, slots=True)
class MetadataField:
    task: str
    value: str | None
    confidence: float
    candidates: tuple[MetadataCandidate, ...]
    status: str


@dataclass(frozen=True, slots=True)
class DefectMetadata:
    image_id: str
    model_version: str
    fields: Mapping[str, MetadataField]
    context: Mapping[str, Any]
    hierarchy_valid: bool
    hierarchy_issues: tuple[str, ...]
    review_required: bool
    review_reasons: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


def build_defect_metadata(
    prediction: VisionPrediction,
    *,
    context: Mapping[str, Any] | None = None,
    policy: ConfidencePolicy | None = None,
    taxonomy: DefectTaxonomy | None = None,
    top_k: int = 3,
) -> DefectMetadata:
    if top_k < 1:
        raise ValueError("top_k must be positive")
    settings = policy or ConfidencePolicy()
    hierarchy = taxonomy or DefectTaxonomy()
    fields: dict[str, MetadataField] = {}
    reasons: list[str] = []
    for task in DEFECT_TASKS:
        ranked = sorted(
            prediction.classifications.get(task, ()),
            key=lambda item: (-item.confidence, item.label),
        )[:top_k]
        field = _field(task, ranked, settings)
        fields[task] = field
        if field.status != "auto_accepted":
            reasons.append(f"{task}:{field.status}")
    issues = _hierarchy_issues(fields, hierarchy)
    if issues:
        reasons.append("hierarchy_violation")
    if not prediction.quality.acceptable:
        reasons.append("image_quality")
    return DefectMetadata(
        image_id=prediction.image_id,
        model_version=prediction.model_version,
        fields=fields,
        context=dict(context or {}),
        hierarchy_valid=not issues,
        hierarchy_issues=issues,
        review_required=bool(reasons),
        review_reasons=tuple(dict.fromkeys(reasons)),
    )


def _field(
    task: str, ranked: list[Classification], policy: ConfidencePolicy
) -> MetadataField:
    candidates = tuple(MetadataCandidate(item.label, item.confidence) for item in ranked)
    if not candidates:
        return MetadataField(task, None, 0, (), "withheld")
    confidence = candidates[0].confidence
    accept_threshold = policy.task_thresholds.get(task, policy.auto_accept)
    if confidence >= accept_threshold:
        status = "auto_accepted"
    elif confidence >= policy.confirm:
        status = "confirmation_required"
    elif confidence >= policy.suggest:
        status = "suggested"
    else:
        status = "withheld"
    value = candidates[0].label if status != "withheld" else None
    return MetadataField(task, value, confidence, candidates, status)


def _hierarchy_issues(
    fields: Mapping[str, MetadataField], taxonomy: DefectTaxonomy
) -> tuple[str, ...]:
    issues = []
    pairs = (("area", "part"), ("part", "part_detail"), ("part_detail", "work_kind"))
    for parent_task, child_task in pairs:
        parent = fields[parent_task].value
        child = fields[child_task].value
        if parent and child and not taxonomy.allows(parent_task, parent, child):
            issues.append(f"{parent_task}:{parent} does not allow {child_task}:{child}")
    return tuple(issues)
