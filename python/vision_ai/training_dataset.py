"""Build deterministic training inputs from canonical records and annotations."""

from __future__ import annotations

import os
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Iterable

from data_engineering.io import write_json, write_jsonl
from data_engineering.models import ImageRecord

from .evaluation_models import GroundTruthAnnotation
from .image_io import inspect_image_file
from .training_models import (
    LabelMapping,
    LabelVocabulary,
    TrainingSample,
    TrainingSpec,
    TrainingTasks,
)

_SPLITS = ("train", "validation", "test")


@dataclass(frozen=True, slots=True)
class TrainingDatasetBuildResult:
    output_directory: str
    manifest_path: str
    training_spec_path: str
    sample_counts: dict[str, int]


def build_training_dataset(
    records: Iterable[ImageRecord],
    annotations: Iterable[GroundTruthAnnotation],
    output_directory: str | Path,
    *,
    dataset_version: str,
    tasks: TrainingTasks | None = None,
    image_root: str | Path | None = None,
) -> TrainingDatasetBuildResult:
    settings = tasks or TrainingTasks()
    record_items = list(records)
    annotation_items = list(annotations)
    output = Path(output_directory)
    if output.exists():
        raise FileExistsError(f"training dataset directory already exists: {output}")
    if not dataset_version.strip():
        raise ValueError("dataset_version cannot be empty")
    root = Path(image_root) if image_root is not None else Path.cwd()

    _validate_unique_ids(record_items, annotation_items)
    records_by_id = {item.image_id: item for item in record_items}
    annotations_by_id = {item.image_id: item for item in annotation_items}
    missing = sorted(set(records_by_id) - set(annotations_by_id))
    unknown = sorted(set(annotations_by_id) - set(records_by_id))
    if missing:
        raise ValueError(f"missing annotations for image_ids: {missing}")
    if unknown:
        raise ValueError(f"annotations contain unknown image_ids: {unknown}")
    mismatched_versions = sorted(
        item.image_id
        for item in annotation_items
        if item.dataset_version not in ("unknown", dataset_version)
    )
    if mismatched_versions:
        raise ValueError(
            f"annotations have a different dataset_version: {mismatched_versions}"
        )
    _validate_splits_and_files(record_items, root)

    samples: dict[str, list[TrainingSample]] = {name: [] for name in _SPLITS}
    for record in sorted(record_items, key=lambda item: item.image_id):
        annotation = _select_annotation(annotations_by_id[record.image_id], settings)
        resolved_image = inspect_image_file(record.image_path, root=root).path
        portable_image_path = Path(
            os.path.relpath(resolved_image, output.resolve())
        ).as_posix()
        samples[record.split].append(
            TrainingSample(
                image_id=record.image_id,
                image_path=portable_image_path,
                group_id=record.group_id,
                split=record.split,
                annotation=annotation,
            )
        )

    if not samples["train"]:
        raise ValueError("training split cannot be empty")
    mapping = _build_label_mapping(samples["train"], settings)
    _validate_known_labels(samples, mapping, settings)
    statistics = _dataset_statistics(samples, settings)
    split_paths = {name: f"{name}.jsonl" for name in _SPLITS}
    spec = TrainingSpec(
        dataset_version=dataset_version,
        tasks=settings,
        split_paths=split_paths,
        label_mapping_path="label_mapping.json",
        image_preprocessing={
            "color_mode": "RGB",
            "resize": [224, 224],
            "normalize": "0_to_1",
        },
        augmentation={"enabled": True, "train_only": True},
        batch_size=16,
        epochs=5,
        learning_rate=0.001,
        random_seed=42,
        output_directory="runs",
        model_artifact_name="model-artifact.json",
    )

    output.mkdir(parents=True, exist_ok=False)
    for split, items in samples.items():
        write_jsonl(output / split_paths[split], (item.to_dict() for item in items))
    write_json(output / "label_mapping.json", mapping.to_dict())
    write_json(output / "training_spec.json", spec.to_dict())
    source_root = Path(os.path.relpath(root.resolve(), output.resolve())).as_posix()
    manifest = {
        "dataset_version": dataset_version,
        "source_root": source_root,
        "tasks": asdict(settings),
        "split_paths": split_paths,
        "label_mapping_path": "label_mapping.json",
        "training_spec_path": "training_spec.json",
        "statistics": statistics,
    }
    manifest_path = output / "dataset_manifest.json"
    write_json(manifest_path, manifest)
    return TrainingDatasetBuildResult(
        output_directory=str(output),
        manifest_path=str(manifest_path),
        training_spec_path=str(output / "training_spec.json"),
        sample_counts={name: len(items) for name, items in samples.items()},
    )


