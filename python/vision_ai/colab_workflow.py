"""Portable Google Colab training bundles and verified result imports."""

from __future__ import annotations

import hashlib
import json
import shutil
import zipfile
from pathlib import Path
from typing import Any, Iterable

from data_engineering.io import read_jsonl, write_json, write_jsonl

from .image_io import inspect_image_file


PORTABLE_FORMAT = "apartment-defect-colab-v1"
RESULT_FORMAT = "apartment-defect-training-result-v1"


def export_colab_bundle(
    source_dataset: str | Path,
    training_spec: str | Path,
    output: str | Path,
    *,
    project_root: str | Path,
) -> Path:
    """Create a credential-free bundle with URL-backed training splits."""
    source_root = Path(source_dataset)
    spec_path = Path(training_spec)
    root = Path(project_root)
    destination = Path(output)
    if destination.exists():
        raise FileExistsError(f"Colab bundle already exists: {destination}")

    remote_paths = {
        row["image_id"]: row["image_path"]
        for row in read_jsonl(source_root / "records.jsonl")
        if str(row.get("image_path", "")).startswith(("https://", "http://"))
    }
    spec = json.loads(spec_path.read_text(encoding="utf-8-sig"))
    staging = destination.parent / f".{destination.stem}-staging"
    if staging.exists():
        shutil.rmtree(staging)
    dataset_dir = staging / "dataset"
    project_dir = staging / "project"
    dataset_dir.mkdir(parents=True)
    project_dir.mkdir()
    try:
        counts: dict[str, int] = {}
        spec_dir = spec_path.parent
        for split, relative in spec["split_paths"].items():
            rows = []
            for row in read_jsonl(_resolve(spec_dir, relative)):
                image_id = row["image_id"]
                if image_id not in remote_paths:
                    raise ValueError(f"no public source URL for image_id {image_id}")
                portable = dict(row)
                portable["image_path"] = remote_paths[image_id]
                portable["paired_image_path"] = None
                rows.append(portable)
            write_jsonl(dataset_dir / f"{split}.jsonl", rows)
            counts[split] = len(rows)

        mapping_source = _resolve(spec_dir, spec["label_mapping_path"])
        shutil.copy2(mapping_source, dataset_dir / "label_mapping.json")
        portable_spec = dict(spec)
        portable_spec["split_paths"] = {
            split: f"{split}.jsonl" for split in ("train", "validation", "test")
        }
        portable_spec["label_mapping_path"] = "label_mapping.json"
        portable_spec.update(
            {
                "model_architecture": "convnext_tiny",
                "pretrained": True,
                "learning_rate": 0.0001,
            }
        )
        write_json(dataset_dir / "training_spec.json", portable_spec)

        shutil.copy2(root / "pyproject.toml", project_dir / "pyproject.toml")
        shutil.copytree(
            root / "python",
            project_dir / "python",
            ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".pytest_cache"),
        )
        manifest = {
            "format": PORTABLE_FORMAT,
            "contains_credentials": False,
            "dataset_version": portable_spec["dataset_version"],
            "split_counts": counts,
            "training": {
                "architecture": "convnext_tiny",
                "pretrained": True,
                "learning_rate": 0.0001,
            },
            "files": _checksums(staging),
        }
        write_json(staging / "bundle_manifest.json", manifest)
        destination.parent.mkdir(parents=True, exist_ok=True)
        _zip_directory(staging, destination)
        destination.with_suffix(destination.suffix + ".sha256").write_text(
            f"{_sha256(destination)}  {destination.name}\n", encoding="ascii"
        )
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    return destination


