"""Local filesystem model registry with validated immutable package copies."""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path, PurePath
from typing import Any, Mapping

from data_engineering.io import write_json

from .model_package import validate_model_package
from .package_models import ModelManifest

REGISTRY_FORMAT_VERSION = "1.0"
STAGES = ("development", "staging", "production", "archived")


class RegistryCorruptionError(RuntimeError):
    """Raised when neither the primary nor backup index can be decoded."""


@dataclass(frozen=True, slots=True)
class RegistryEntry:
    model_name: str
    model_version: str
    stage: str
    package_path: str
    package_digest: str
    registered_at: str
    updated_at: str
    promoted_at: str | None
    source_training_run_id: str
    dataset_version: str
    model_metadata: Mapping[str, Any] = field(default_factory=dict)
    validation_status: str = "valid"
    compatibility_status: str = "pass"
    notes: str = ""

    def __post_init__(self) -> None:
        _safe_component(self.model_name, "model name")
        _safe_component(self.model_version, "model version")
        if self.stage not in STAGES:
            raise ValueError(f"stage must be one of: {', '.join(STAGES)}")
        expected = f"models/{self.model_name}/{self.model_version}"
        if self.package_path != expected:
            raise ValueError("registry package path is not canonical")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "RegistryEntry":
        return cls(**{**dict(value), "model_metadata": dict(value.get("model_metadata", {}))})


