"""Build canonical, leakage-safe records from defect/image metadata exports."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from typing import Any, Iterable, Mapping

from data_engineering.models import ImageRecord

from .evaluation_models import GroundTruthAnnotation

LABEL_COLUMNS = {
    "area": ("area", "실"),
    "part": ("part", "부위"),
    "part_detail": ("part_detail", "상세부위"),
    "work_kind": ("work_kind", "공종"),
    "cause": ("cause", "하자원인"),
}
IMAGE_COLUMNS = ("image_uri", "photo_url", "full_path", "original_full_path")


@dataclass(frozen=True, slots=True)
class DefectDatasetItem:
    record: ImageRecord
    annotation: GroundTruthAnnotation


def defect_rows_to_dataset(
    rows: Iterable[Mapping[str, Any]], *, dataset_version: str
) -> tuple[DefectDatasetItem, ...]:
    if not dataset_version.strip():
        raise ValueError("dataset_version cannot be empty")
    items = []
    seen_images: set[str] = set()
    for number, row in enumerate(rows, 1):
        defect_id = _required(row, "defect_id")
        image_path = _first(row, IMAGE_COLUMNS)
        if image_path is None:
            raise ValueError(f"row {number} has no image path")
        image_id = str(row.get("image_id") or _image_id(defect_id, image_path))
        if image_id in seen_images:
            continue
        seen_images.add(image_id)
        labels = {
            task: value
            for task, columns in LABEL_COLUMNS.items()
            if (value := _first(row, columns)) is not None
        }
        if not labels:
            raise ValueError(f"row {number} has no hierarchical defect labels")
        metadata = {
            key: row[key]
            for key in (
                "site_id", "site_code", "site_name", "dong", "ho", "area",
                "단지명", "동", "호", "실", "requirement",
                "defect_description", "고객민원내용",
            )
            if row.get(key) not in (None, "")
        }
        record = ImageRecord(
            image_id=image_id,
            image_path=image_path,
            group_id=f"defect:{defect_id}",
            label=labels.get("part_detail") or labels.get("part") or next(iter(labels.values())),
            source=str(row.get("source") or "defect-db"),
            metadata={"defect_id": defect_id, **metadata},
        )
        annotation = GroundTruthAnnotation(
            image_id=image_id,
            classifications=labels,
            dataset_version=dataset_version,
        )
        items.append(DefectDatasetItem(record, annotation))
    return tuple(items)


def _required(row: Mapping[str, Any], key: str) -> str:
    value = row.get(key)
    if value in (None, ""):
        raise ValueError(f"{key} is required")
    return str(value)


def _first(row: Mapping[str, Any], columns: tuple[str, ...]) -> str | None:
    for column in columns:
        value = row.get(column)
        if value not in (None, ""):
            return str(value).strip()
    return None


def _image_id(defect_id: str, image_path: str) -> str:
    digest = hashlib.sha256(f"{defect_id}\0{image_path}".encode("utf-8")).hexdigest()[:20]
    return f"defect-image:{digest}"
