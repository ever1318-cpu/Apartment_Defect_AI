import json
import math
from pathlib import Path
from types import SimpleNamespace

import pytest

from data_engineering.cli import main
from data_engineering.models import ImageRecord
from vision_ai.evaluation_models import GroundTruthAnnotation
from vision_ai.models import BoundingBox, DefectDetection
from vision_ai.pytorch_training import (
    ONNX_OUTPUT_NAMES,
    PreparedTrainingData,
    PyTorchDependencies,
    PyTorchTrainingBackend,
    TrainingDatasetLoader,
    export_pytorch_checkpoint,
    load_pytorch_dependencies,
    resolve_torch_device,
)
from vision_ai.training import TrainingRunner, load_training_backend
from vision_ai.training_dataset import build_training_dataset
from vision_ai.training_models import MetricEntry, TrainingSpec


def _dataset(tmp_path: Path) -> tuple[Path, TrainingSpec]:
    records = []
    annotations = []
    for split in ("train", "validation", "test"):
        image_id = f"{split}-image"
        image = tmp_path / "images" / f"{image_id}.png"
        image.parent.mkdir(parents=True, exist_ok=True)
        image.write_bytes(b"\x89PNG\r\n\x1a\nfixture")
        records.append(
            ImageRecord(
                image_id, str(image.relative_to(tmp_path)), image_id, "defect",
                split=split,
            )
        )
        annotations.append(
            GroundTruthAnnotation(
                image_id=image_id,
                dataset_version="dataset-8",
                classifications={
                    "space": "bathroom",
                    "trade": "finishing",
                    "component": "wall",
                },
                detections=(
                    DefectDetection(
                        "crack",
                        1.0,
                        BoundingBox(0.1, 0.1, 0.5, 0.5),
                        severity="low",
                    ),
                ),
            )
        )
    output = tmp_path / "training-dataset"
    build_training_dataset(
        records,
        annotations,
        output,
        dataset_version="dataset-8",
        image_root=tmp_path,
    )
    spec = TrainingSpec.from_dict(
        json.loads((output / "training_spec.json").read_text(encoding="utf-8"))
    )
    return output, spec


class _Cuda:
    def __init__(self, available=False):
        self.available = available

    def is_available(self):
        return self.available


def test_optional_pytorch_import_and_device_selection() -> None:
    torch = SimpleNamespace(cuda=_Cuda())
    assert resolve_torch_device("auto", torch) == "cpu"
    assert resolve_torch_device("cpu", torch) == "cpu"
    with pytest.raises(RuntimeError, match="CUDA"):
        resolve_torch_device("cuda", torch)
    assert resolve_torch_device("auto", SimpleNamespace(cuda=_Cuda(True))) == "cuda"

    try:
        dependencies = load_pytorch_dependencies()
    except RuntimeError as exc:
        assert "pytorch" in str(exc).lower()
    else:
        assert dependencies.torch is not None


def test_dataset_loader_encodes_labels_and_rejects_empty_train(tmp_path) -> None:
    directory, spec = _dataset(tmp_path)
    prepared = TrainingDatasetLoader().load(spec, directory)
    sample = prepared.splits["train"][0]
    assert sample.image_path.is_file()
    assert sample.classifications == {"space": 0, "trade": 0, "component": 0}
    assert sample.detections[0]["severity"] == 0

    (directory / "train.jsonl").write_text("", encoding="utf-8")
    with pytest.raises(ValueError, match="cannot be empty"):
        TrainingDatasetLoader().load(spec, directory)


class _FakeEngine:
    def __init__(self, dependencies, data, spec, device, run_directory):
        self.run_directory = run_directory
        self.seed = spec.random_seed

    def train(self):
        (self.run_directory / "model.pt").write_bytes(b"latest")
        (self.run_directory / "best-model.pt").write_bytes(b"best")
        return (
            MetricEntry(
                1,
                {
                    "train_loss": self.seed / 1000,
                    "validation_loss": 0.5,
                    "validation_accuracy": 1.0,
                },
            ),
        )

    def validate(self, history):
        return {"best_validation_accuracy": history[-1].metrics["validation_accuracy"]}

    def export(self, final_metrics):
        for name in (
            "model.onnx",
            "checkpoint_metadata.json",
            "export_metadata.json",
            "environment_metadata.json",
        ):
            (self.run_directory / name).write_bytes(b"artifact")
        return {
            "format": "onnx",
            "output_names": list(ONNX_OUTPUT_NAMES),
            "final_metrics": dict(final_metrics),
        }


class _FakeDatasetLoader:
    def load(self, spec, spec_directory):
        return PreparedTrainingData(
            {"train": (), "validation": (), "test": ()},
            SimpleNamespace(tasks={}),
            Path(spec_directory),
        )


