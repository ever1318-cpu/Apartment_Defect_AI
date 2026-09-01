"""Deterministic HTTPS image materialization for training."""

from __future__ import annotations

import hashlib
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from pathlib import Path
from typing import Iterable
from urllib.parse import urlparse

from data_engineering.models import ImageRecord

from .image_io import inspect_image_file


def select_training_pilot(
    records: Iterable[ImageRecord], *, max_per_split: int, seed: int = 42
) -> tuple[ImageRecord, ...]:
    if max_per_split < 1:
        raise ValueError("max_per_split must be positive")
    grouped = {"train": [], "validation": [], "test": []}
    for record in records:
        if record.split in grouped:
            grouped[record.split].append(record)
    selected = []
    for split, items in grouped.items():
        ordered = sorted(
            items,
            key=lambda item: hashlib.sha256(
                f"{seed}|{item.group_id}|{item.image_id}".encode()
            ).digest(),
        )
        selected.extend(ordered[:max_per_split])
    return tuple(selected)


def materialize_remote_images(
    records: Iterable[ImageRecord],
    output: str | Path,
    *,
    workers: int = 8,
    timeout_seconds: float = 30,
    max_bytes: int = 20 * 1024 * 1024,
) -> tuple[tuple[ImageRecord, ...], tuple[dict[str, str], ...]]:
    if workers < 1 or timeout_seconds <= 0 or max_bytes < 1:
        raise ValueError("download settings must be positive")
    root = Path(output)
    root.mkdir(parents=True, exist_ok=True)
    items = tuple(records)

    def download(record: ImageRecord):
        parsed = urlparse(record.image_path)
        if parsed.scheme not in {"http", "https"}:
            return record, None
        suffix = Path(parsed.path).suffix.lower()
        if suffix not in {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff"}:
            suffix = ".jpg"
        name = hashlib.sha256(record.image_id.encode()).hexdigest() + suffix
        destination = root / "images" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        try:
            if destination.is_file():
                inspect_image_file(destination)
                return replace(record, image_path=str(destination.resolve())), None
            request = urllib.request.Request(
                record.image_path, headers={"User-Agent": "Apartment-Defect-AI/1.0"}
            )
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                content_length = int(response.headers.get("Content-Length", "0") or 0)
                if content_length > max_bytes:
                    raise ValueError("remote image exceeds maximum size")
                payload = response.read(max_bytes + 1)
            if len(payload) > max_bytes:
                raise ValueError("remote image exceeds maximum size")
            destination.write_bytes(payload)
            inspect_image_file(destination)
            return replace(record, image_path=str(destination.resolve())), None
        except Exception as exc:
            destination.unlink(missing_ok=True)
            return None, {
                "image_id": record.image_id,
                "error_type": type(exc).__name__,
            }

    with ThreadPoolExecutor(max_workers=workers) as executor:
        results = tuple(executor.map(download, items))
    downloaded = tuple(record for record, _ in results if record is not None)
    failures = tuple(error for _, error in results if error is not None)
    return downloaded, failures
