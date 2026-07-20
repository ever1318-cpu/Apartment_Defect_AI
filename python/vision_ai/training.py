"""Framework-neutral training workflow and deterministic reference backend."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict
from datetime import datetime, timezone
from importlib import import_module
from pathlib import Path
from typing import Any, Mapping, Protocol, Sequence, cast

from data_engineering.io import read_jsonl, write_json

from .training_models import (
    LabelMapping,
    MetricEntry,
    TrainingRunResult,
    TrainingSpec,
)


class TrainingBackend(Protocol):
    @property
    def backend_name(self) -> str: ...

    def prepare(
        self, spec: TrainingSpec, spec_directory: Path
    ) -> Mapping[str, Any]: ...

    def train(
        self, prepared: Mapping[str, Any], spec: TrainingSpec
    ) -> Sequence[MetricEntry]: ...

    def validate(
        self,
        prepared: Mapping[str, Any],
        history: Sequence[MetricEntry],
        spec: TrainingSpec,
    ) -> Mapping[str, float]: ...

    def export(
        self,
        prepared: Mapping[str, Any],
        final_metrics: Mapping[str, float],
        spec: TrainingSpec,
    ) -> Mapping[str, Any]: ...


class ReferenceTrainingBackend:
    backend_name = "reference"

    def prepare(
        self, spec: TrainingSpec, spec_directory: Path
    ) -> Mapping[str, Any]:
        split_counts = {}
        for split, relative_path in spec.split_paths.items():
            path = _resolve(spec_directory, relative_path)
            if not path.is_file():
                raise FileNotFoundError(f"training split does not exist: {path}")
            split_counts[split] = sum(1 for _ in read_jsonl(path))
        if split_counts["train"] == 0:
            raise ValueError("training split cannot be empty")
        mapping_path = _resolve(spec_directory, spec.label_mapping_path)
        if not mapping_path.is_file():
            raise FileNotFoundError(f"label mapping does not exist: {mapping_path}")
        mapping = LabelMapping.from_dict(
            json.loads(mapping_path.read_text(encoding="utf-8"))
        )
        return {
            "split_counts": split_counts,
            "label_tasks": sorted(mapping.tasks),
        }

    def train(
        self, prepared: Mapping[str, Any], spec: TrainingSpec
    ) -> Sequence[MetricEntry]:
        seed_offset = (spec.random_seed % 10) / 1000
        return tuple(
            MetricEntry(
                epoch=epoch,
                metrics={
                    "train_loss": round(1 / epoch + seed_offset, 6),
                    "validation_accuracy": round(
                        min(0.99, 0.5 + epoch / (2 * spec.epochs)), 6
                    ),
                },
            )
            for epoch in range(1, spec.epochs + 1)
        )

    def validate(
        self,
        prepared: Mapping[str, Any],
        history: Sequence[MetricEntry],
        spec: TrainingSpec,
    ) -> Mapping[str, float]:
        if not history:
            raise ValueError("metric history cannot be empty")
        final = history[-1].metrics
        return {
            "validation_accuracy": final["validation_accuracy"],
            "final_train_loss": final["train_loss"],
            "test_sample_count": float(prepared["split_counts"]["test"]),
        }

    def export(
        self,
        prepared: Mapping[str, Any],
        final_metrics: Mapping[str, float],
        spec: TrainingSpec,
    ) -> Mapping[str, Any]:
        return {
            "format": "reference-training-artifact",
            "dataset_version": spec.dataset_version,
            "tasks": asdict(spec.tasks),
            "label_tasks": prepared["label_tasks"],
            "final_metrics": dict(final_metrics),
        }


class TrainingRunner:
    def __init__(self, backend: TrainingBackend):
        self.backend = backend

    def run(
        self,
        spec: TrainingSpec,
        run_directory: str | Path,
        *,
        spec_directory: str | Path | None = None,
        created_at: str | None = None,
    ) -> TrainingRunResult:
        run_dir = Path(run_directory)
        if run_dir.exists():
            raise FileExistsError(f"training run directory already exists: {run_dir}")
        spec_dir = Path(spec_directory) if spec_directory is not None else Path.cwd()
        timestamp = created_at or datetime.now(timezone.utc).isoformat()
        run_id = _run_id(spec, timestamp)
        run_dir.mkdir(parents=True, exist_ok=False)
        manifest_path = run_dir / "run_manifest.json"
        artifacts = ["training_spec.json", "run_manifest.json"]
        write_json(run_dir / "training_spec.json", spec.to_dict())
        stages: dict[str, str] = {}
        try:
            mapping_source = _resolve(spec_dir, spec.label_mapping_path)
            mapping = LabelMapping.from_dict(
                json.loads(mapping_source.read_text(encoding="utf-8"))
            )
            write_json(run_dir / "label_mapping.json", mapping.to_dict())
            artifacts.append("label_mapping.json")

            prepared = dict(self.backend.prepare(spec, spec_dir))
            prepared.setdefault("run_directory", str(run_dir))
            prepared.setdefault("artifacts", [])
            stages["prepare"] = "completed"
            history = tuple(self.backend.train(prepared, spec))
            stages["train"] = "completed"
            write_json(
                run_dir / "metric_history.json",
                {"history": [asdict(item) for item in history]},
            )
            artifacts.append("metric_history.json")

            final_metrics = dict(self.backend.validate(prepared, history, spec))
            stages["validate"] = "completed"
            write_json(run_dir / "final_metrics.json", final_metrics)
            artifacts.append("final_metrics.json")

            model_metadata = dict(
                self.backend.export(prepared, final_metrics, spec)
            )
            stages["export"] = "completed"
            for artifact in prepared.get("artifacts", ()):
                if artifact not in artifacts:
                    artifacts.append(artifact)
            write_json(run_dir / spec.model_artifact_name, model_metadata)
            artifacts.append(spec.model_artifact_name)
            write_json(
                run_dir / "model_metadata.json",
                {
                    "backend": self.backend.backend_name,
                    "artifact": spec.model_artifact_name,
                    **model_metadata,
                },
            )
            artifacts.append("model_metadata.json")
            manifest = {
                "run_id": run_id,
                "created_at": timestamp,
                "status": "completed",
                "backend": self.backend.backend_name,
                "dataset_version": spec.dataset_version,
                "stages": stages,
                "artifacts": artifacts,
                "final_metrics": final_metrics,
            }
            write_json(manifest_path, manifest)
            return TrainingRunResult(
                run_id=run_id,
                created_at=timestamp,
                status="completed",
                run_directory=str(run_dir),
                manifest_path=str(manifest_path),
                final_metrics=final_metrics,
            )
        except Exception as exc:
            manifest = {
                "run_id": run_id,
                "created_at": timestamp,
                "status": "failed",
                "backend": self.backend.backend_name,
                "dataset_version": spec.dataset_version,
                "stages": stages,
                "artifacts": artifacts,
                "error_type": type(exc).__name__,
                "error": str(exc),
            }
            write_json(manifest_path, manifest)
            return TrainingRunResult(
                run_id=run_id,
                created_at=timestamp,
                status="failed",
                run_directory=str(run_dir),
                manifest_path=str(manifest_path),
                error_type=type(exc).__name__,
                error=str(exc),
            )


def load_training_backend(specification: str, **options: Any) -> TrainingBackend:
    if specification.strip().lower() == "reference":
        if options:
            raise ValueError("reference training backend does not accept options")
        return ReferenceTrainingBackend()
    if specification.strip().lower() == "pytorch":
        from .pytorch_training import PyTorchTrainingBackend

        return PyTorchTrainingBackend(**options)
    module_name, separator, attribute_name = specification.partition(":")
    if not separator:
        raise ValueError(f"unknown training backend {specification!r}")
    try:
        value = getattr(import_module(module_name), attribute_name)
    except (ImportError, AttributeError) as exc:
        raise ValueError(
            f"cannot load training backend {specification!r}: {exc}"
        ) from exc
    backend = value(**options) if callable(value) else value
    required = ("backend_name", "prepare", "train", "validate", "export")
    missing = [name for name in required if not hasattr(backend, name)]
    if missing:
        raise ValueError(
            f"training backend is missing required members: {', '.join(missing)}"
        )
    return cast(TrainingBackend, backend)


def _resolve(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path


def _run_id(spec: TrainingSpec, created_at: str) -> str:
    payload = json.dumps(spec.to_dict(), sort_keys=True).encode("utf-8")
    digest = hashlib.sha256(payload).hexdigest()[:8]
    timestamp = "".join(character for character in created_at if character.isdigit())[:14]
    return f"run-{timestamp}-{digest}"
