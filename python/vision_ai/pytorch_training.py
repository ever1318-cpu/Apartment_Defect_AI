"""Optional PyTorch training backend with lazy dependencies and ONNX export."""

from __future__ import annotations

import inspect
import math
import platform
import random
import time
from collections import Counter
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol, Sequence

from data_engineering.io import read_jsonl, write_json

from .evaluation_models import GroundTruthAnnotation
from .image_io import inspect_image_file
from .training_models import LabelMapping, MetricEntry, TrainingSpec

DEFAULT_CLASSIFICATION_TASKS = ("space", "trade", "component")


def onnx_output_names(label_sizes: Mapping[str, int]) -> tuple[str, ...]:
    tasks = sorted(
        key.removeprefix("classification:")
        for key in label_sizes
        if key.startswith("classification:")
    )
    if set(tasks) == set(DEFAULT_CLASSIFICATION_TASKS):
        tasks = list(DEFAULT_CLASSIFICATION_TASKS)
    return (
        "quality",
        *(f"{task}_scores" for task in tasks),
        "boxes",
        "detection_scores",
        "detection_labels",
    )


ONNX_OUTPUT_NAMES = onnx_output_names(
    {f"classification:{task}": 1 for task in DEFAULT_CLASSIFICATION_TASKS}
)


@dataclass(frozen=True, slots=True)
class PyTorchDependencies:
    torch: Any
    torchvision: Any
    image_module: Any
    numpy: Any


@dataclass(frozen=True, slots=True)
class EncodedTrainingSample:
    image_id: str
    image_path: Path
    classifications: Mapping[str, int]
    detections: tuple[Mapping[str, Any], ...]
    paired_image_path: Path | None = None


@dataclass(frozen=True, slots=True)
class PreparedTrainingData:
    splits: Mapping[str, tuple[EncodedTrainingSample, ...]]
    label_mapping: LabelMapping
    spec_directory: Path


class PyTorchEngine(Protocol):
    def train(self) -> Sequence[MetricEntry]: ...

    def validate(self, history: Sequence[MetricEntry]) -> Mapping[str, float]: ...

    def export(self, final_metrics: Mapping[str, float]) -> Mapping[str, Any]: ...


def load_pytorch_dependencies() -> PyTorchDependencies:
    """Import the optional training stack only when PyTorch is selected."""
    try:
        import numpy
        import torch
        import torchvision
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError(
            "PyTorch training requires optional dependencies; "
            "install with `pip install -e \".[pytorch]\"`"
        ) from exc
    return PyTorchDependencies(torch, torchvision, Image, numpy)


def resolve_torch_device(requested: str, torch_module: Any) -> str:
    normalized = requested.strip().lower()
    if normalized == "auto":
        return "cuda" if torch_module.cuda.is_available() else "cpu"
    if normalized == "cuda" and not torch_module.cuda.is_available():
        raise RuntimeError("CUDA was requested but is not available")
    if normalized not in ("cpu", "cuda"):
        raise ValueError("device must be auto, cpu, or cuda")
    return normalized


