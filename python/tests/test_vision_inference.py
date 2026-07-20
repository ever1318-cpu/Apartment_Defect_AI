import json

import pytest

from data_engineering.cli import main
from data_engineering.models import ImageRecord
from vision_ai.backends import (
    CallableVisionBackend,
    ReferenceVisionBackend,
    load_backend,
)
from vision_ai.image_io import inspect_image_file
from vision_ai.inference import InferenceRunner
from vision_ai.models import BoundingBox, Classification, DefectDetection, ImageQuality
from vision_ai.pipeline import VisionPipeline


def record(image_id: str, path: str = "images/example.jpg") -> ImageRecord:
    return ImageRecord(image_id, path, "apartment-1", "crack")


def write_png(path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"reference-image-bytes")


def backend() -> CallableVisionBackend:
    def quality(path: str) -> ImageQuality:
        if "broken" in path:
            raise RuntimeError("unreadable image")
        return ImageQuality(0.2, False) if "poor" in path else ImageQuality(0.9, True)

    return CallableVisionBackend(
        "callable-1",
        quality,
        lambda path, task: (Classification(task, 0.9),),
        lambda path: (
            DefectDetection("crack", 0.8, BoundingBox(0.1, 0.1, 0.3, 0.3)),
        ),
    )


def test_callable_backend_preserves_protocol_contract() -> None:
    prediction = VisionPipeline(backend()).predict(record("image-1"))
    assert prediction.model_version == "callable-1"
    assert prediction.classifications["space"][0].label == "space"


def test_runner_isolates_failures_and_summarizes_outcomes() -> None:
    result = InferenceRunner(VisionPipeline(backend())).run(
        [
            record("ok"),
            record("poor", "images/poor.jpg"),
            record("broken", "images/broken.jpg"),
            record("ok"),
        ]
    )
    assert [item.image_id for item in result.predictions] == ["ok", "poor"]
    assert [item.error_type for item in result.failures] == ["RuntimeError", "ValueError"]
    assert result.summary.to_dict() == {
        "total": 4,
        "completed": 1,
        "rejected_quality": 1,
        "failed": 2,
    }
    assert result.predictions[0].metadata["backend_name"] == "CallableVisionBackend"
    assert result.predictions[0].metadata["duration_ms"] >= 0
    assert [item.metadata["status"] for item in result.outputs] == [
        "completed",
        "rejected_quality",
        "error",
        "error",
    ]


def test_runner_can_fail_fast() -> None:
    with pytest.raises(RuntimeError, match="unreadable"):
        InferenceRunner(VisionPipeline(backend()), fail_fast=True).run(
            [record("broken", "broken.jpg")]
        )


def test_backend_loader_loads_factory_and_validates_specification() -> None:
    loaded = load_backend("tests.fixture_vision_backend:create_backend")
    assert loaded.model_version == "fixture-1"
    with pytest.raises(ValueError, match="module:attribute"):
        load_backend("invalid")
    assert isinstance(load_backend("reference"), ReferenceVisionBackend)


def test_vision_predict_cli_writes_predictions_and_summary(tmp_path, capsys) -> None:
    source = tmp_path / "records.jsonl"
    output = tmp_path / "predictions.jsonl"
    write_png(tmp_path / "images" / "example.png")
    source.write_text(
        json.dumps(record("image-1", "images/example.png").to_dict()) + "\n",
        encoding="utf-8",
    )

    code = main(
        [
            "vision-predict",
            str(source),
            str(output),
            "--backend",
            "tests.fixture_vision_backend:create_backend",
        ]
    )

    assert code == 0
    prediction = json.loads(output.read_text(encoding="utf-8"))
    assert prediction["image_id"] == "image-1"
    assert json.loads(capsys.readouterr().out)["completed"] == 1


def test_image_inspection_rejects_extension_signature_mismatch(tmp_path) -> None:
    image = tmp_path / "wrong.jpg"
    write_png(image)
    with pytest.raises(ValueError, match="expects jpeg, found png"):
        inspect_image_file(image)


def test_reference_backend_is_deterministic_for_real_image(tmp_path) -> None:
    image = tmp_path / "sample.png"
    write_png(image)
    pipeline = VisionPipeline(ReferenceVisionBackend())
    item = record("image-1", str(image))

    first = pipeline.predict(item)
    second = pipeline.predict(item)

    assert first == second
    assert first.detections[0].mask is not None


def test_manifest_batch_keeps_error_predictions_validate_compatible(
    tmp_path, capsys
) -> None:
    manifest = tmp_path / "records.jsonl"
    predictions = tmp_path / "predictions.jsonl"
    errors = tmp_path / "errors.jsonl"
    write_png(tmp_path / "valid.png")
    records = [
        record("valid", "valid.png"),
        record("missing", "missing.png"),
    ]
    manifest.write_text(
        "".join(json.dumps(item.to_dict()) + "\n" for item in records),
        encoding="utf-8",
    )

    code = main(
        [
            "vision-predict",
            str(manifest),
            str(predictions),
            "--backend",
            "reference",
            "--errors",
            str(errors),
        ]
    )

    assert code == 1
    output_items = [
        json.loads(line) for line in predictions.read_text(encoding="utf-8").splitlines()
    ]
    assert [item["metadata"]["status"] for item in output_items] == [
        "completed",
        "error",
    ]
    assert json.loads(errors.read_text(encoding="utf-8"))["image_id"] == "missing"
    capsys.readouterr()
    assert main(["vision-validate", str(predictions), "--records", str(manifest)]) == 0


def test_single_image_cli_uses_reference_backend_by_default(tmp_path, capsys) -> None:
    image = tmp_path / "single.png"
    output = tmp_path / "single-prediction.jsonl"
    write_png(image)

    assert main(["vision-predict-image", str(image), str(output)]) == 0

    prediction = json.loads(output.read_text(encoding="utf-8"))
    assert prediction["image_id"] == "single"
    assert prediction["metadata"]["backend_name"] == "reference"
    assert prediction["metadata"]["model_version"] == "reference-1"
    assert prediction["metadata"]["duration_ms"] >= 0
    assert json.loads(capsys.readouterr().out)["completed"] == 1
