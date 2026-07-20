"""Small adapters and loading helpers for framework-neutral Vision backends."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from importlib import import_module
from pathlib import Path
from typing import Callable, Sequence, cast

from .image_io import inspect_image_file
from .models import (
    BoundingBox,
    Classification,
    DefectDetection,
    ImageQuality,
    PolygonMask,
)
from .pipeline import VisionBackend


@dataclass(frozen=True, slots=True)
class CallableVisionBackend:
    """Adapt plain callables without depending on a model framework."""

    model_version: str
    quality_fn: Callable[[str], ImageQuality]
    classification_fn: Callable[[str, str], Sequence[Classification]]
    detection_fn: Callable[[str], Sequence[DefectDetection]]

    def __post_init__(self) -> None:
        if not self.model_version.strip():
            raise ValueError("model_version cannot be empty")

    def assess_quality(self, image_path: str) -> ImageQuality:
        return self.quality_fn(image_path)

    def classify(
        self, image_path: str, task: str
    ) -> Sequence[Classification]:
        return self.classification_fn(image_path, task)

    def detect(self, image_path: str) -> Sequence[DefectDetection]:
        return self.detection_fn(image_path)


@dataclass(frozen=True, slots=True)
class ReferenceVisionBackend:
    """Deterministic file-backed backend for end-to-end workflow verification."""

    model_version: str = "reference-1"
    minimum_quality_bytes: int = 12
    backend_name: str = "reference"

    def __post_init__(self) -> None:
        if not self.model_version.strip():
            raise ValueError("model_version cannot be empty")
        if self.minimum_quality_bytes <= 0:
            raise ValueError("minimum_quality_bytes must be positive")

    def assess_quality(self, image_path: str) -> ImageQuality:
        info = inspect_image_file(image_path)
        acceptable = info.size_bytes >= self.minimum_quality_bytes
        return ImageQuality(
            score=min(1.0, info.size_bytes / self.minimum_quality_bytes),
            acceptable=acceptable,
            issues=() if acceptable else ("file_too_small",),
        )

    def classify(
        self, image_path: str, task: str
    ) -> Sequence[Classification]:
        digest = _image_digest(image_path)
        labels = {
            "space": ("bathroom", "kitchen", "living_room"),
            "trade": ("finishing", "plumbing", "electrical"),
            "component": ("wall", "floor", "ceiling"),
        }.get(task, (f"{task}_unknown",))
        index = digest[0] % len(labels)
        confidence = 0.55 + digest[1] / 255 * 0.4
        return (Classification(labels[index], confidence),)

    def detect(self, image_path: str) -> Sequence[DefectDetection]:
        digest = _image_digest(image_path)
        x_min = 0.05 + digest[2] / 255 * 0.20
        y_min = 0.05 + digest[3] / 255 * 0.20
        width = 0.20 + digest[4] / 255 * 0.25
        height = 0.20 + digest[5] / 255 * 0.25
        x_max, y_max = min(0.95, x_min + width), min(0.95, y_min + height)
        box = BoundingBox(x_min, y_min, x_max, y_max)
        mask = PolygonMask(
            (
                (box.x_min, box.y_min),
                (box.x_max, box.y_min),
                (box.x_max, box.y_max),
                (box.x_min, box.y_max),
            )
        )
        labels = ("crack", "leak", "surface_damage")
        return (
            DefectDetection(
                labels[digest[6] % len(labels)],
                0.60 + digest[7] / 255 * 0.35,
                box,
                mask,
            ),
        )


def _image_digest(image_path: str) -> bytes:
    info = inspect_image_file(image_path)
    return hashlib.sha256(Path(info.path).read_bytes()).digest()


def load_backend(specification: str) -> VisionBackend:
    """Load a backend instance or zero-argument factory from ``module:attribute``."""
    if specification == "reference":
        return ReferenceVisionBackend()
    module_name, separator, attribute_name = specification.partition(":")
    if not separator or not module_name or not attribute_name:
        raise ValueError("backend must use module:attribute syntax")
    try:
        value = getattr(import_module(module_name), attribute_name)
    except (ImportError, AttributeError) as exc:
        raise ValueError(f"cannot load backend {specification!r}: {exc}") from exc
    required = ("model_version", "assess_quality", "classify", "detect")
    backend = (
        value
        if all(hasattr(value, name) for name in required)
        else value()
        if callable(value)
        else value
    )
    missing = [name for name in required if not hasattr(backend, name)]
    if missing:
        raise ValueError(f"backend is missing required members: {', '.join(missing)}")
    if not isinstance(backend.model_version, str) or not backend.model_version.strip():
        raise ValueError("backend model_version must be a non-empty string")
    if any(not callable(getattr(backend, name)) for name in required[1:]):
        raise ValueError("backend inference members must be callable")
    return cast(VisionBackend, backend)