class TrainingDatasetLoader:
    """Validate and encode Sprint 2-5 training JSONL without ML dependencies."""

    def load(self, spec: TrainingSpec, spec_directory: str | Path) -> PreparedTrainingData:
        base = Path(spec_directory)
        mapping_path = _resolve(base, spec.label_mapping_path)
        if not mapping_path.is_file():
            raise FileNotFoundError(f"label mapping does not exist: {mapping_path}")
        import json

        mapping = LabelMapping.from_dict(
            json.loads(mapping_path.read_text(encoding="utf-8"))
        )
        splits: dict[str, tuple[EncodedTrainingSample, ...]] = {}
        for split, relative_path in spec.split_paths.items():
            path = _resolve(base, relative_path)
            if not path.is_file():
                raise FileNotFoundError(f"training split does not exist: {path}")
            items = tuple(
                self._sample(value, split, path.parent, mapping, spec)
                for value in read_jsonl(path)
            )
            splits[split] = items
        if not splits["train"]:
            raise ValueError("training split cannot be empty")
        return PreparedTrainingData(splits, mapping, base.resolve())

    def _sample(
        self,
        value: Mapping[str, Any],
        expected_split: str,
        split_directory: Path,
        mapping: LabelMapping,
        spec: TrainingSpec,
    ) -> EncodedTrainingSample:
        try:
            image_id = value["image_id"]
            if value["split"] != expected_split:
                raise ValueError(
                    f"sample {image_id!r} declares split {value['split']!r}, "
                    f"expected {expected_split!r}"
                )
            image_path = _resolve(split_directory, value["image_path"]).resolve()
            inspect_image_file(image_path)
            paired_value = value.get("paired_image_path")
            paired_image_path = (
                _resolve(split_directory, paired_value).resolve()
                if paired_value else None
            )
            if paired_image_path is not None:
                inspect_image_file(paired_image_path)
            annotation = GroundTruthAnnotation.from_dict(value["annotation"])
            if annotation.image_id != image_id:
                raise ValueError(f"sample {image_id!r} annotation image_id differs")
            classifications = {}
            if spec.tasks.classification:
                for task in spec.tasks.classification_tasks:
                    vocabulary = mapping.tasks[f"classification:{task}"]
                    classifications[task] = vocabulary.encode(
                        annotation.classifications[task]
                    )
            detections = []
            if spec.tasks.detection:
                detection_vocabulary = mapping.tasks["detection"]
                severity_vocabulary = (
                    mapping.tasks["severity"] if spec.tasks.severity else None
                )
                for item in annotation.detections:
                    if spec.tasks.severity and item.severity is None:
                        raise ValueError(
                            f"sample {image_id!r} detection severity is required"
                        )
                    detections.append(
                        {
                            "label": detection_vocabulary.encode(item.label),
                            "box": (
                                item.box.x_min,
                                item.box.y_min,
                                item.box.x_max,
                                item.box.y_max,
                            ),
                            "severity": (
                                severity_vocabulary.encode(item.severity)
                                if severity_vocabulary is not None
                                and item.severity is not None
                                else None
                            ),
                        }
                    )
            return EncodedTrainingSample(
                image_id=image_id,
                image_path=image_path,
                classifications=classifications,
                detections=tuple(detections),
                paired_image_path=paired_image_path,
            )
        except (KeyError, TypeError) as exc:
            raise ValueError(f"invalid training sample: {exc}") from exc


def create_torch_dataloaders(
    data: PreparedTrainingData,
    spec: TrainingSpec,
    dependencies: PyTorchDependencies,
) -> Mapping[str, Any]:
    """Create DataLoaders separately from JSON validation and backend orchestration."""
    torch = dependencies.torch
    transforms = dependencies.torchvision.transforms
    resize = tuple(spec.image_preprocessing.get("resize", (224, 224)))
    common = [
        transforms.Resize(resize),
        transforms.ToTensor(),
    ]
    if spec.model_architecture == "convnext_tiny":
        common.append(
            transforms.Normalize(
                mean=(0.485, 0.456, 0.406),
                std=(0.229, 0.224, 0.225),
            )
        )
    evaluation_transform = transforms.Compose(common)
    training_transform = (
        transforms.Compose(
            [
                transforms.RandomResizedCrop(resize, scale=(0.75, 1.0)),
                transforms.RandomHorizontalFlip(),
                transforms.ColorJitter(brightness=0.15, contrast=0.15),
                transforms.ToTensor(),
                *(
                    [
                        transforms.Normalize(
                            mean=(0.485, 0.456, 0.406),
                            std=(0.229, 0.224, 0.225),
                        )
                    ]
                    if spec.model_architecture == "convnext_tiny"
                    else []
                ),
            ]
        )
        if spec.augmentation.get("enabled", False)
        else evaluation_transform
    )

    class Dataset(torch.utils.data.Dataset):
        def __init__(self, samples: Sequence[EncodedTrainingSample], transform: Any):
            self.samples = samples
            self.transform = transform

        def __len__(self):
            return len(self.samples)

        def __getitem__(self, index):
            sample = self.samples[index]
            try:
                with dependencies.image_module.open(sample.image_path) as image:
                    tensor = self.transform(image.convert("RGB"))
            except Exception as exc:
                raise ValueError(f"cannot load training image: {sample.image_path}") from exc
            paired = None
            if sample.paired_image_path is not None:
                try:
                    with dependencies.image_module.open(sample.paired_image_path) as image:
                        paired = evaluation_transform(image.convert("RGB"))
                except Exception as exc:
                    raise ValueError(
                        f"cannot load paired training image: {sample.paired_image_path}"
                    ) from exc
            return tensor, paired, sample

    def collate(batch):
        if not batch:
            raise ValueError("invalid empty training batch")
        images, paired, targets = zip(*batch)
        paired_batch = (
            torch.stack(paired)
            if all(item is not None for item in paired)
            else None
        )
        return torch.stack(images), paired_batch, targets

    generator = torch.Generator().manual_seed(spec.random_seed)
    return {
        split: torch.utils.data.DataLoader(
            Dataset(
                samples,
                training_transform if split == "train" else evaluation_transform,
            ),
            batch_size=spec.batch_size,
            shuffle=split == "train",
            collate_fn=collate,
            generator=generator,
        )
        for split, samples in data.splits.items()
    }


