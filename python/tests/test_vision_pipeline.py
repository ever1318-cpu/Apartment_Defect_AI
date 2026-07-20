import json

import pytest

from data_engineering.cli import main
from data_engineering.models import ImageRecord
from vision_ai.models import BoundingBox, Classification, DefectDetection, ImageQuality
from vision_ai.pipeline import PipelineConfig, VisionPipeline
from vision_ai.validators import validate_predictions


class FakeBackend:
    model_version = "fake-1"

    def __init__(self, acceptable: bool = True):
        self.acceptable = acceptable
        self.calls: list[tuple[str, str]] = []

    def assess_quality(self, image_path: str) -> ImageQuality:
        self.calls.append(("quality", image_path))
        return ImageQuality(0.9 if self.acceptable else 0.2, self.acceptable)

    def classify(self, image_path: str, task: str) -> list[Classification]:
        self.calls.append((task, image_path))
        return [Classification(f"{task}-a", 0.9), Classification(f"{task}-b", 0.1)]

    def detect(self, image_path: str) -> list[DefectDetection]:
        self.calls.append(("detect", image_path))
        return [
            DefectDetection("crack", 0.9, BoundingBox(0, 0, 0.5, 0.5)),
            DefectDetection("crack", 0.8, BoundingBox(0.01, 0.01, 0.49, 0.49)),
        ]


def record(image_id: str = "image-1") -> ImageRecord:
    return ImageRecord(image_id, "images/1.jpg", "apartment-1", "crack")


def test_pipeline_runs_all_stages_and_postprocessing() -> None:
    backend = FakeBackend()
    prediction = VisionPipeline(backend).predict(record())

    assert prediction.metadata["status"] == "completed"
    assert tuple(prediction.classifications) == ("space", "trade", "component")
    assert all(len(values) == 1 for values in prediction.classifications.values())
    assert len(prediction.detections) == 1
    assert prediction.detections[0].severity == "high"


def test_pipeline_short_circuits_unacceptable_image() -> None:
    backend = FakeBackend(acceptable=False)
    prediction = VisionPipeline(backend).predict(record())

    assert prediction.metadata["status"] == "rejected_quality"
    assert prediction.classifications == {}
    assert prediction.detections == ()
    assert backend.calls == [("quality", "images/1.jpg")]


def test_pipeline_can_continue_after_quality_warning() -> None:
    backend = FakeBackend(acceptable=False)
    config = PipelineConfig(reject_low_quality=False)
    assert VisionPipeline(backend, config).predict(record()).detections


def test_pipeline_config_rejects_invalid_thresholds() -> None:
    with pytest.raises(ValueError, match="severity areas"):
        PipelineConfig(severity_medium_area=0.5, severity_high_area=0.2)


def test_batch_rejects_duplicate_image_ids() -> None:
    with pytest.raises(ValueError, match="duplicate image_id"):
        VisionPipeline(FakeBackend()).predict_many([record(), record()])


def test_prediction_validation_compares_manifest_ids() -> None:
    prediction = VisionPipeline(FakeBackend()).predict(record())
    issues = validate_predictions(
        [prediction, prediction], expected_image_ids={"image-1", "image-2"}
    )
    assert [issue.code for issue in issues] == [
        "duplicate_image_id",
        "missing_prediction",
    ]


def test_vision_validate_cli_accepts_valid_predictions(tmp_path) -> None:
    prediction = VisionPipeline(FakeBackend()).predict(record())
    path = tmp_path / "predictions.jsonl"
    path.write_text(json.dumps(prediction.to_dict()) + "\n", encoding="utf-8")

    assert main(["vision-validate", str(path)]) == 0