def test_injected_pytorch_backend_writes_run_artifacts_deterministically(tmp_path) -> None:
    directory, spec = _dataset(tmp_path)
    dependency = PyTorchDependencies(
        SimpleNamespace(cuda=_Cuda()), SimpleNamespace(), SimpleNamespace(), SimpleNamespace()
    )

    def run(name):
        backend = PyTorchTrainingBackend(
            device="cpu",
            dependency_loader=lambda: dependency,
            dataset_loader=_FakeDatasetLoader(),
            engine_factory=_FakeEngine,
        )
        return TrainingRunner(backend).run(
            spec,
            tmp_path / name,
            spec_directory=directory,
            created_at="2026-07-20T00:00:00+00:00",
        )

    first = run("run-one")
    second = run("run-two")
    assert first.status == second.status == "completed"
    first_history = json.loads(
        (tmp_path / "run-one" / "metric_history.json").read_text(encoding="utf-8")
    )
    second_history = json.loads(
        (tmp_path / "run-two" / "metric_history.json").read_text(encoding="utf-8")
    )
    assert first_history == second_history
    manifest = json.loads(
        (tmp_path / "run-one" / "run_manifest.json").read_text(encoding="utf-8")
    )
    assert {"model.pt", "best-model.pt", "model.onnx"} <= set(manifest["artifacts"])


def test_pytorch_backend_rejects_non_finite_history(tmp_path) -> None:
    directory, spec = _dataset(tmp_path)

    class NanEngine(_FakeEngine):
        def train(self):
            return (MetricEntry(1, {"train_loss": math.nan}),)

    dependency = PyTorchDependencies(
        SimpleNamespace(cuda=_Cuda()), None, None, None
    )
    result = TrainingRunner(
        PyTorchTrainingBackend(
            dependency_loader=lambda: dependency,
            dataset_loader=_FakeDatasetLoader(),
            engine_factory=NanEngine,
        )
    ).run(spec, tmp_path / "nan-run", spec_directory=directory)
    assert result.status == "failed"
    assert "NaN or infinite" in result.error


def test_export_uses_named_onnx_contract_and_handles_failures(tmp_path) -> None:
    checkpoint = tmp_path / "best-model.pt"
    checkpoint.write_bytes(b"checkpoint")
    output = tmp_path / "model.onnx"
    calls = {}

    class Model:
        def load_state_dict(self, value):
            calls["state"] = value

        def eval(self):
            return self

    torch = SimpleNamespace(
        zeros=lambda shape, dtype: ("dummy", shape),
        float32="float32",
    )

    def exporter(model, dummy, target, **options):
        calls.update(options)
        Path(target).write_bytes(b"onnx")

    metadata = export_pytorch_checkpoint(
        checkpoint,
        output,
        dependencies=PyTorchDependencies(torch, None, None, None),
        checkpoint_loader=lambda path: {
            "model_state": {"weight": 1},
            "label_sizes": {},
            "output_names": list(ONNX_OUTPUT_NAMES),
        },
        model_builder=lambda dependencies, sizes: Model(),
        exporter=exporter,
        checker=lambda path: calls.setdefault("checked", path),
    )
    assert calls["output_names"] == list(ONNX_OUTPUT_NAMES)
    assert calls["dynamic_axes"]["images"] == {0: "batch"}
    assert metadata["opset"] == 17

    with pytest.raises(FileNotFoundError):
        export_pytorch_checkpoint(tmp_path / "missing.pt", output)


def test_cli_pytorch_failure_and_export_command(monkeypatch, tmp_path) -> None:
    directory, _ = _dataset(tmp_path)
    exit_code = main(
        [
            "vision-train",
            str(directory / "training_spec.json"),
            str(tmp_path / "missing-dependency-run"),
            "--backend",
            "pytorch",
            "--device",
            "cpu",
        ]
    )
    if exit_code == 1:
        manifest = json.loads(
            (tmp_path / "missing-dependency-run" / "run_manifest.json").read_text(
                encoding="utf-8"
            )
        )
        assert "optional dependencies" in manifest["error"]

    checkpoint = tmp_path / "run" / "best-model.pt"
    checkpoint.parent.mkdir()
    checkpoint.write_bytes(b"checkpoint")

    def fake_export(source, output, **options):
        Path(output).write_bytes(b"onnx")
        return {"output_names": list(ONNX_OUTPUT_NAMES), **options}

    monkeypatch.setattr(
        "vision_ai.pytorch_training.export_pytorch_checkpoint", fake_export
    )
    output = tmp_path / "exported.onnx"
    assert main(
        ["vision-export-onnx", str(checkpoint.parent), str(output), "--opset", "18"]
    ) == 0
    assert output.is_file()
    assert json.loads(output.with_suffix(".metadata.json").read_text())["opset"] == 18


def test_training_backend_registry_loads_pytorch_without_importing_torch() -> None:
    backend = load_training_backend("pytorch", device="cpu")
    assert isinstance(backend, PyTorchTrainingBackend)