def build_tiny_vision_model(
    dependencies: PyTorchDependencies,
    label_sizes: Mapping[str, int],
    *,
    architecture: str = "tiny_cnn",
    pretrained: bool = False,
) -> Any:
    """Build a dynamic multi-task CNN or ConvNeXt-Tiny."""
    torch = dependencies.torch
    nn = torch.nn

    class MultiTaskVisionModel(nn.Module):
        def __init__(self):
            super().__init__()
            if architecture == "convnext_tiny":
                weights = (
                    dependencies.torchvision.models.ConvNeXt_Tiny_Weights.DEFAULT
                    if pretrained else None
                )
                base = dependencies.torchvision.models.convnext_tiny(weights=weights)
                self.backbone = base.features
                self.pool = base.avgpool
                self.feature_norm = base.classifier[0]
                feature_size = int(base.classifier[2].in_features)
            elif architecture == "tiny_cnn":
                self.backbone = nn.Sequential(
                    nn.Conv2d(3, 16, 3, padding=1),
                    nn.ReLU(),
                    nn.MaxPool2d(2),
                    nn.Conv2d(16, 32, 3, padding=1),
                    nn.ReLU(),
                    nn.MaxPool2d(2),
                    nn.Conv2d(32, 64, 3, padding=1),
                    nn.ReLU(),
                )
                self.pool = nn.AdaptiveAvgPool2d((1, 1))
                self.feature_norm = nn.Identity()
                feature_size = 64
            else:
                raise ValueError(f"unsupported model architecture: {architecture}")
            self.quality_head = nn.Linear(feature_size, 1)
            classification_tasks = sorted(
                key.removeprefix("classification:")
                for key in label_sizes
                if key.startswith("classification:")
            )
            if set(classification_tasks) == set(DEFAULT_CLASSIFICATION_TASKS):
                classification_tasks = list(DEFAULT_CLASSIFICATION_TASKS)
            self.classification_tasks = tuple(classification_tasks)
            self.classification_heads = nn.ModuleDict(
                {
                    task: nn.Linear(
                        feature_size,
                        max(1, label_sizes.get(f"classification:{task}", 1)),
                    )
                    for task in self.classification_tasks
                }
            )
            self.box_head = nn.Linear(feature_size, 4)
            self.detection_head = nn.Linear(
                feature_size, max(1, label_sizes.get("detection", 1))
            )
            self.severity_head = nn.Linear(
                feature_size, max(1, label_sizes.get("severity", 1))
            )

        def _features(self, images):
            values = self.pool(self.backbone(images))
            values = self.feature_norm(values)
            return values.flatten(1)

        def forward_training(self, images):
            features = self._features(images)
            return {
                "quality": torch.sigmoid(self.quality_head(features)),
                "classifications": {
                    task: head(features)
                    for task, head in self.classification_heads.items()
                },
                "boxes": torch.sigmoid(self.box_head(features)),
                "detection_logits": self.detection_head(features),
                "severity_logits": self.severity_head(features),
            }

        def forward(self, images):
            values = self.forward_training(images)
            raw_boxes = values["boxes"]
            minimum = raw_boxes[:, :2] * 0.5
            maximum = torch.clamp(
                minimum + raw_boxes[:, 2:] * (1 - minimum), max=1.0
            )
            boxes = torch.cat((minimum, maximum), dim=1).unsqueeze(1)
            detection_probabilities = torch.softmax(
                values["detection_logits"], dim=1
            )
            detection_scores, detection_labels = torch.max(
                detection_probabilities, dim=1, keepdim=True
            )
            return (
                values["quality"],
                *(
                    torch.softmax(values["classifications"][task], dim=1)
                    for task in self.classification_tasks
                ),
                boxes,
                detection_scores,
                detection_labels,
            )

    return MultiTaskVisionModel()


