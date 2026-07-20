import json
from pathlib import Path

import pytest

from data_engineering.cli import main
from data_engineering.io import write_jsonl, write_records
from data_engineering.models import ImageRecord
from vision_ai.evaluation_models import GroundTruthAnnotation
from vision_ai.models import BoundingBox, DefectDetection
from vision_ai.training import ReferenceTrainingBackend, TrainingRunner
from vision_ai.training_dataset import build_training_dataset
from vision_ai.training_models import (
    LabelMapping,
    LabelVocabulary,
    TrainingSpec,
    TrainingTasks,
)


def write_png(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + b"training-image")


def annotation(
    image_id: str,
    *,
    space: str = "bathroom",
    detection_label: str = "crack",
    severity: str = "low",
) -> GroundTruthAnnotation:
    return GroundTruthAnnotation(
        image_id=image_id,
        dataset_version="dataset-7",
        classifications={
            "space": space,
            "trade": "finishing",
            "component": "wall",
        },
        detections=(
            DefectDetection(
                detection_label,
                1.0,
                BoundingBox(0.1, 0.1, 0.5, 0.5),
                severity=severity,
            ),
        ),
    )


def training_inputs(tmp_path: Path):
    records = [
        ImageRecord("train-b", "images/train-b.png", "group-1", "defect", split="train"),
        ImageRecord("train-a", "images/train-a.png", "group-2", "defect", split="train"),
        ImageRecord(
            "validation",
            "images/validation.png",
            "group-3",
            "defect",
            split="validation",
        ),
        ImageRecord("test", "images/test.png", "group-4", "defect", split="test"),
    ]
    annotations = [
        annotation("train-b", space="kitchen", detection_label="leak", severity="medium"),
        annotation("train-a"),
        annotation("validation"),
        annotation("test"),
    ]
    for record in records:
        write_png(tmp_path / record.image_path)
    return records, annotations


def build_fixture(tmp_path: Path, name: str = "training-dataset"):
    records, annotations = training_inputs(tmp_path)
    output = tmp_path / name
    result = build_training_dataset(
        records,
        annotations,
        output,
        dataset_version="dataset-7",
        image_root=tmp_path,
    )
    return output, result


def load_spec(dataset_dir: Path) -> TrainingSpec:
    return TrainingSpec.from_dict(
        json.loads((dataset_dir / "training_spec.json").read_text(encoding="utf-8"))
    )


def test_label_vocabulary_has_stable_indices_and_round_trip() -> None:
    first = LabelVocabulary(("bathroom", "kitchen"))
    second = LabelVocabulary(tuple(sorted({"kitchen", "bathroom"})))

    assert first.label_to_index == {"bathroom": 0, "kitchen": 1}
    assert first == second
    assert LabelVocabulary.from_dict(first.to_dict()) == first
    with pytest.raises(ValueError, match="unknown label"):
        first.encode("balcony")
    with pytest.raises(ValueError, match="reserved"):
        LabelVocabulary(("__unknown__",))


