"""Validate canonical records and dataset-level invariants."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from ..models import ImageRecord


@dataclass(frozen=True, slots=True)
class ValidationIssue:
    code: str
    message: str
    image_id: str | None = None


def validate_records(
    records: Iterable[ImageRecord],
    *,
    root: str | Path | None = None,
    require_files: bool = False,
) -> list[ValidationIssue]:
    items = list(records)
    issues: list[ValidationIssue] = []
    ids = Counter(record.image_id for record in items)
    for image_id, count in sorted(ids.items()):
        if count > 1:
            issues.append(ValidationIssue("duplicate_image_id", f"appears {count} times", image_id))

    split_by_group: dict[str, set[str]] = {}
    base = Path(root) if root is not None else None
    for record in items:
        if record.split:
            split_by_group.setdefault(record.group_id, set()).add(record.split)
        if record.suffix not in {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff"}:
            issues.append(
                ValidationIssue("unsupported_extension", record.image_path, record.image_id)
            )
        if require_files:
            candidate = Path(record.image_path)
            if base is not None and not candidate.is_absolute():
                candidate = base / candidate
            if not candidate.is_file():
                issues.append(ValidationIssue("missing_file", str(candidate), record.image_id))

    for group_id, splits in sorted(split_by_group.items()):
        if len(splits) > 1:
            issues.append(
                ValidationIssue(
                    "group_leakage",
                    f"group {group_id!r} occurs in splits {sorted(splits)}",
                )
            )
    return issues


def assert_valid_records(records: Iterable[ImageRecord], **kwargs: object) -> None:
    issues = validate_records(records, **kwargs)
    if issues:
        details = "; ".join(f"{issue.code}: {issue.message}" for issue in issues)
        raise ValueError(f"manifest validation failed: {details}")
