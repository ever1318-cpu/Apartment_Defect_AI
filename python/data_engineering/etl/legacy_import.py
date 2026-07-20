"""Convert legacy CSV exports into the canonical image-record format."""

from __future__ import annotations

import csv
import hashlib
from pathlib import Path
from typing import Iterable, Mapping

from ..models import ImageRecord

DEFAULT_COLUMNS = {
    "image_id": "image_id",
    "image_path": "image_path",
    "group_id": "group_id",
    "label": "label",
    "width": "width",
    "height": "height",
    "source": "source",
}


def _identifier(row: Mapping[str, str], path: str) -> str:
    return hashlib.sha256(
        ("|".join(f"{key}={row[key]}" for key in sorted(row)) or path).encode("utf-8")
    ).hexdigest()[:20]


def import_legacy_csv(
    path: str | Path,
    *,
    columns: Mapping[str, str] | None = None,
    source: str = "legacy",
    strict: bool = True,
) -> list[ImageRecord]:
    mapping = {**DEFAULT_COLUMNS, **(columns or {})}
    records: list[ImageRecord] = []
    with Path(path).open("r", encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not reader.fieldnames:
            raise ValueError("legacy CSV has no header")
        for row_number, row in enumerate(reader, 2):
            try:
                image_path = (row.get(mapping["image_path"]) or "").strip()
                label = (row.get(mapping["label"]) or "").strip()
                group_id = (row.get(mapping["group_id"]) or "").strip()
                image_id = (row.get(mapping["image_id"]) or "").strip()
                if not image_id:
                    image_id = _identifier(row, image_path)
                if not group_id:
                    group_id = image_id

                def optional_int(name: str) -> int | None:
                    raw = (row.get(mapping[name]) or "").strip()
                    return int(raw) if raw else None

                records.append(
                    ImageRecord(
                        image_id=image_id,
                        image_path=image_path,
                        group_id=group_id,
                        label=label,
                        width=optional_int("width"),
                        height=optional_int("height"),
                        source=(row.get(mapping["source"]) or source).strip(),
                        metadata={"legacy_row": row_number},
                    )
                )
            except (TypeError, ValueError) as exc:
                if strict:
                    raise ValueError(f"{path}:{row_number}: {exc}") from exc
    return records


def deduplicate_records(records: Iterable[ImageRecord]) -> list[ImageRecord]:
    """Keep first occurrence and reject conflicting duplicate image IDs."""
    unique: dict[str, ImageRecord] = {}
    for record in records:
        previous = unique.get(record.image_id)
        if previous is not None and previous != record:
            raise ValueError(f"conflicting duplicate image_id: {record.image_id}")
        unique.setdefault(record.image_id, record)
    return list(unique.values())
