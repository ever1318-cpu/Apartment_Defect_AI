"""Optional PyTorch training backend with lazy dependencies and ONNX export."""

from __future__ import annotations

import math
import platform
import random
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol, Sequence

from data_engineering.io import read_jsonl, write_json

from .evaluation_models import GroundTruthAnnotation
from .image_io import inspect_image_file
from .training_models import LabelMapping, MetricEntry, TrainingSpec

ONNX_OUTPUT_NAMES = (
    "quality",
    "space_scores",
    "trade_scores",
    "component_scores",
    "boxes",
    "detection_scores",
    "detection_labels",
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
    transform = transforms.Compose([transforms.Resize(resize), transforms.ToTensor()])

    class Dataset(torch.utils.data.Dataset):
        def __init__(self, samples: Sequence[EncodedTrainingSample]):
            self.samples = samples

        def __len__(self):
            return len(self.samples)

        def __getitem__(self, index):
            sample = self.samples[index]
            try:
                with dependencies.image_module.open(sample.image_path) as image:
                    tensor = transform(image.convert("RGB"))
            except Exception as exc:
                raise ValueError(f"cannot load training image: {sample.image_path}") from exc
            return tensor, sample

    def collate(batch):
        if not batch:
            raise ValueError("invalid empty training batch")
        images, targets = zip(*batch)
        return torch.stack(images), targets

    generator = torch.Generator().manual_seed(spec.random_seed)
    return {
        split: torch.utils.data.DataLoader(
            Dataset(samples),
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
) -> Any:
    """Build a small CNN locally; no pretrained weights or downloads are used."""
    torch = dependencies.torch
    nn = torch.nn

    class TinyVisionModel(nn.Module):
        def __init__(self):
            super().__init__()
            self.backbone = nn.Sequential(
                nn.Conv2d(3, 16, 3, padding=1),
                nn.ReLU(),
                nn.MaxPool2d(2),
                nn.Conv2d(16, 32, 3, padding=1),
                nn.ReLU(),
                nn.MaxPool2d(2),
                nn.Conv2d(32, 64, 3, padding=1),
                nn.ReLU(),
                nn.AdaptiveAvgPool2d((1, 1)),
            )
            self.quality_head = nn.Linear(64, 1)
            self.classification_heads = nn.ModuleDict(
                {
                    task: nn.Linear(64, max(1, label_sizes.get(f"classification:{task}", 1)))
                    for task in ("space", "trade", "component")
                }
            )
            self.box_head = nn.Linear(64, 4)
            self.detection_head = nn.Linear(
                64, max(1, label_sizes.get("detection", 1))
            )
            self.severity_head = nn.Linear(
                64, max(1, label_sizes.get("severity", 1))
            )

        def _features(self, images):
            return self.backbone(images).flatten(1)

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
                torch.softmax(values["classifications"]["space"], dim=1),
                torch.softmax(values["classifications"]["trade"], dim=1),
                torch.softmax(values["classifications"]["component"], dim=1),
                boxes,
                detection_scores,
                detection_labels,
            )

    return TinyVisionModel()


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
        self.model = build_tiny_vision_model(dependencies, label_sizes).to(device)
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
            for images, targets in loader:
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
                    loss = loss + torch.nn.functional.cross_entropy(logits, target)
                    correct += int((logits.argmax(1) == target).sum().item())
                    classified += len(targets)
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

    def _checkpoint(
        self, epoch: int, metrics: Mapping[str, float]
    ) -> Mapping[str, Any]:
        return {
            "epoch": epoch,
            "model_state": self.model.state_dict(),
            "label_sizes": self.label_sizes,
            "training_spec": self.spec.to_dict(),
            "metrics": dict(metrics),
            "output_names": list(ONNX_OUTPUT_NAMES),
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
    model_builder: Callable[[PyTorchDependencies, Mapping[str, int]], Any] = (
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
    if tuple(value["output_names"]) != ONNX_OUTPUT_NAMES:
        raise ValueError("checkpoint output names do not match ONNX contract")
    model = model_builder(deps, value["label_sizes"])
    model.load_state_dict(value["model_state"])
    model.eval()
    dummy = deps.torch.zeros(input_shape, dtype=deps.torch.float32)
    output.parent.mkdir(parents=True, exist_ok=True)
    dynamic_axes = (
        {
            "images": {0: "batch"},
            **{name: {0: "batch"} for name in ONNX_OUTPUT_NAMES},
        }
        if dynamic_batch
        else None
    )
    export = exporter or deps.torch.onnx.export
    export(
        model,
        dummy,
        str(output),
        input_names=["images"],
        output_names=list(ONNX_OUTPUT_NAMES),
        dynamic_axes=dynamic_axes,
        opset_version=opset,
    )
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
        "output_names": list(ONNX_OUTPUT_NAMES),
        "source_checkpoint": checkpoint.name,
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
