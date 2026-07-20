import base64
from dataclasses import replace
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from data_engineering.io import write_json
from vision_ai.model_package import generate_checksums
from vision_ai.model_registry import ModelRegistry
from vision_ai.onnx_backend import OnnxVisionBackend
from vision_ai.serving import ServingConfig
from vision_ai.serving_app import create_serving_app


def test_core_import_and_cli_help_do_not_import_optional_stacks() -> None:
    command = subprocess.run(
        [sys.executable, "-m", "data_engineering.cli", "--help"],
        capture_output=True,
        text=True,
        check=True,
        env={**os.environ, "PYTHONPATH": "python"},
    )
    assert "vision-release-check" in command.stdout
    script = (
        "import sys,vision_ai;"
        "names=('fastapi','onnxruntime','torch','torchvision');"
        "assert not any(name in sys.modules for name in names)"
    )
    subprocess.run(
        [sys.executable, "-c", script],
        check=True,
        env={**os.environ, "PYTHONPATH": "python"},
    )


@pytest.mark.integration
@pytest.mark.serving
def test_fastapi_request_lifecycle_is_enabled_with_serving_extra(tmp_path) -> None:
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient
    from test_vision_registry_serving import _service, _png

    service, _ = _service(tmp_path, cache=True)
    app = create_serving_app(service.config, service=service)
    encoded = base64.b64encode(_png()).decode()
    with TestClient(app) as client:
        assert client.get("/health").status_code == 200
        assert client.get("/ready").status_code == 200
        assert client.get("/v1/models").status_code == 200
        first = client.post(
            "/v1/predict",
            json={
                "image_base64": encoded,
                "mime_type": "image/png",
                "image_id": "first",
            },
            headers={"x-request-id": "matrix-request"},
        )
        second = client.post(
            "/v1/predict",
            json={
                "image_base64": encoded,
                "mime_type": "image/png",
                "image_id": "second",
            },
        )
        assert first.status_code == second.status_code == 200
        assert first.headers["x-request-id"] == "matrix-request"
        assert second.json()["image_id"] == "second"
        invalid = client.post(
            "/v1/predict",
            json={"image_base64": "%%%", "mime_type": "image/png"},
        )
        assert invalid.status_code == 400
        assert set(invalid.json()["error"]) == {
            "code",
            "message",
            "details",
            "request_id",
        }
        metrics = client.get("/v1/metrics").json()
        assert metrics["cache_hit_count"] >= 1
        assert metrics["by_response_status"]["200"] >= 1


@pytest.mark.integration
@pytest.mark.onnx
def test_tiny_onnxruntime_cpu_package_registry_smoke(tmp_path) -> None:
    onnx = pytest.importorskip("onnx")
    numpy = pytest.importorskip("numpy")
    pytest.importorskip("onnxruntime")
    from onnx import TensorProto, helper
    from test_vision_registry_serving import _package

    package = _package(tmp_path)
    input_info = helper.make_tensor_value_info(
        "images", TensorProto.FLOAT, [None, 3, 224, 224]
    )
    specifications = (
        ("quality", TensorProto.FLOAT, [1, 1], [0.9]),
        ("space_scores", TensorProto.FLOAT, [1, 2], [0.9, 0.1]),
        ("trade_scores", TensorProto.FLOAT, [1, 2], [0.8, 0.2]),
        ("component_scores", TensorProto.FLOAT, [1, 2], [0.7, 0.3]),
        ("boxes", TensorProto.FLOAT, [1, 1, 4], [0.1, 0.1, 0.4, 0.4]),
        ("detection_scores", TensorProto.FLOAT, [1, 1], [0.95]),
        ("detection_labels", TensorProto.INT64, [1, 1], [0]),
    )
    nodes = []
    outputs = []
    for name, data_type, dims, values in specifications:
        tensor = helper.make_tensor(f"{name}_value", data_type, dims, values)
        nodes.append(helper.make_node("Constant", [], [name], value=tensor))
        outputs.append(helper.make_tensor_value_info(name, data_type, dims))
    graph = helper.make_graph(nodes, "tiny-vision", [input_info], outputs)
    model = helper.make_model(
        graph, opset_imports=[helper.make_opsetid("", 17)]
    )
    model.ir_version = 10
    onnx.save(model, package / "model.onnx")
    write_json(
        package / "checksums.json",
        {"algorithm": "sha256", "files": generate_checksums(package)},
    )
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        package, "apartment-defect", "1.0.0", stage="production"
    )
    backend = OnnxVisionBackend(
        registry.package_directory(registry.production("apartment-defect")),
        providers=("CPUExecutionProvider",),
        input_loader=lambda path: numpy.zeros((1, 3, 224, 224), dtype=numpy.float32),
    )
    assert backend.assess_quality("unused.png").acceptable
    assert backend.classify("unused.png", "space")[0].label == "bathroom"
    assert backend.detect("unused.png")[0].label == "crack"