def test_builder_writes_split_outputs_mapping_and_statistics(tmp_path) -> None:
    output, result = build_fixture(tmp_path)

    assert result.sample_counts == {"train": 2, "validation": 1, "test": 1}
    train_ids = [
        json.loads(line)["image_id"]
        for line in (output / "train.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    mapping = LabelMapping.from_dict(
        json.loads((output / "label_mapping.json").read_text(encoding="utf-8"))
    )
    manifest = json.loads(
        (output / "dataset_manifest.json").read_text(encoding="utf-8")
    )

    assert train_ids == ["train-a", "train-b"]
    first_sample = json.loads(
        (output / "train.jsonl").read_text(encoding="utf-8").splitlines()[0]
    )
    assert not Path(first_sample["image_path"]).is_absolute()
    assert (output / first_sample["image_path"]).resolve().is_file()
    assert mapping.tasks["classification:space"].labels == ("bathroom", "kitchen")
    assert mapping.tasks["detection"].labels == ("crack", "leak")
    assert manifest["statistics"]["train"]["sample_count"] == 2
    assert manifest["statistics"]["train"]["label_distribution"]["severity"] == {
        "low": 1,
        "medium": 1,
    }


def test_builder_can_enable_only_classification_task(tmp_path) -> None:
    records, annotations = training_inputs(tmp_path)
    output = tmp_path / "classification-only"
    build_training_dataset(
        records,
        annotations,
        output,
        dataset_version="dataset-7",
        image_root=tmp_path,
        tasks=TrainingTasks(
            classification=True,
            detection=False,
            severity=False,
            classification_tasks=("space",),
        ),
    )
    mapping = LabelMapping.from_dict(
        json.loads((output / "label_mapping.json").read_text(encoding="utf-8"))
    )
    sample = json.loads(
        (output / "train.jsonl").read_text(encoding="utf-8").splitlines()[0]
    )
    assert tuple(mapping.tasks) == ("classification:space",)
    assert sample["annotation"]["detections"] == []
    assert sample["annotation"]["classifications"] == {"space": "bathroom"}


def test_builder_rejects_missing_image_duplicate_and_group_leakage(tmp_path) -> None:
    records, annotations = training_inputs(tmp_path)
    (tmp_path / records[0].image_path).unlink()
    with pytest.raises(FileNotFoundError):
        build_training_dataset(
            records,
            annotations,
            tmp_path / "missing",
            dataset_version="dataset-7",
            image_root=tmp_path,
        )

    records, annotations = training_inputs(tmp_path)
    with pytest.raises(ValueError, match="duplicate record"):
        build_training_dataset(
            [*records, records[0]],
            annotations,
            tmp_path / "duplicate",
            dataset_version="dataset-7",
            image_root=tmp_path,
        )

    records[2] = ImageRecord(
        "validation",
        "images/validation.png",
        "group-1",
        "defect",
        split="validation",
    )
    with pytest.raises(ValueError, match="group leakage"):
        build_training_dataset(
            records,
            annotations,
            tmp_path / "leakage",
            dataset_version="dataset-7",
            image_root=tmp_path,
        )


def test_builder_rejects_missing_annotation_invalid_box_and_unknown_label(
    tmp_path,
) -> None:
    records, annotations = training_inputs(tmp_path)
    with pytest.raises(ValueError, match="missing annotations"):
        build_training_dataset(
            records,
            annotations[:-1],
            tmp_path / "missing-annotation",
            dataset_version="dataset-7",
            image_root=tmp_path,
        )
    with pytest.raises(ValueError, match="positive area"):
        GroundTruthAnnotation.from_dict(
            {
                "image_id": "bad",
                "classifications": {},
                "detections": [
                    {
                        "label": "crack",
                        "box": {"x_min": 0.5, "y_min": 0.1, "x_max": 0.5, "y_max": 0.2},
                    }
                ],
            }
        )

    records, annotations = training_inputs(tmp_path)
    annotations[2] = annotation("validation", space="balcony")
    with pytest.raises(ValueError, match="unknown label"):
        build_training_dataset(
            records,
            annotations,
            tmp_path / "unknown-label",
            dataset_version="dataset-7",
            image_root=tmp_path,
        )


def test_training_spec_json_round_trip(tmp_path) -> None:
    dataset_dir, _ = build_fixture(tmp_path)
    spec = load_spec(dataset_dir)
    assert TrainingSpec.from_dict(spec.to_dict()) == spec


def test_reference_training_is_deterministic_and_writes_artifacts(tmp_path) -> None:
    dataset_dir, _ = build_fixture(tmp_path)
    spec = load_spec(dataset_dir)
    created_at = "2026-07-20T00:00:00+00:00"
    first = TrainingRunner(ReferenceTrainingBackend()).run(
        spec,
        tmp_path / "run-1",
        spec_directory=dataset_dir,
        created_at=created_at,
    )
    second = TrainingRunner(ReferenceTrainingBackend()).run(
        spec,
        tmp_path / "run-2",
        spec_directory=dataset_dir,
        created_at=created_at,
    )

    assert first.status == second.status == "completed"
    assert first.run_id == second.run_id
    assert first.final_metrics == second.final_metrics
    first_history = json.loads(
        (tmp_path / "run-1" / "metric_history.json").read_text(encoding="utf-8")
    )
    second_history = json.loads(
        (tmp_path / "run-2" / "metric_history.json").read_text(encoding="utf-8")
    )
    assert first_history == second_history
    assert len(first_history["history"]) == spec.epochs
    for artifact in (
        "training_spec.json",
        "label_mapping.json",
        "metric_history.json",
        "final_metrics.json",
        "model-artifact.json",
        "model_metadata.json",
        "run_manifest.json",
    ):
        assert (tmp_path / "run-1" / artifact).is_file()


def test_runner_prevents_collision_and_records_failure(tmp_path) -> None:
    dataset_dir, _ = build_fixture(tmp_path)
    spec = load_spec(dataset_dir)
    existing = tmp_path / "existing"
    existing.mkdir()
    with pytest.raises(FileExistsError):
        TrainingRunner(ReferenceTrainingBackend()).run(
            spec, existing, spec_directory=dataset_dir
        )

    class FailingBackend:
        backend_name = "failing"

        def prepare(self, spec, spec_directory):
            raise RuntimeError("prepare failed")

    failed = TrainingRunner(FailingBackend()).run(
        spec,
        tmp_path / "failed",
        spec_directory=dataset_dir,
        created_at="2026-07-20T00:00:00+00:00",
    )
    manifest = json.loads(
        (tmp_path / "failed" / "run_manifest.json").read_text(encoding="utf-8")
    )
    assert failed.status == "failed"
    assert failed.error_type == "RuntimeError"
    assert manifest["status"] == "failed"
    assert manifest["error"] == "prepare failed"


def test_training_cli_end_to_end(tmp_path, capsys) -> None:
    records, annotations = training_inputs(tmp_path)
    records_path = tmp_path / "records.jsonl"
    annotations_path = tmp_path / "annotations.jsonl"
    dataset_dir = tmp_path / "training-dataset"
    run_dir = tmp_path / "training-run"
    write_records(records_path, records)
    write_jsonl(annotations_path, (item.to_dict() for item in annotations))

    assert (
        main(
            [
                "vision-build-training-dataset",
                str(records_path),
                str(annotations_path),
                str(dataset_dir),
                "--dataset-version",
                "dataset-7",
                "--root",
                str(tmp_path),
            ]
        )
        == 0
    )
    assert (
        main(
            [
                "vision-train",
                str(dataset_dir / "training_spec.json"),
                str(run_dir),
                "--backend",
                "reference",
            ]
        )
        == 0
    )

    output_lines = capsys.readouterr().out.splitlines()
    assert output_lines[-1] == str(run_dir / "run_manifest.json")
    assert json.loads((run_dir / "run_manifest.json").read_text(encoding="utf-8"))[
        "status"
    ] == "completed"
