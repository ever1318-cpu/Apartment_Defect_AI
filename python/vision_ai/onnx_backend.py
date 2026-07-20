"""Optional ONNX Runtime adapter for the stable VisionBackend contract."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol, Sequence

from .image_io import inspect_image_file
from .models import BoundingBox, Classification, DefectDetection, ImageQuality


class OnnxSession(Protocol):
    def get_inputs(self) -> Sequence[Any]: ...

    def run(
        self, output_names: Sequence[str], input_feed: Mapping[str, Any]
    ) -> Sequence[Any]: ...


SessionFactory = Callable[[Path, Sequence[str] | None], OnnxSession]
InputLoader = Callable[[str], Any]

_OUTPUT_NAMES = (
    "quality",
    "space_scores",
    "trade_scores",
    "component_scores",
    "boxes",
    "detection_scores",
    "detection_labels",
)


def create_onnx_session(
    model_path: Path, providers: Sequence[str] | None = None
) -> OnnxSession:
    """Create a runtime session; importing onnxruntime is intentionally lazy."""
    try:
        import onnxruntime
    except ImportError as exc:
        raise RuntimeError(
            "ONNX backend requires optional dependencies; "
            "install with `pip install -e \".[onnx]\"`"
        ) from exc
    options: dict[str, Any] = {}
    if providers:
        options["providers"] = list(providers)
    return onnxruntime.InferenceSession(str(model_path), **options)


def load_onnx_image(image_path: str, size: tuple[int, int] = (224, 224)) -> Any:
    """Load an RGB image as normalized NCHW float32; dependencies are lazy."""
    inspect_image_file(image_path)
    try:
        import numpy
        from PIL import Image
    except ImportError as exc:
        raise RuntimeError(
            "ONNX image preprocessing requires numpy and Pillow; "
            "install with `pip install -e \".[onnx]\"`"
        ) from exc
    with Image.open(image_path) as image:
        rgb = image.convert("RGB").resize(size)
        array = numpy.asarray(rgb, dtype=numpy.float32) / 255.0
    return numpy.transpose(array, (2, 0, 1))[None, ...]


@dataclass(slots=True)
class OnnxVisionBackend:
    model_path: str | Path
    model_version: str = "onnx-1"
    providers: Sequence[str] | None = None
    deployment_profile: str = "cpu"
    session_factory: SessionFactory = create_onnx_session
    input_loader: InputLoader = load_onnx_image
    classification_labels: Mapping[str, tuple[str, ...]] = field(
        default_factory=lambda: {
            "space": ("bathroom", "kitchen", "living_room"),
            "trade": ("finishing", "plumbing", "electrical"),
            "component": ("wall", "floor", "ceiling"),
        }
    )
    detection_labels: tuple[str, ...] = ("crack", "leak", "surface_damage")
    backend_name: str = "onnx"
    _session: OnnxSession = field(init=False, repr=False)
    _input_name: str = field(init=False, repr=False)
    _cached_path: str | None = field(init=False, default=None, repr=False)
    _cached_outputs: dict[str, Any] | None = field(
        init=False, default=None, repr=False
    )

    def __post_init__(self) -> None:
        path = Path(self.model_path)
        if path.is_dir():
            from .model_package import load_package_configuration

            package = load_package_configuration(
                path, deployment_profile=self.deployment_profile
            )
            self.model_path = package["model_path"]
            self.model_version = package["model_version"]
            self.classification_labels = package["classification_labels"]
            self.detection_labels = package["detection_labels"]
            if self.providers is None:
                self.providers = package[
                    "deployment_profile"
                ].execution_providers
            preprocessing = package["preprocessing"].get(
                "image_preprocessing", {}
            )
            resize = preprocessing.get("resize")
            if resize and self.input_loader is load_onnx_image:
                size = tuple(int(value) for value in resize)
                self.input_loader = lambda image_path: load_onnx_image(
                    image_path, size=size
                )
            path = Path(self.model_path)
        if not path.is_file():
            raise FileNotFoundError(f"ONNX model file does not exist: {path}")
        if not self.model_version.strip():
            raise ValueError("model_version cannot be empty")
        self.model_path = path.resolve()
        self._session = self.session_factory(self.model_path, self.providers)
        inputs = tuple(self._session.get_inputs())
        if len(inputs) != 1 or not getattr(inputs[0], "name", None):
            raise ValueError("ONNX model must expose exactly one named input")
        self._input_name = str(inputs[0].name)

    def assess_quality(self, image_path: str) -> ImageQuality:
        row = _matrix("quality", self._infer(image_path)["quality"], columns=1)[0]
        score = float(row[0])
        return ImageQuality(score, score >= 0.5)

    def classify(
        self, image_path: str, task: str
    ) -> Sequence[Classification]:
        labels = self.classification_labels.get(task)
        if labels is None:
            raise ValueError(f"ONNX backend has no labels for task {task!r}")
        scores = _matrix(
            f"{task}_scores",
            self._infer(image_path)[f"{task}_scores"],
            columns=len(labels),
        )[0]
        return tuple(
            Classification(label, float(score))
            for label, score in zip(labels, scores)
        )

    def detect(self, image_path: str) -> Sequence[DefectDetection]:
        outputs = self._infer(image_path)
        boxes = _matrix("boxes", outputs["boxes"], columns=4)[0]
        scores = _matrix("detection_scores", outputs["detection_scores"])[0]
        labels = _matrix("detection_labels", outputs["detection_labels"])[0]
        if len(boxes) != len(scores) or len(scores) != len(labels):
            raise ValueError(
                "ONNX detection outputs must contain the same number of items"
            )
        detections: list[DefectDetection] = []
        for box, score, label_index in zip(boxes, scores, labels):
            index = int(label_index)
            if index < 0 or index >= len(self.detection_labels):
                raise ValueError(f"ONNX detection label index out of range: {index}")
            detections.append(
                DefectDetection(
                    self.detection_labels[index],
                    float(score),
                    BoundingBox(*(float(value) for value in box)),
                )
            )
        return tuple(detections)

    def _infer(self, image_path: str) -> dict[str, Any]:
        if image_path == self._cached_path and self._cached_outputs is not None:
            return self._cached_outputs
        tensor = self.input_loader(image_path)
        values = self._session.run(_OUTPUT_NAMES, {self._input_name: tensor})
        if len(values) != len(_OUTPUT_NAMES):
            raise ValueError(
                f"ONNX session returned {len(values)} outputs; "
                f"expected {len(_OUTPUT_NAMES)}"
            )
        outputs = dict(zip(_OUTPUT_NAMES, values))
        self._cached_path = image_path
        self._cached_outputs = outputs
        return outputs


def _matrix(name: str, value: Any, *, columns: int | None = None) -> list[list[Any]]:
    converted = value.tolist() if hasattr(value, "tolist") else value
    if (
        not isinstance(converted, (list, tuple))
        or len(converted) != 1
        or not isinstance(converted[0], (list, tuple))
    ):
        raise ValueError(f"ONNX output {name!r} must have batch shape [1, ...]")
    rows = [list(converted[0])]
    if columns is not None:
        if name == "boxes":
            if any(
                not isinstance(row, (list, tuple)) or len(row) != columns
                for row in rows[0]
            ):
                raise ValueError(f"ONNX output {name!r} must have shape [1, N, 4]")
        elif len(rows[0]) != columns:
            raise ValueError(
                f"ONNX output {name!r} must have shape [1, {columns}]"
            )
    return rows