class TorchTrainingEngine:
    def __init__(
        self,
        dependencies: PyTorchDependencies,
        data: PreparedTrainingData,
        spec: TrainingSpec,
        device: str,
        run_directory: Path,
    ):
        self.dependencies = dependencies
        self.data = data
        self.spec = spec
        self.device = device
        self.run_directory = run_directory
        self.loaders = create_torch_dataloaders(data, spec, dependencies)
        _seed_everything(dependencies.torch, spec.random_seed)
        label_sizes = {
            task: len(vocabulary.labels)
            for task, vocabulary in data.label_mapping.tasks.items()
        }
        self.label_sizes = label_sizes
        self.output_names = onnx_output_names(label_sizes)
        self.class_weights = self._class_weights()
        self.model = build_tiny_vision_model(
            dependencies,
            label_sizes,
            architecture=spec.model_architecture,
            pretrained=spec.pretrained,
        ).to(device)
        self.best_epoch = 0
        self.best_accuracy = -1.0
        self.best_loss = math.inf

    def train(self) -> Sequence[MetricEntry]:
        torch = self.dependencies.torch
        optimizer = torch.optim.Adam(
            self.model.parameters(), lr=self.spec.learning_rate
        )
        history = []
        for epoch in range(1, self.spec.epochs + 1):
            train_loss, _ = self._epoch(self.loaders["train"], optimizer)
            validation_loss, validation_accuracy = self._epoch(
                self.loaders["validation"], None
            )
            if not all(
                math.isfinite(value)
                for value in (train_loss, validation_loss, validation_accuracy)
            ):
                raise ValueError("training produced NaN or infinite metrics")
            entry = MetricEntry(
                epoch,
                {
                    "train_loss": train_loss,
                    "validation_loss": validation_loss,
                    "validation_accuracy": validation_accuracy,
                },
            )
            history.append(entry)
            checkpoint = self._checkpoint(epoch, entry.metrics)
            torch.save(checkpoint, self.run_directory / "model.pt")
            if (
                validation_accuracy > self.best_accuracy
                or (
                    validation_accuracy == self.best_accuracy
                    and validation_loss < self.best_loss
                )
            ):
                self.best_epoch = epoch
                self.best_accuracy = validation_accuracy
                self.best_loss = validation_loss
                torch.save(checkpoint, self.run_directory / "best-model.pt")
        return tuple(history)

    def validate(self, history: Sequence[MetricEntry]) -> Mapping[str, float]:
        if not history:
            raise ValueError("metric history cannot be empty")
        return {
            "best_validation_accuracy": self.best_accuracy,
            "best_validation_loss": self.best_loss,
            "best_epoch": float(self.best_epoch),
            **self._classification_metrics(self.loaders["test"]),
        }

    def export(self, final_metrics: Mapping[str, float]) -> Mapping[str, Any]:
        checkpoint = self.run_directory / "best-model.pt"
        output = self.run_directory / "model.onnx"
        metadata = export_pytorch_checkpoint(
            checkpoint,
            output,
            opset=int(self.spec.onnx_export.get("opset", 17)),
            dynamic_batch=bool(self.spec.onnx_export.get("dynamic_batch", True)),
            input_shape=tuple(
                self.spec.onnx_export.get("input_shape", (1, 3, 224, 224))
            ),
            dependencies=self.dependencies,
        )
        write_json(
            self.run_directory / "checkpoint_metadata.json",
            {
                "latest": "model.pt",
                "best": "best-model.pt",
                "best_epoch": self.best_epoch,
                "selection_policy": (
                    "highest_validation_accuracy_then_lowest_validation_loss"
                ),
            },
        )
        write_json(self.run_directory / "export_metadata.json", metadata)
        write_json(
            self.run_directory / "environment_metadata.json",
            {
                "python": platform.python_version(),
                "torch": str(self.dependencies.torch.__version__),
                "torchvision": str(self.dependencies.torchvision.__version__),
                "device": self.device,
                "random_seed": self.spec.random_seed,
            },
        )
        return {
            **metadata,
            "checkpoint": "best-model.pt",
            "final_metrics": dict(final_metrics),
        }

    def _epoch(self, loader: Any, optimizer: Any | None) -> tuple[float, float]:
        torch = self.dependencies.torch
        training = optimizer is not None
        self.model.train(training)
        total_loss = 0.0
        correct = 0
        classified = 0
        batches = 0
        context = torch.enable_grad() if training else torch.no_grad()
        with context:
            for images, paired_images, targets in loader:
                if images.ndim != 4 or not targets:
                    raise ValueError("invalid training batch")
                images = images.to(self.device)
                outputs = self.model.forward_training(images)
                loss = torch.zeros((), device=self.device)
                for task in self.spec.tasks.classification_tasks:
                    if not self.spec.tasks.classification:
                        break
                    target = torch.tensor(
                        [item.classifications[task] for item in targets],
                        device=self.device,
                    )
                    logits = outputs["classifications"][task]
                    loss = loss + torch.nn.functional.cross_entropy(
                        logits, target, weight=self.class_weights[task]
                    )
                    correct += int((logits.argmax(1) == target).sum().item())
                    classified += len(targets)
                if (
                    paired_images is not None
                    and self.spec.consistency_weight > 0
                    and self.spec.tasks.classification
                ):
                    paired_outputs = self.model.forward_training(
                        paired_images.to(self.device)
                    )
                    for task in self.spec.tasks.classification_tasks:
                        primary_probability = torch.softmax(
                            outputs["classifications"][task], dim=1
                        )
                        paired_probability = torch.softmax(
                            paired_outputs["classifications"][task], dim=1
                        )
                        loss = loss + self.spec.consistency_weight * (
                            torch.nn.functional.mse_loss(
                                primary_probability, paired_probability
                            )
                        )
                detection_rows = [
                    (index, item.detections[0])
                    for index, item in enumerate(targets)
                    if item.detections
                ]
                if self.spec.tasks.detection and detection_rows:
                    indices = torch.tensor(
                        [index for index, _ in detection_rows], device=self.device
                    )
                    boxes = torch.tensor(
                        [item["box"] for _, item in detection_rows],
                        dtype=torch.float32,
                        device=self.device,
                    )
                    labels = torch.tensor(
                        [item["label"] for _, item in detection_rows],
                        device=self.device,
                    )
                    loss = loss + torch.nn.functional.mse_loss(
                        outputs["boxes"][indices], boxes
                    )
                    loss = loss + torch.nn.functional.cross_entropy(
                        outputs["detection_logits"][indices], labels
                    )
                    if self.spec.tasks.severity:
                        severity = torch.tensor(
                            [item["severity"] for _, item in detection_rows],
                            device=self.device,
                        )
                        loss = loss + torch.nn.functional.cross_entropy(
                            outputs["severity_logits"][indices], severity
                        )
                if not torch.isfinite(loss):
                    raise ValueError("training loss is NaN or infinite")
                if training:
                    optimizer.zero_grad()
                    loss.backward()
                    optimizer.step()
                total_loss += float(loss.detach().cpu().item())
                batches += 1
        if batches == 0:
            if training:
                raise ValueError("training split cannot be empty")
            return 0.0, 0.0
        return total_loss / batches, correct / classified if classified else 0.0

    def _class_weights(self) -> dict[str, Any]:
        torch = self.dependencies.torch
        samples = self.data.splits["train"]
        weights = {}
        for task in self.spec.tasks.classification_tasks:
            counts = Counter(item.classifications[task] for item in samples)
            size = self.label_sizes[f"classification:{task}"]
            total = sum(counts.values())
            values = [
                min(
                    10.0,
                    math.sqrt(total / max(size * counts.get(index, 0), 1)),
                )
                for index in range(size)
            ]
            weights[task] = torch.tensor(
                values, dtype=torch.float32, device=self.device
            )
        return weights

    def _classification_metrics(self, loader: Any) -> dict[str, float]:
        torch = self.dependencies.torch
        self.model.eval()
        actual: dict[str, list[int]] = {
            task: [] for task in self.spec.tasks.classification_tasks
        }
        predicted: dict[str, list[int]] = {
            task: [] for task in self.spec.tasks.classification_tasks
        }
        top3_correct = Counter()
        with torch.no_grad():
            for images, _, targets in loader:
                outputs = self.model.forward_training(images.to(self.device))
                for task in self.spec.tasks.classification_tasks:
                    logits = outputs["classifications"][task]
                    target = torch.tensor(
                        [item.classifications[task] for item in targets],
                        device=self.device,
                    )
                    guess = logits.argmax(1)
                    actual[task].extend(target.cpu().tolist())
                    predicted[task].extend(guess.cpu().tolist())
                    width = min(3, logits.shape[1])
                    top = logits.topk(width, dim=1).indices
                    top3_correct[task] += int(
                        (top == target.unsqueeze(1)).any(dim=1).sum().item()
                    )
        metrics: dict[str, float] = {}
        for task in self.spec.tasks.classification_tasks:
            pairs = list(zip(actual[task], predicted[task]))
            metrics[f"test_{task}_accuracy"] = (
                sum(a == p for a, p in pairs) / len(pairs) if pairs else 0.0
            )
            labels = range(self.label_sizes[f"classification:{task}"])
            f1_values = []
            for label in labels:
                tp = sum(a == label and p == label for a, p in pairs)
                fp = sum(a != label and p == label for a, p in pairs)
                fn = sum(a == label and p != label for a, p in pairs)
                precision = tp / (tp + fp) if tp + fp else 0.0
                recall = tp / (tp + fn) if tp + fn else 0.0
                f1_values.append(
                    2 * precision * recall / (precision + recall)
                    if precision + recall else 0.0
                )
            metrics[f"test_{task}_macro_f1"] = (
                sum(f1_values) / len(f1_values) if f1_values else 0.0
            )
            metrics[f"test_{task}_top3_accuracy"] = (
                top3_correct[task] / len(pairs) if pairs else 0.0
            )
        return metrics

    def _checkpoint(
        self, epoch: int, metrics: Mapping[str, float]
    ) -> Mapping[str, Any]:
        return {
            "epoch": epoch,
            "model_state": self.model.state_dict(),
            "label_sizes": self.label_sizes,
            "training_spec": self.spec.to_dict(),
            "metrics": dict(metrics),
            "output_names": list(self.output_names),
            "model_architecture": self.spec.model_architecture,
        }