@dataclass(frozen=True, slots=True)
class RegistryIndex:
    registry_format_version: str = REGISTRY_FORMAT_VERSION
    revision: int = 0
    models: tuple[RegistryEntry, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "registry_format_version": self.registry_format_version,
            "revision": self.revision,
            "models": [item.to_dict() for item in self.models],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "RegistryIndex":
        return cls(
            registry_format_version=value["registry_format_version"],
            revision=int(value["revision"]),
            models=tuple(RegistryEntry.from_dict(item) for item in value["models"]),
        )


class ModelRegistry:
    """Own package copies and update a revisioned JSON index atomically."""

    def __init__(
        self,
        directory: str | Path,
        *,
        lock_timeout_seconds: float = 5.0,
        stale_lock_seconds: float = 300.0,
    ):
        self.directory = Path(directory)
        self.index_path = self.directory / "registry.json"
        self.backup_path = self.directory / "registry.json.bak"
        self.models_directory = self.directory / "models"
        self.locks_directory = self.directory / "locks"
        self.lock_timeout_seconds = lock_timeout_seconds
        self.stale_lock_seconds = stale_lock_seconds

    def initialize(self) -> RegistryIndex:
        self.models_directory.mkdir(parents=True, exist_ok=True)
        self.locks_directory.mkdir(parents=True, exist_ok=True)
        if not self.index_path.exists():
            with self._lock():
                if not self.index_path.exists():
                    self._write(RegistryIndex())
        return self.read()

    def read(self) -> RegistryIndex:
        if not self.index_path.is_file():
            return self.initialize()
        try:
            return self._read_index(self.index_path)
        except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as primary:
            try:
                recovered = self._read_index(self.backup_path)
                write_json(self.index_path, recovered.to_dict())
                return recovered
            except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
                raise RegistryCorruptionError(
                    "registry index is corrupted and no valid backup is available"
                ) from primary

    def list(self, model_name: str | None = None) -> tuple[RegistryEntry, ...]:
        values = self.read().models
        if model_name is not None:
            _safe_component(model_name, "model name")
            values = tuple(item for item in values if item.model_name == model_name)
        return tuple(sorted(values, key=lambda item: (item.model_name, item.model_version)))

    def get(self, model_name: str, model_version: str) -> RegistryEntry:
        _safe_component(model_name, "model name")
        _safe_component(model_version, "model version")
        for item in self.read().models:
            if item.model_name == model_name and item.model_version == model_version:
                return item
        raise KeyError(f"model not found: {model_name}/{model_version}")

    def production(self, model_name: str) -> RegistryEntry:
        values = [item for item in self.list(model_name) if item.stage == "production"]
        if len(values) != 1:
            raise KeyError(f"active production model not found: {model_name}")
        return values[0]

    def package_directory(self, entry: RegistryEntry) -> Path:
        relative = PurePath(entry.package_path)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("unsafe registry package path")
        path = self.directory.joinpath(*relative.parts)
        resolved = path.resolve()
        root = self.models_directory.resolve()
        if root != resolved and root not in resolved.parents:
            raise ValueError("registry package path escapes model root")
        return resolved

    def register(
        self,
        package_directory: str | Path,
        model_name: str,
        model_version: str,
        *,
        stage: str = "development",
        notes: str = "",
        expected_revision: int | None = None,
        registered_at: str | None = None,
    ) -> RegistryEntry:
        self.initialize()
        _safe_component(model_name, "model name")
        _safe_component(model_version, "model version")
        _validate_stage(stage)
        source = Path(package_directory)
        validation = validate_model_package(source, strict=True)
        if not validation.valid:
            raise ValueError("invalid model package: " + "; ".join(validation.errors))
        _reject_tree_symlinks(source)
        manifest = ModelManifest.from_dict(
            json.loads((source / "model_manifest.json").read_text(encoding="utf-8"))
        )
        if manifest.model_name != model_name or manifest.model_version != model_version:
            raise ValueError("registry name/version must match the package manifest")
        timestamp = registered_at or datetime.now(timezone.utc).isoformat()
        destination = self.models_directory / model_name / model_version
        with self._lock():
            index = self.read()
            _check_revision(index, expected_revision)
            if any(
                item.model_name == model_name and item.model_version == model_version
                for item in index.models
            ):
                raise FileExistsError(f"model already registered: {model_name}/{model_version}")
            if destination.exists():
                raise FileExistsError(f"registry package already exists: {destination}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            temp = Path(
                tempfile.mkdtemp(prefix=f".{model_version}.tmp-", dir=destination.parent)
            )
            try:
                shutil.copytree(
                    source, temp, dirs_exist_ok=True, symlinks=True
                )
                _reject_tree_symlinks(temp)
                os.replace(temp, destination)
                compatibility = _compatibility_status(validation)
                entry = RegistryEntry(
                    model_name=model_name,
                    model_version=model_version,
                    stage=stage,
                    package_path=f"models/{model_name}/{model_version}",
                    package_digest=_sha256(source / "checksums.json"),
                    registered_at=timestamp,
                    updated_at=timestamp,
                    promoted_at=timestamp if stage == "production" else None,
                    source_training_run_id=manifest.source_training_run_id,
                    dataset_version=manifest.dataset_version,
                    model_metadata={
                        "framework": manifest.framework,
                        "framework_version": manifest.framework_version,
                        "package_format_version": manifest.package_format_version,
                    },
                    compatibility_status=compatibility,
                    notes=notes,
                )
                models = list(index.models)
                if stage == "production":
                    models = _replace_production(models, model_name, timestamp, "staging")
                models.append(entry)
                self._write(
                    RegistryIndex(index.registry_format_version, index.revision + 1, tuple(models))
                )
                return entry
            except Exception:
                shutil.rmtree(temp, ignore_errors=True)
                if destination.exists() and not any(
                    item.model_name == model_name and item.model_version == model_version
                    for item in index.models
                ):
                    shutil.rmtree(destination, ignore_errors=True)
                raise

    def promote(
        self,
        model_name: str,
        model_version: str,
        stage: str,
        *,
        previous_production_stage: str = "staging",
        expected_revision: int | None = None,
        promoted_at: str | None = None,
    ) -> RegistryEntry:
        self.initialize()
        _safe_component(model_name, "model name")
        _safe_component(model_version, "model version")
        _validate_stage(stage)
        if previous_production_stage not in {"staging", "archived"}:
            raise ValueError("previous production stage must be staging or archived")
        timestamp = promoted_at or datetime.now(timezone.utc).isoformat()
        with self._lock():
            index = self.read()
            _check_revision(index, expected_revision)
            models = list(index.models)
            target_index = next(
                (
                    position
                    for position, item in enumerate(models)
                    if item.model_name == model_name
                    and item.model_version == model_version
                ),
                None,
            )
            if target_index is None:
                raise KeyError(f"model not found: {model_name}/{model_version}")
            if stage == "production":
                models = _replace_production(
                    models,
                    model_name,
                    timestamp,
                    previous_production_stage,
                    exclude_version=model_version,
                )
                target_index = next(
                    position
                    for position, item in enumerate(models)
                    if item.model_name == model_name
                    and item.model_version == model_version
                )
            current = models[target_index]
            updated = RegistryEntry(
                **{
                    **current.to_dict(),
                    "stage": stage,
                    "updated_at": timestamp,
                    "promoted_at": timestamp if stage == "production" else current.promoted_at,
                }
            )
            models[target_index] = updated
            self._write(
                RegistryIndex(index.registry_format_version, index.revision + 1, tuple(models))
            )
            return updated

    def _write(self, index: RegistryIndex) -> None:
        try:
            write_json(self.index_path, index.to_dict())
        except OSError as exc:
            raise RuntimeError("model registry is not writable") from exc
        try:
            write_json(self.backup_path, index.to_dict())
        except OSError:
            # The committed primary remains authoritative; a later successful
            # mutation refreshes the recovery copy.
            pass

    def _lock(self):
        return _RegistryLock(
            self.locks_directory / "registry.lock",
            timeout_seconds=self.lock_timeout_seconds,
            stale_seconds=self.stale_lock_seconds,
        )

    @staticmethod
    def _read_index(path: Path) -> RegistryIndex:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
        return RegistryIndex.from_dict(value)


class _RegistryLock:
    def __init__(
        self, path: Path, *, timeout_seconds: float, stale_seconds: float
    ):
        self.path = path
        self.timeout_seconds = timeout_seconds
        self.stale_seconds = stale_seconds
        self.descriptor: int | None = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        deadline = time.monotonic() + self.timeout_seconds
        while True:
            try:
                self.descriptor = os.open(
                    self.path, os.O_CREAT | os.O_EXCL | os.O_WRONLY
                )
                break
            except FileExistsError as exc:
                try:
                    age = time.time() - self.path.stat().st_mtime
                    if age >= self.stale_seconds:
                        self.path.unlink(missing_ok=True)
                        continue
                except FileNotFoundError:
                    continue
                if time.monotonic() >= deadline:
                    raise RuntimeError(
                        "timed out waiting for model registry writer lock"
                    ) from exc
                time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
        os.write(self.descriptor, str(os.getpid()).encode("ascii"))
        return self

    def __exit__(self, exc_type, exc, traceback):
        if self.descriptor is not None:
            os.close(self.descriptor)
        self.path.unlink(missing_ok=True)


def _replace_production(
    models: list[RegistryEntry],
    model_name: str,
    timestamp: str,
    replacement_stage: str,
    *,
    exclude_version: str | None = None,
) -> list[RegistryEntry]:
    return [
        RegistryEntry(
            **{
                **item.to_dict(),
                "stage": replacement_stage,
                "updated_at": timestamp,
            }
        )
        if item.model_name == model_name
        and item.stage == "production"
        and item.model_version != exclude_version
        else item
        for item in models
    ]


def _safe_component(value: str, description: str) -> str:
    if (
        not isinstance(value, str)
        or not value.strip()
        or value in {".", ".."}
        or Path(value).is_absolute()
        or len(PurePath(value).parts) != 1
        or any(character in value for character in ("/", "\\", ":"))
    ):
        raise ValueError(f"unsafe {description}: {value!r}")
    return value


def _validate_stage(stage: str) -> None:
    if stage not in STAGES:
        raise ValueError(f"stage must be one of: {', '.join(STAGES)}")


def _check_revision(index: RegistryIndex, expected: int | None) -> None:
    if expected is not None and expected != index.revision:
        raise RuntimeError(
            f"registry revision conflict: expected {expected}, actual {index.revision}"
        )


def _reject_tree_symlinks(root: Path) -> None:
    if root.is_symlink():
        raise ValueError(f"symbolic links are not allowed: {root}")
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"symbolic links are not allowed: {path}")


def _compatibility_status(validation: Any) -> str:
    statuses = {item.status for item in validation.compatibility_results}
    return "fail" if "fail" in statuses else "warning" if "warning" in statuses else "pass"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