def prepare_colab_dataset(
    bundle_directory: str | Path,
    output: str | Path,
    *,
    workers: int = 16,
    timeout_seconds: float = 45,
) -> Path:
    """Download URL-backed split images and emit a local Colab training spec."""
    import urllib.request
    from concurrent.futures import ThreadPoolExecutor

    if workers < 1:
        raise ValueError("workers must be positive")
    source = Path(bundle_directory) / "dataset"
    destination = Path(output)
    destination.mkdir(parents=True, exist_ok=True)
    images = destination / "images"
    images.mkdir(exist_ok=True)

    def download(row: dict[str, Any]) -> dict[str, Any]:
        url = row["image_path"]
        suffix = Path(url.split("?", 1)[0]).suffix.lower()
        if suffix not in {".jpg", ".jpeg", ".png", ".webp", ".tif", ".tiff"}:
            suffix = ".jpg"
        target = images / f"{hashlib.sha256(row['image_id'].encode()).hexdigest()}{suffix}"
        if not target.exists():
            request = urllib.request.Request(
                url, headers={"User-Agent": "Apartment-Defect-AI-Colab/1.0"}
            )
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                target.write_bytes(response.read())
        inspect_image_file(target)
        updated = dict(row)
        updated["image_path"] = str(target.resolve())
        return updated

    failures: list[dict[str, str]] = []
    counts: dict[str, int] = {}
    for split in ("train", "validation", "test"):
        rows = list(read_jsonl(source / f"{split}.jsonl"))

        def safe_download(row: dict[str, Any]) -> dict[str, Any] | None:
            try:
                return download(row)
            except Exception as exc:
                failures.append(
                    {"image_id": row["image_id"], "error_type": type(exc).__name__}
                )
                return None

        with ThreadPoolExecutor(max_workers=workers) as executor:
            downloaded = [item for item in executor.map(safe_download, rows) if item]
        write_jsonl(destination / f"{split}.jsonl", downloaded)
        counts[split] = len(downloaded)

    shutil.copy2(source / "label_mapping.json", destination / "label_mapping.json")
    spec = json.loads((source / "training_spec.json").read_text(encoding="utf-8"))
    write_json(destination / "training_spec.json", spec)
    write_jsonl(destination / "download_failures.jsonl", failures)
    write_json(
        destination / "colab_dataset_manifest.json",
        {
            "format": PORTABLE_FORMAT,
            "split_counts": counts,
            "failure_count": len(failures),
            "credentials_used": False,
        },
    )
    if not counts.get("train"):
        raise ValueError("no training images were downloaded")
    return destination / "training_spec.json"


def import_colab_result(archive: str | Path, output: str | Path) -> Path:
    """Safely extract a completed Colab run and validate its manifest."""
    source = Path(archive)
    destination = Path(output)
    if destination.exists():
        raise FileExistsError(f"result directory already exists: {destination}")
    checksum_file = source.with_suffix(source.suffix + ".sha256")
    if checksum_file.is_file():
        expected = checksum_file.read_text(encoding="ascii").split()[0].lower()
        if expected != _sha256(source):
            raise ValueError("result archive SHA-256 does not match its sidecar")
    with zipfile.ZipFile(source) as bundle:
        _validate_members(bundle.infolist())
        bundle.extractall(destination)
    manifests = list(destination.rglob("run_manifest.json"))
    if len(manifests) != 1:
        shutil.rmtree(destination)
        raise ValueError("result archive must contain exactly one run_manifest.json")
    manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
    if manifest.get("status") != "completed":
        shutil.rmtree(destination)
        raise ValueError("Colab training run is not completed")
    required = {"final_metrics.json", "training_spec.json", "label_mapping.json"}
    present = {path.name for path in manifests[0].parent.iterdir()}
    missing = sorted(required - present)
    if missing:
        shutil.rmtree(destination)
        raise ValueError(f"Colab result is missing: {', '.join(missing)}")
    write_json(
        destination / "colab_import_manifest.json",
        {
            "format": RESULT_FORMAT,
            "archive_sha256": _sha256(source),
            "run_manifest": str(manifests[0].relative_to(destination)),
            "status": "verified",
        },
    )
    return manifests[0].parent


def _resolve(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path


def _checksums(root: Path) -> dict[str, str]:
    return {
        path.relative_to(root).as_posix(): _sha256(path)
        for path in sorted(root.rglob("*"))
        if path.is_file()
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _zip_directory(source: Path, destination: Path) -> None:
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as bundle:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                bundle.write(path, path.relative_to(source).as_posix())


def _validate_members(members: Iterable[zipfile.ZipInfo]) -> None:
    for member in members:
        path = Path(member.filename)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError(f"unsafe archive member: {member.filename}")