def _validate_unique_ids(
    records: list[ImageRecord], annotations: list[GroundTruthAnnotation]
) -> None:
    for name, items in (("record", records), ("annotation", annotations)):
        counts = Counter(item.image_id for item in items)
        duplicates = sorted(image_id for image_id, count in counts.items() if count > 1)
        if duplicates:
            raise ValueError(f"duplicate {name} image_ids: {duplicates}")


def _validate_splits_and_files(records: list[ImageRecord], root: Path) -> None:
    split_by_group: dict[str, set[str]] = defaultdict(set)
    for record in records:
        if record.split not in _SPLITS:
            raise ValueError(f"record {record.image_id!r} has no training split")
        split_by_group[record.group_id].add(record.split)
        inspect_image_file(record.image_path, root=root)
    leaking = sorted(group for group, splits in split_by_group.items() if len(splits) > 1)
    if leaking:
        raise ValueError(f"group leakage across splits: {leaking}")


def _select_annotation(
    annotation: GroundTruthAnnotation, tasks: TrainingTasks
) -> GroundTruthAnnotation:
    classifications = (
        {
            task: annotation.classifications[task]
            for task in tasks.classification_tasks
            if task in annotation.classifications
        }
        if tasks.classification
        else {}
    )
    if tasks.classification:
        missing = [
            task
            for task in tasks.classification_tasks
            if task not in annotation.classifications
        ]
        if missing:
            raise ValueError(
                f"annotation {annotation.image_id!r} is missing tasks {missing}"
            )
    detections = annotation.detections if tasks.detection else ()
    if tasks.severity:
        if any(item.severity is None for item in detections):
            raise ValueError(
                f"annotation {annotation.image_id!r} has missing severity"
            )
    elif detections:
        detections = tuple(replace(item, severity=None) for item in detections)
    return GroundTruthAnnotation(
        image_id=annotation.image_id,
        classifications=classifications,
        detections=detections,
        dataset_version=annotation.dataset_version,
    )


def _build_label_mapping(
    train_samples: list[TrainingSample], tasks: TrainingTasks
) -> LabelMapping:
    labels: dict[str, set[str]] = defaultdict(set)
    for sample in train_samples:
        if tasks.classification:
            for task, label in sample.annotation.classifications.items():
                labels[f"classification:{task}"].add(label)
        if tasks.detection:
            labels["detection"].update(
                item.label for item in sample.annotation.detections
            )
        if tasks.severity:
            labels["severity"].update(
                item.severity
                for item in sample.annotation.detections
                if item.severity is not None
            )
    expected = []
    if tasks.classification:
        expected.extend(f"classification:{task}" for task in tasks.classification_tasks)
    if tasks.detection:
        expected.append("detection")
    if tasks.severity:
        expected.append("severity")
    return LabelMapping(
        tasks={
            task: LabelVocabulary(tuple(sorted(labels[task])))
            for task in expected
        }
    )


def _validate_known_labels(
    samples: dict[str, list[TrainingSample]],
    mapping: LabelMapping,
    tasks: TrainingTasks,
) -> None:
    for split in ("validation", "test"):
        for sample in samples[split]:
            labels: list[tuple[str, str]] = []
            if tasks.classification:
                labels.extend(
                    (f"classification:{task}", label)
                    for task, label in sample.annotation.classifications.items()
                )
            if tasks.detection:
                labels.extend(
                    ("detection", item.label)
                    for item in sample.annotation.detections
                )
            if tasks.severity:
                labels.extend(
                    ("severity", item.severity)
                    for item in sample.annotation.detections
                    if item.severity is not None
                )
            for task, label in labels:
                try:
                    mapping.tasks[task].encode(label)
                except ValueError as exc:
                    raise ValueError(
                        f"{split} sample {sample.image_id!r}: {exc} for task {task}"
                    ) from exc


def _dataset_statistics(
    samples: dict[str, list[TrainingSample]], tasks: TrainingTasks
) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for split, items in samples.items():
        distributions: dict[str, Counter[str]] = defaultdict(Counter)
        for sample in items:
            if tasks.classification:
                for task, label in sample.annotation.classifications.items():
                    distributions[f"classification:{task}"][label] += 1
            if tasks.detection:
                distributions["detection"].update(
                    item.label for item in sample.annotation.detections
                )
            if tasks.severity:
                distributions["severity"].update(
                    item.severity
                    for item in sample.annotation.detections
                    if item.severity is not None
                )
        result[split] = {
            "sample_count": len(items),
            "label_distribution": {
                task: dict(sorted(counts.items()))
                for task, counts in sorted(distributions.items())
            },
        }
    return result
