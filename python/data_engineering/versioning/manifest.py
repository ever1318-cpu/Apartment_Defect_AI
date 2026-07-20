"""Build and verify content-addressed dataset manifests."""

from __future__ import annotations

import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from typing import Any, Iterable, Mapping

from ..models import ImageRecord


def records_digest(records: Iterable[ImageRecord]) -> str:
    payloads = [
        json.dumps(record.to_dict(), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        for record in records
    ]
    digest = hashlib.sha256()
    for payload in sorted(payloads):
        digest.update(payload.encode("utf-8"))
        digest.update(b"\n")
    return digest.hexdigest()


def build_manifest(
    records: Iterable[ImageRecord],
    *,
    version: str,
    pipeline: Mapping[str, Any] | None = None,
    created_at: str | None = None,
) -> dict[str, Any]:
    items = list(records)
    return {
        "schema_version": "1.0",
        "dataset_version": version,
        "created_at": created_at or datetime.now(timezone.utc).isoformat(),
        "record_count": len(items),
        "content_sha256": records_digest(items),
        "labels": dict(sorted(Counter(record.label for record in items).items())),
        "splits": dict(
            sorted(Counter(record.split or "unassigned" for record in items).items())
        ),
        "pipeline": dict(pipeline or {}),
    }


def verify_manifest(manifest: Mapping[str, Any], records: Iterable[ImageRecord]) -> bool:
    items = list(records)
    return (
        manifest.get("record_count") == len(items)
        and manifest.get("content_sha256") == records_digest(items)
    )