@pytest.mark.integration
@pytest.mark.training
@pytest.mark.slow
def test_pytorch_tiny_model_export_smoke_when_full_stack_is_installed(tmp_path) -> None:
    pytest.importorskip("torch")
    pytest.importorskip("torchvision")
    pytest.importorskip("onnx")
    pytest.importorskip("onnxruntime")
    pytest.importorskip("PIL")
    from PIL import Image

    from data_engineering.models import ImageRecord
    from vision_ai.evaluation import EvaluationConfig, evaluate_predictions
    from vision_ai.evaluation_models import GroundTruthAnnotation
    from vision_ai.model_package import build_model_package, validate_model_package
    from vision_ai.models import BoundingBox, DefectDetection
    from vision_ai.pipeline import PipelineConfig, VisionPipeline
    from vision_ai.pytorch_training import PyTorchTrainingBackend
    from vision_ai.training import TrainingRunner
    from vision_ai.training_dataset import build_training_dataset
    from vision_ai.training_models import TrainingSpec

    dependencies_available = all(
        importlib.util.find_spec(name) is not None
        for name in (
            "torch",
            "torchvision",
            "onnx",
            "onnxruntime",
            "numpy",
            "PIL",
        )
    )
    assert dependencies_available

    records = []
    annotations = []
    for index, split in enumerate(("train", "validation", "test")):
        image_id = f"{split}-image"
        image = tmp_path / "images" / f"{image_id}.png"
        image.parent.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (32, 32), (64 + index, 96, 128)).save(image)
        records.append(
            ImageRecord(
                image_id,
                str(image.relative_to(tmp_path)),
                f"{split}-group",
                "defect",
                width=32,
                height=32,
                split=split,
            )
        )
        annotations.append(
            GroundTruthAnnotation(
                image_id=image_id,
                dataset_version="smoke-dataset",
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

    dataset = tmp_path / "training-dataset"
    build_training_dataset(
        records,
        annotations,
        dataset,
        dataset_version="smoke-dataset",
        image_root=tmp_path,
    )
    spec = TrainingSpec.from_dict(
        json.loads((dataset / "training_spec.json").read_text(encoding="utf-8"))
    )
    spec = replace(
        spec,
        batch_size=1,
        epochs=1,
        image_preprocessing={
            "color_mode": "RGB",
            "resize": [32, 32],
            "normalize": "0_to_1",
        },
        augmentation={"enabled": False, "train_only": True},
        onnx_export={
            "opset": 17,
            "dynamic_batch": True,
            "input_shape": [1, 3, 32, 32],
        },
    )
    run = tmp_path / "training-run"
    result = TrainingRunner(PyTorchTrainingBackend(device="cpu")).run(
        spec, run, spec_directory=dataset
    )
    assert result.status == "completed", result.error
    assert (run / "model.pt").is_file()
    assert (run / "best-model.pt").is_file()
    assert (run / "model.onnx").is_file()

    package = build_model_package(
        run,
        tmp_path / "model-package",
        "apartment-defect",
        "smoke-1.0.0",
    )
    assert validate_model_package(package).valid
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        package,
        "apartment-defect",
        "smoke-1.0.0",
        stage="production",
    )
    entry = registry.production("apartment-defect")
    backend = OnnxVisionBackend(
        registry.package_directory(entry),
        providers=("CPUExecutionProvider",),
    )
    test_record = replace(
        records[-1],
        image_path=str((tmp_path / records[-1].image_path).resolve()),
    )
    prediction = VisionPipeline(
        backend,
        PipelineConfig(
            classification_threshold=0.0,
            detection_threshold=0.0,
            reject_low_quality=False,
        ),
    ).predict(test_record)
    report = evaluate_predictions(
        (annotations[-1],),
        (prediction,),
        EvaluationConfig(confidence_threshold=0.0),
    )
    assert report.evaluated_images == 1
    assert prediction.model_version == "smoke-1.0.0"
