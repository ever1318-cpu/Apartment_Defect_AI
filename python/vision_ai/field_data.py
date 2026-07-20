"""Offline field-data ingestion, curation, labeling QA, and versioning."""

from __future__ import annotations

import hashlib
import json
import math
import os
import shutil
import struct
import tempfile
import zlib
from collections import Counter, defaultdict
from dataclasses import asdict, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

from data_engineering.io import read_jsonl, write_json, write_jsonl
from data_engineering.models import ImageRecord, SplitRatios
from data_engineering.splitters.group_stratified import group_stratified_split

from .field_data_models import (
    AnnotationIssue,
    AnnotationQAReport,
    AnnotationRevision,
    DuplicateGroup,
    IngestedImage,
    LabelingTask,
    PrivacyMask,
    QualityResult,
)
from .image_io import inspect_image_file


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def ingest_images(
    source: str | Path,
    output_directory: str | Path,
    *,
    source_batch: str,
    operator: str = "unknown",
    device_metadata: Mapping[str, Any] | None = None,
    ingested_at: str | None = None,
) -> tuple[IngestedImage, ...]:
    source_path = Path(source).resolve()
    output = Path(output_directory)
    if output.exists():
        raise FileExistsError(f"ingestion output already exists: {output}")
    if not source_batch.strip():
        raise ValueError("source_batch cannot be empty")
    files = _source_files(source_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = Path(tempfile.mkdtemp(prefix=f".{output.name}.tmp-", dir=output.parent))
    records: list[IngestedImage] = []
    errors: list[dict[str, Any]] = []
    seen: set[str] = set()
    timestamp = ingested_at or utc_now()
    try:
        (temp / "images").mkdir()
        for path, relative in files:
            try:
                _reject_unsafe_source(path, source_path if source_path.is_dir() else path.parent)
                info = inspect_image_file(path)
                digest = _file_sha256(path)
                if digest in seen:
                    raise ValueError("duplicate content ID in source batch")
                seen.add(digest)
                stored = Path("images") / f"{digest}{path.suffix.lower()}"
                shutil.copyfile(path, temp / stored)
                records.append(
                    IngestedImage(
                        image_id=digest,
                        content_sha256=digest,
                        stored_path=stored.as_posix(),
                        original_filename=path.name,
                        original_relative_path=relative.as_posix(),
                        format=info.format,
                        size_bytes=info.size_bytes,
                        ingested_at=timestamp,
                        source_batch=source_batch,
                        operator=operator,
                        device_metadata=dict(device_metadata or {}),
                    )
                )
            except Exception as exc:
                errors.append(
                    {
                        "path": relative.as_posix(),
                        "error_type": type(exc).__name__,
                        "message": str(exc),
                    }
                )
        records.sort(key=lambda item: item.image_id)
        write_jsonl(temp / "images.jsonl", (item.to_dict() for item in records))
        write_jsonl(temp / "errors.jsonl", errors)
        write_json(
            temp / "ingestion_manifest.json",
            {
                "format_version": "1.0",
                "source_batch": source_batch,
                "operator": operator,
                "ingested_at": timestamp,
                "record_count": len(records),
                "error_count": len(errors),
                "records_file": "images.jsonl",
                "errors_file": "errors.jsonl",
            },
        )
        os.replace(temp, output)
        return tuple(records)
    except Exception:
        shutil.rmtree(temp, ignore_errors=True)
        raise


def check_image_quality(
    records: Iterable[IngestedImage],
    *,
    root: str | Path,
    max_dimension: int = 16_384,
    min_dimension: int = 64,
    max_bytes: int = 50 * 1024 * 1024,
    min_brightness: float = 0.08,
    max_brightness: float = 0.92,
) -> tuple[QualityResult, ...]:
    base = Path(root).resolve()
    results = []
    for record in records:
        issues: list[str] = []
        width = height = None
        brightness = contrast = blur = None
        try:
            path = _safe_join(base, record.stored_path)
            info = inspect_image_file(path)
            width, height = _image_dimensions(path, info.format)
            brightness, contrast, blur = _pixel_metrics(path)
            if info.size_bytes > max_bytes:
                issues.append("file_too_large")
            if width is not None and height is not None:
                if max(width, height) > max_dimension:
                    issues.append("dimension_limit_exceeded")
                if min(width, height) < min_dimension:
                    issues.append("low_resolution")
                ratio = max(width / height, height / width)
                if ratio > 4:
                    issues.append("extreme_aspect_ratio")
            if brightness is not None:
                if brightness < min_brightness:
                    issues.append("underexposed")
                elif brightness > max_brightness:
                    issues.append("overexposed")
            if contrast is not None and contrast < 0.03:
                issues.append("low_contrast")
            if blur is not None and blur < 0.002:
                issues.append("blurred")
            fatal = {"file_too_large", "dimension_limit_exceeded"}
            status = "fail" if fatal.intersection(issues) else "warning" if issues else "pass"
        except Exception as exc:
            issues = [f"corrupt_or_unsupported:{type(exc).__name__}"]
            status = "fail"
        results.append(
            QualityResult(
                record.image_id,
                status,
                width,
                height,
                record.size_bytes,
                width / height if width and height else None,
                blur,
                brightness,
                contrast,
                tuple(issues),
            )
        )
    return tuple(results)


def find_duplicates(
    records: Iterable[IngestedImage],
    *,
    root: str | Path,
    similarity_threshold: float = 0.92,
) -> tuple[DuplicateGroup, ...]:
    if not 0 <= similarity_threshold <= 1:
        raise ValueError("similarity_threshold must be between zero and one")
    items = sorted(records, key=lambda item: item.image_id)
    exact: dict[str, list[str]] = defaultdict(list)
    for item in items:
        exact[item.content_sha256].append(item.image_id)
    groups: list[DuplicateGroup] = []
    assigned: set[str] = set()
    for digest, image_ids in sorted(exact.items()):
        if len(image_ids) > 1:
            ids = tuple(sorted(image_ids))
            groups.append(DuplicateGroup(f"exact-{digest[:12]}", "exact", ids[0], ids, 1.0))
            assigned.update(ids)
    hashes = {
        item.image_id: _perceptual_fallback(_safe_join(Path(root).resolve(), item.stored_path))
        for item in items
        if item.image_id not in assigned
    }
    remaining = sorted(hashes)
    while remaining:
        canonical = remaining.pop(0)
        members = [canonical]
        similarities = [1.0]
        for candidate in tuple(remaining):
            similarity = _hash_similarity(hashes[canonical], hashes[candidate])
            if similarity >= similarity_threshold:
                remaining.remove(candidate)
                members.append(candidate)
                similarities.append(similarity)
        if len(members) > 1:
            groups.append(
                DuplicateGroup(
                    f"near-{canonical[:12]}",
                    "near",
                    canonical,
                    tuple(members),
                    min(similarities),
                )
            )
    return tuple(groups)


def create_masked_derivative(
    record: IngestedImage,
    masks: Sequence[PrivacyMask],
    output_directory: str | Path,
    *,
    root: str | Path,
    transformer: Callable[[Path, Path, Sequence[PrivacyMask]], None] | None = None,
) -> Path:
    if any(mask.image_id != record.image_id for mask in masks):
        raise ValueError("privacy masks must target the derivative image")
    if not masks or any(mask.status != "approved" for mask in masks):
        raise ValueError("all privacy masks must be approved")
    source = _safe_join(Path(root).resolve(), record.stored_path)
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    target = output / f"{record.image_id}-masked{source.suffix.lower()}"
    if target.exists():
        raise FileExistsError(f"masked derivative already exists: {target}")
    if transformer is None:
        transformer = _pillow_mask_transformer
    transformer(source, target, masks)
    return target


def create_labeling_tasks(
    records: Iterable[IngestedImage],
    task_types: Sequence[str],
    *,
    instructions_version: str,
    label_vocabulary_version: str,
    assignee: str | None = None,
    priority: int = 0,
    created_at: str | None = None,
) -> tuple[LabelingTask, ...]:
    timestamp = created_at or utc_now()
    tasks = []
    seen = set()
    for record in sorted(records, key=lambda item: item.image_id):
        for task_type in task_types:
            task_id = f"{record.image_id}:{task_type}"
            if task_id in seen:
                raise ValueError(f"duplicate labeling task: {task_id}")
            seen.add(task_id)
            tasks.append(
                LabelingTask(
                    task_id,
                    record.image_id,
                    task_type,
                    "pending",
                    priority,
                    timestamp,
                    timestamp,
                    record.source_batch,
                    instructions_version,
                    label_vocabulary_version,
                    assignee,
                )
            )
    return tuple(tasks)


def validate_annotations(
    revisions: Iterable[AnnotationRevision],
    *,
    label_vocabulary: Mapping[str, Sequence[str]] | None = None,
) -> AnnotationQAReport:
    items = list(revisions)
    issues: list[AnnotationIssue] = []
    distribution: Counter[str] = Counter()
    seen: set[tuple[str, int]] = set()
    latest: dict[str, AnnotationRevision] = {}
    for revision in items:
        key = (revision.image_id, revision.revision)
        if key in seen:
            issues.append(AnnotationIssue("duplicate_revision", "error", "duplicate annotation revision", revision.image_id))
        seen.add(key)
        if revision.image_id not in latest or revision.revision > latest[revision.image_id].revision:
            latest[revision.image_id] = revision
        annotation = revision.annotation
        if not annotation.classifications and not annotation.detections:
            issues.append(AnnotationIssue("empty_annotation", "warning", "annotation contains no labels", revision.image_id))
        for task, label in annotation.classifications.items():
            distribution[f"{task}:{label}"] += 1
            allowed = set((label_vocabulary or {}).get(task, ()))
            if allowed and label not in allowed:
                issues.append(AnnotationIssue("unknown_label", "error", f"unknown {task} label {label}", revision.image_id))
        detection_seen = set()
        for detection in annotation.detections:
            signature = (detection.label, asdict(detection.box))
            encoded = json.dumps(signature, sort_keys=True)
            if encoded in detection_seen:
                issues.append(AnnotationIssue("duplicate_annotation", "warning", "duplicate detection", revision.image_id))
            detection_seen.add(encoded)
            distribution[f"detection:{detection.label}"] += 1
            if detection.severity not in {"low", "medium", "high"}:
                issues.append(AnnotationIssue("invalid_severity", "error", "detection severity is required", revision.image_id))
            if detection.mask is not None and _self_intersects(detection.mask.points):
                issues.append(AnnotationIssue("invalid_polygon", "error", "polygon self-intersects", revision.image_id))
        if revision.status == "approved" and not revision.reviewer:
            issues.append(AnnotationIssue("reviewer_missing", "error", "approved annotation lacks reviewer", revision.image_id))
    agreement = _agreement(items)
    return AnnotationQAReport(
        not any(item.severity == "error" for item in issues),
        tuple(issues),
        dict(sorted(distribution.items())),
        agreement,
    )


def build_dataset_version(
    ingestion_directory: str | Path,
    annotation_directory: str | Path,
    output_directory: str | Path,
    *,
    version: str,
    seed: int = 42,
    privacy_mode: str = "raw",
) -> Path:
    ingestion_root = Path(ingestion_directory)
    output = Path(output_directory)
    if output.exists():
        raise FileExistsError(f"dataset version already exists: {output}")
    if privacy_mode not in {"raw", "masked"}:
        raise ValueError("privacy_mode must be raw or masked")
    records = _load_ingested_tree(ingestion_root)
    revisions = _load_revisions(Path(annotation_directory))
    qa = validate_annotations(revisions)
    if not qa.valid:
        raise ValueError("annotation QA failed")
    latest: dict[str, AnnotationRevision] = {}
    for item in sorted(revisions, key=lambda value: value.revision):
        latest[item.image_id] = item
    approved = {
        image_id: item
        for image_id, item in latest.items()
        if item.status == "approved"
    }
    duplicate_ids = _duplicate_exclusions(ingestion_root)
    included: list[ImageRecord] = []
    annotations = []
    exclusions = []
    source_batches = set()
    selected_sources: dict[str, Path] = {}
    for record in sorted(records, key=lambda item: item.image_id):
        reason = None
        revision = approved.get(record.image_id)
        if revision is None:
            reason = "annotation_not_approved"
        elif record.image_id in duplicate_ids:
            reason = "non_canonical_duplicate"
        source_path = _safe_join(ingestion_root.resolve(), record.stored_path)
        if privacy_mode == "masked":
            candidate = ingestion_root / "masked" / f"{record.image_id}-masked{Path(record.stored_path).suffix}"
            if not candidate.is_file():
                reason = reason or "privacy_derivative_missing"
            else:
                source_path = candidate
        if reason:
            exclusions.append({"image_id": record.image_id, "reason": reason})
            continue
        source_batches.add(record.source_batch)
        exported_path = Path("images") / f"{record.image_id}{source_path.suffix.lower()}"
        selected_sources[record.image_id] = source_path
        included.append(
            ImageRecord(
                record.image_id,
                exported_path.as_posix(),
                record.source_batch,
                next(iter(revision.annotation.classifications.values()), "defect"),
            )
        )
        annotations.append(revision.annotation)
    if not included:
        raise ValueError("dataset version has no eligible samples")
    splits = group_stratified_split(included, SplitRatios(), seed=seed)
    split_records = [
        item for split in ("train", "validation", "test") for item in splits[split]
    ]
    output.mkdir(parents=True)
    (output / "images").mkdir()
    for record in split_records:
        shutil.copyfile(selected_sources[record.image_id], output / record.image_path)
    write_jsonl(output / "records.jsonl", (item.to_dict() for item in split_records))
    write_jsonl(output / "annotations.jsonl", (item.to_dict() for item in annotations))
    manifest = {
        "format_version": "1.0",
        "dataset_version": version,
        "created_at": utc_now(),
        "seed": seed,
        "privacy_mode": privacy_mode,
        "source_batches": sorted(source_batches),
        "included_count": len(included),
        "excluded": exclusions,
        "split_counts": {name: len(values) for name, values in splits.items()},
        "label_distribution": _approved_label_distribution(annotations),
        "quality_distribution": _quality_distribution(ingestion_root),
        "records_file": "records.jsonl",
        "annotations_file": "annotations.jsonl",
        "lineage": {
            "ingestion_directory": os.path.relpath(ingestion_root.resolve(), output.resolve()).replace("\\", "/"),
            "annotation_directory": os.path.relpath(Path(annotation_directory).resolve(), output.resolve()).replace("\\", "/"),
        },
    }
    path = output / "dataset_version_manifest.json"
    write_json(path, manifest)
    return path


def _source_files(source: Path) -> list[tuple[Path, Path]]:
    if source.is_dir():
        return [(path, path.relative_to(source)) for path in sorted(source.rglob("*")) if path.is_file() or path.is_symlink()]
    if source.suffix.lower() == ".jsonl":
        base = source.parent.resolve()
        values = []
        for item in read_jsonl(source):
            relative = Path(item["image_path"])
            values.append((_safe_join(base, relative.as_posix()), relative))
        return values
    raise ValueError("source must be an image directory or JSONL manifest")


def _reject_unsafe_source(path: Path, root: Path) -> None:
    if path.is_symlink():
        raise ValueError("symbolic links are not accepted")
    resolved = path.resolve()
    try:
        resolved.relative_to(root.resolve())
    except ValueError as exc:
        raise ValueError("source path escapes ingestion root") from exc


def _safe_join(root: Path, relative: str) -> Path:
    candidate = Path(relative)
    if candidate.is_absolute():
        raise ValueError("absolute paths are not accepted")
    joined = root / candidate
    if joined.is_symlink():
        raise ValueError("symbolic links are not accepted")
    resolved = joined.resolve()
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise ValueError("path traversal is not accepted") from exc
    return resolved


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _image_dimensions(path: Path, format_name: str) -> tuple[int | None, int | None]:
    with path.open("rb") as stream:
        data = stream.read(32)
    if format_name == "png":
        if len(data) < 24 or data[12:16] != b"IHDR":
            raise ValueError("PNG header is malformed")
        width = int.from_bytes(data[16:20], "big")
        height = int.from_bytes(data[20:24], "big")
        if width <= 0 or height <= 0:
            raise ValueError("PNG dimensions are invalid")
        return width, height
    try:
        from PIL import Image
        with Image.open(path) as image:
            image.verify()
        with Image.open(path) as image:
            return image.size
    except ImportError:
        return None, None


def _pixel_metrics(path: Path) -> tuple[float | None, float | None, float | None]:
    try:
        from PIL import Image
    except ImportError:
        values = _png_luminance(path)
        if values is None:
            return None, None, None
    else:
        with Image.open(path) as image:
            grayscale = image.convert("L").resize((64, 64))
            values = list(grayscale.getdata())
    mean = sum(values) / len(values)
    variance = sum((value - mean) ** 2 for value in values) / len(values)
    edges = [
        abs(values[index] - values[index - 1])
        for index in range(1, len(values))
        if index % 64
    ]
    return mean / 255, math.sqrt(variance) / 255, sum(edges) / max(len(edges), 1) / 255


def _png_luminance(path: Path) -> list[int] | None:
    """Decode common non-interlaced 8-bit PNG scanlines without Pillow."""
    data = path.read_bytes()
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return None
    offset = 8
    compressed = bytearray()
    width = height = color_type = bit_depth = interlace = None
    while offset + 12 <= len(data):
        length = struct.unpack(">I", data[offset : offset + 4])[0]
        name = data[offset + 4 : offset + 8]
        payload = data[offset + 8 : offset + 8 + length]
        if len(payload) != length:
            raise ValueError("PNG chunk is truncated")
        if name == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(
                ">IIBBBBB", payload
            )
        elif name == b"IDAT":
            compressed.extend(payload)
        elif name == b"IEND":
            break
        offset += 12 + length
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color_type)
    if not width or not height or bit_depth != 8 or interlace != 0 or channels is None:
        return None
    raw = zlib.decompress(bytes(compressed))
    stride = width * channels
    if len(raw) != height * (stride + 1):
        raise ValueError("PNG pixel payload length is invalid")
    rows: list[bytearray] = []
    cursor = 0
    for _ in range(height):
        filter_type = raw[cursor]
        source = raw[cursor + 1 : cursor + 1 + stride]
        cursor += stride + 1
        prior = rows[-1] if rows else bytearray(stride)
        row = bytearray(stride)
        for index, value in enumerate(source):
            left = row[index - channels] if index >= channels else 0
            up = prior[index]
            upper_left = prior[index - channels] if index >= channels else 0
            if filter_type == 0:
                predictor = 0
            elif filter_type == 1:
                predictor = left
            elif filter_type == 2:
                predictor = up
            elif filter_type == 3:
                predictor = (left + up) // 2
            elif filter_type == 4:
                predictor = _paeth(left, up, upper_left)
            else:
                raise ValueError("unsupported PNG filter")
            row[index] = (value + predictor) & 0xFF
        rows.append(row)
    values = []
    for row in rows:
        for index in range(0, len(row), channels):
            if color_type in {0, 4}:
                values.append(row[index])
            else:
                red, green, blue = row[index : index + 3]
                values.append(round(0.299 * red + 0.587 * green + 0.114 * blue))
    return values