class PyTorchTrainingBackend:
    backend_name = "pytorch"

    def __init__(
        self,
        *,
        device: str = "auto",
        dependency_loader: Callable[[], PyTorchDependencies] = load_pytorch_dependencies,
        dataset_loader: TrainingDatasetLoader | None = None,
        engine_factory: Callable[..., PyTorchEngine] = TorchTrainingEngine,
    ):
        self.requested_device = device
        self.dependency_loader = dependency_loader
        self.dataset_loader = dataset_loader or TrainingDatasetLoader()
        self.engine_factory = engine_factory
        self._engine: PyTorchEngine | None = None
        self._started = 0.0

    def prepare(
        self, spec: TrainingSpec, spec_directory: Path
    ) -> Mapping[str, Any]:
        dependencies = self.dependency_loader()
        device = resolve_torch_device(self.requested_device, dependencies.torch)
        data = self.dataset_loader.load(spec, spec_directory)
        self._started = time.perf_counter()
        return {
            "dependencies": dependencies,
            "device": device,
            "data": data,
            "artifacts": [],
        }

    def train(
        self, prepared: Mapping[str, Any], spec: TrainingSpec
    ) -> Sequence[MetricEntry]:
        run_directory = Path(prepared["run_directory"])
        self._engine = self.engine_factory(
            prepared["dependencies"],
            prepared["data"],
            spec,
            prepared["device"],
            run_directory,
        )
        history = tuple(self._engine.train())
        if not history:
            raise ValueError("PyTorch training returned empty metric history")
        if any(
            not math.isfinite(value)
            for item in history
            for value in item.metrics.values()
        ):
            raise ValueError("PyTorch training returned NaN or infinite metrics")
        prepared["artifacts"].extend(["model.pt", "best-model.pt"])
        return history

    def validate(
        self,
        prepared: Mapping[str, Any],
        history: Sequence[MetricEntry],
        spec: TrainingSpec,
    ) -> Mapping[str, float]:
        if self._engine is None:
            raise RuntimeError("training engine is not prepared")
        return self._engine.validate(history)

    def export(
        self,
        prepared: Mapping[str, Any],
        final_metrics: Mapping[str, float],
        spec: TrainingSpec,
    ) -> Mapping[str, Any]:
        if self._engine is None:
            raise RuntimeError("training engine is not prepared")
        metadata = dict(self._engine.export(final_metrics))
        metadata["training_duration_seconds"] = time.perf_counter() - self._started
        prepared["artifacts"].extend(
            [
                "model.onnx",
                "checkpoint_metadata.json",
                "export_metadata.json",
                "environment_metadata.json",
            ]
        )
        return metadata


