"""Framework-neutral contracts for training datasets and runs."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Mapping

from .evaluation_models import GroundTruthAnnotation


@dataclass(frozen=True, slots=True)
class TrainingTasks:
    classification: bool = True
    detection: bool = True
    severity: bool = True
    classification_tasks: tuple[str, ...] = ("space", "trade", "component")

    def __post_init__(self) -> None:
        if not any((self.classification, self.detection, self.severity)):
            raise ValueError("at least one training task must be enabled")
        if self.severity and not self.detection:
            raise ValueError("severity training requires detection")
        if self.classification and (
            not self.classification_tasks
            or any(not task.strip() for task in self.classification_tasks)
        ):
            raise ValueError("classification_tasks must contain non-empty tasks")

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "TrainingTasks":
        return cls(
            classification=value.get("classification", True),
            detection=value.get("detection", True),
            severity=value.get("severity", True),
            classification_tasks=tuple(
                value.get("classification_tasks", ("space", "trade", "component"))
            ),
        )


@dataclass(frozen=True, slots=True)
class LabelVocabulary:
    labels: tuple[str, ...]
    unknown_policy: str = "error"

    def __post_init__(self) -> None:
        if self.unknown_policy != "error":
            raise ValueError("only unknown_policy='error' is supported")
        if len(set(self.labels)) != len(self.labels):
            raise ValueError("vocabulary labels cannot contain duplicates")
        if tuple(sorted(self.labels)) != self.labels:
            raise ValueError("vocabulary labels must use stable sorted order")
        if any(not label.strip() or _reserved(label) for label in self.labels):
            raise ValueError("vocabulary labels cannot be empty or reserved")

    @property
    def label_to_index(self) -> dict[str, int]:
        return {label: index for index, label in enumerate(self.labels)}

    @property
    def index_to_label(self) -> dict[str, str]:
        return {str(index): label for index, label in enumerate(self.labels)}

    def encode(self, label: str) -> int:
        try:
            return self.label_to_index[label]
        except KeyError as exc:
            raise ValueError(f"unknown label {label!r}") from exc

    def to_dict(self) -> dict[str, Any]:
        return {
            "labels": list(self.labels),
            "label_to_index": self.label_to_index,
            "index_to_label": self.index_to_label,
            "unknown_policy": self.unknown_policy,
            "reserved_labels": [],
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "LabelVocabulary":
        vocabulary = cls(
            labels=tuple(value["labels"]),
            unknown_policy=value.get("unknown_policy", "error"),
        )
        if value.get("label_to_index", vocabulary.label_to_index) != vocabulary.label_to_index:
            raise ValueError("label_to_index does not match stable label order")
        if value.get("index_to_label", vocabulary.index_to_label) != vocabulary.index_to_label:
            raise ValueError("index_to_label does not match stable label order")
        return vocabulary


@dataclass(frozen=True, slots=True)
class LabelMapping:
    tasks: Mapping[str, LabelVocabulary]

    def to_dict(self) -> dict[str, Any]:
        return {
            "tasks": {
                task: vocabulary.to_dict()
                for task, vocabulary in sorted(self.tasks.items())
            }
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "LabelMapping":
        return cls(
            tasks={
                task: LabelVocabulary.from_dict(item)
                for task, item in value["tasks"].items()
            }
        )


@dataclass(frozen=True, slots=True)
class TrainingSample:
    image_id: str
    image_path: str
    group_id: str
    split: str
    annotation: GroundTruthAnnotation

    def to_dict(self) -> dict[str, Any]:
        return {
            "image_id": self.image_id,
            "image_path": self.image_path,
            "group_id": self.group_id,
            "split": self.split,
            "annotation": self.annotation.to_dict(),
        }


@dataclass(frozen=True, slots=True)
class TrainingSpec:
    dataset_version: str
    tasks: TrainingTasks
    split_paths: Mapping[str, str]
    label_mapping_path: str
    image_preprocessing: Mapping[str, Any]
    augmentation: Mapping[str, Any]
    batch_size: int
    epochs: int
    learning_rate: float
    random_seed: int
    output_directory: str
    model_artifact_name: str
    onnx_export: Mapping[str, Any] = field(
        default_factory=lambda: {
            "opset": 17,
            "dynamic_batch": True,
            "input_shape": [1, 3, 224, 224],
        }
    )

    def __post_init__(self) -> None:
        if not self.dataset_version.strip():
            raise ValueError("dataset_version cannot be empty")
        if set(self.split_paths) != {"train", "validation", "test"}:
            raise ValueError("split_paths must contain train, validation, and test")
        if self.batch_size <= 0 or self.epochs <= 0:
            raise ValueError("batch_size and epochs must be positive")
        if self.learning_rate <= 0:
            raise ValueError("learning_rate must be positive")
        if (
            not self.label_mapping_path.strip()
            or not self.output_directory.strip()
            or not self.model_artifact_name.strip()
        ):
            raise ValueError("artifact paths cannot be empty")
        artifact = Path(self.model_artifact_name)
        if artifact.is_absolute() or len(artifact.parts) != 1:
            raise ValueError("model_artifact_name must be a relative file name")
        opset = self.onnx_export.get("opset", 17)
        input_shape = self.onnx_export.get("input_shape", [1, 3, 224, 224])
        if not isinstance(opset, int) or opset <= 0:
            raise ValueError("ONNX opset must be a positive integer")
        if (
            not isinstance(input_shape, (list, tuple))
            or len(input_shape) != 4
            or any(not isinstance(value, int) or value <= 0 for value in input_shape)
        ):
            raise ValueError("ONNX input_shape must contain four positive integers")

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "TrainingSpec":
        return cls(
            dataset_version=value["dataset_version"],
            tasks=TrainingTasks.from_dict(value["tasks"]),
            split_paths=dict(value["split_paths"]),
            label_mapping_path=value["label_mapping_path"],
            image_preprocessing=dict(value["image_preprocessing"]),
            augmentation=dict(value["augmentation"]),
            batch_size=value["batch_size"],
            epochs=value["epochs"],
            learning_rate=value["learning_rate"],
            random_seed=value["random_seed"],
            output_directory=value["output_directory"],
            model_artifact_name=value["model_artifact_name"],
            onnx_export=dict(
                value.get(
                    "onnx_export",
                    {
                        "opset": 17,
                        "dynamic_batch": True,
                        "input_shape": [1, 3, 224, 224],
                    },
                )
            ),
        )


@dataclass(frozen=True, slots=True)
class MetricEntry:
    epoch: int
    metrics: Mapping[str, float]


@dataclass(frozen=True, slots=True)
class TrainingRunResult:
    run_id: str
    created_at: str
    status: str
    run_directory: str
    manifest_path: str
    final_metrics: Mapping[str, float] = field(default_factory=dict)
    error_type: str | None = None
    error: str | None = None


def _reserved(label: str) -> bool:
    return label.startswith("__") and label.endswith("__")