def _paeth(left: int, up: int, upper_left: int) -> int:
    estimate = left + up - upper_left
    distances = (
        (abs(estimate - left), left),
        (abs(estimate - up), up),
        (abs(estimate - upper_left), upper_left),
    )
    return min(distances)[1]


def _perceptual_fallback(path: Path) -> int:
    try:
        from PIL import Image
        with Image.open(path) as image:
            values = list(image.convert("L").resize((9, 8)).getdata())
        return sum(
            (values[row * 9 + column] > values[row * 9 + column + 1]) << (row * 8 + column)
            for row in range(8)
            for column in range(8)
        )
    except (ImportError, OSError):
        data = path.read_bytes()
        sample = [data[min(len(data) - 1, index * len(data) // 65)] for index in range(65)]
        return sum((sample[index] > sample[index + 1]) << index for index in range(64))


def _hash_similarity(left: int, right: int) -> float:
    return 1 - (left ^ right).bit_count() / 64


def _pillow_mask_transformer(source: Path, target: Path, masks: Sequence[PrivacyMask]) -> None:
    try:
        from PIL import Image, ImageDraw
    except ImportError as exc:
        raise RuntimeError("privacy derivatives require Pillow or an injected transformer") from exc
    with Image.open(source) as image:
        output = image.copy()
        draw = ImageDraw.Draw(output)
        for mask in masks:
            points = [(x * output.width, y * output.height) for x, y in mask.polygon]
            draw.polygon(points, fill=(0, 0, 0))
        output.save(target)


def _self_intersects(points: Sequence[tuple[float, float]]) -> bool:
    def orientation(a, b, c):
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
    edges = list(zip(points, (*points[1:], points[0])))
    for index, (a, b) in enumerate(edges):
        for other, (c, d) in enumerate(edges):
            if abs(index - other) <= 1 or {index, other} == {0, len(edges) - 1}:
                continue
            if orientation(a, b, c) * orientation(a, b, d) < 0 and orientation(c, d, a) * orientation(c, d, b) < 0:
                return True
    return False


def _agreement(items: Sequence[AnnotationRevision]) -> float | None:
    by_image: dict[str, list[AnnotationRevision]] = defaultdict(list)
    for item in items:
        by_image[item.image_id].append(item)
    scores = []
    for revisions in by_image.values():
        annotators = {item.annotator for item in revisions}
        if len(annotators) < 2:
            continue
        signatures = [
            (tuple(sorted(item.annotation.classifications.items())), tuple(sorted(d.label for d in item.annotation.detections)))
            for item in revisions
        ]
        scores.append(sum(value == signatures[0] for value in signatures[1:]) / (len(signatures) - 1))
    return sum(scores) / len(scores) if scores else None


def _load_ingested_tree(root: Path) -> list[IngestedImage]:
    paths = [root / "images.jsonl"] if (root / "images.jsonl").is_file() else sorted(root.glob("*/images.jsonl"))
    return [IngestedImage.from_dict(item) for path in paths for item in read_jsonl(path)]


def _load_revisions(root: Path) -> list[AnnotationRevision]:
    paths = [root] if root.is_file() else sorted(root.glob("*.jsonl"))
    return [AnnotationRevision.from_dict(item) for path in paths for item in read_jsonl(path)]


def _duplicate_exclusions(root: Path) -> set[str]:
    path = root / "duplicate_groups.json"
    if not path.is_file():
        return set()
    value = json.loads(path.read_text(encoding="utf-8-sig"))
    return {
        image_id
        for group in value.get("groups", ())
        for image_id in group["image_ids"]
        if image_id != group["canonical_image_id"]
    }


def _quality_distribution(root: Path) -> dict[str, int]:
    path = root / "quality.jsonl"
    return dict(sorted(Counter(item["status"] for item in read_jsonl(path)).items())) if path.is_file() else {}


def _approved_label_distribution(annotations: Sequence[Any]) -> dict[str, int]:
    values: Counter[str] = Counter()
    for annotation in annotations:
        for task, label in annotation.classifications.items():
            values[f"{task}:{label}"] += 1
        for detection in annotation.detections:
            values[f"detection:{detection.label}"] += 1
            if detection.severity:
                values[f"severity:{detection.severity}"] += 1
    return dict(sorted(values.items()))