def export_pytorch_checkpoint(
    checkpoint_path: str | Path,
    output_path: str | Path,
    *,
    opset: int = 17,
    dynamic_batch: bool = True,
    input_shape: tuple[int, ...] = (1, 3, 224, 224),
    dependencies: PyTorchDependencies | None = None,
    checkpoint_loader: Callable[[Path], Mapping[str, Any]] | None = None,
    model_builder: Callable[..., Any] = (
        build_tiny_vision_model
    ),
    exporter: Callable[..., None] | None = None,
    checker: Callable[[Path], None] | None = None,
) -> dict[str, Any]:
    checkpoint = Path(checkpoint_path)
    output = Path(output_path)
    if not checkpoint.is_file():
        raise FileNotFoundError(f"checkpoint does not exist: {checkpoint}")
    deps = dependencies or load_pytorch_dependencies()
    load = checkpoint_loader or (
        lambda path: deps.torch.load(path, map_location="cpu", weights_only=False)
    )
    value = load(checkpoint)
    for key in ("model_state", "label_sizes", "output_names"):
        if key not in value:
            raise ValueError(f"checkpoint is missing {key!r}")
    output_names = (
        onnx_output_names(value["label_sizes"])
        if any(
            key.startswith("classification:") for key in value["label_sizes"]
        )
        else tuple(value["output_names"])
    )
    if tuple(value["output_names"]) != output_names:
        raise ValueError("checkpoint output names do not match ONNX contract")
    architecture = value.get(
        "model_architecture",
        value.get("training_spec", {}).get("model_architecture", "tiny_cnn"),
    )
    if model_builder is build_tiny_vision_model:
        model = model_builder(
            deps,
            value["label_sizes"],
            architecture=architecture,
            pretrained=False,
        )
    else:
        model = model_builder(deps, value["label_sizes"])
    model.load_state_dict(value["model_state"])
    model.eval()
    dummy = deps.torch.zeros(input_shape, dtype=deps.torch.float32)
    output.parent.mkdir(parents=True, exist_ok=True)
    dynamic_axes = (
        {
            "images": {0: "batch"},
            **{name: {0: "batch"} for name in output_names},
        }
        if dynamic_batch
        else None
    )
    export = exporter or deps.torch.onnx.export
    export_options: dict[str, Any] = {
        "input_names": ["images"],
        "output_names": list(output_names),
        "dynamic_axes": dynamic_axes,
        "opset_version": opset,
    }
    if exporter is None and "external_data" in inspect.signature(export).parameters:
        # Model packages intentionally contain one portable ONNX artifact.
        export_options["external_data"] = False
    export(model, dummy, str(output), **export_options)
    if not output.is_file() or output.stat().st_size == 0:
        raise RuntimeError("ONNX export did not create a non-empty model file")
    if checker is not None:
        checker(output)
    else:
        _check_onnx_model(output)
    return {
        "format": "onnx",
        "path": output.name,
        "opset": opset,
        "dynamic_batch": dynamic_batch,
        "input_name": "images",
        "input_shape": list(input_shape),
        "output_names": list(output_names),
        "source_checkpoint": checkpoint.name,
        "model_architecture": architecture,
    }


def _check_onnx_model(path: Path) -> None:
    try:
        import onnx
    except ImportError as exc:
        raise RuntimeError(
            "ONNX export validation requires optional dependency 'onnx'; "
            "install with `pip install -e \".[pytorch]\"`"
        ) from exc
    onnx.checker.check_model(onnx.load(str(path)))


def _seed_everything(torch: Any, seed: int) -> None:
    random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    if hasattr(torch, "use_deterministic_algorithms"):
        torch.use_deterministic_algorithms(True)


def _resolve(base: Path, value: str) -> Path:
    path = Path(value)
    return path if path.is_absolute() else base / path
