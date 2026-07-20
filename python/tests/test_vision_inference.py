import json

import pytest

from data_engineering.cli import main
from data_engineering.models import ImageRecord
from vision_ai.backends import CallableVisionBackend, load_backend
from vision_ai.inference import InferenceRunner
from vision_ai.models import BoundingBox, Classification, DefectDetection, ImageQuality
from vision_ai.pipeline import VisionPipeline


def record(image_id: str, path: str = "images/example.jpg") -> ImageRecord:
    return ImageRecord(image_id, path, "apartment-1", "crack")


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


def test_vision_predict_cli_writes_predictions_and_summary(tmp_path, capsys) -> None:
    source = tmp_path / "records.jsonl"
    output = tmp_path / "predictions.jsonl"
    source.write_text(
        json.dumps(record("image-1").to_dict()) + "\n", encoding="utf-8"
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
