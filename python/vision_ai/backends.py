"""Small adapters and loading helpers for framework-neutral Vision backends."""

from __future__ import annotations

from dataclasses import dataclass
from importlib import import_module
from typing import Callable, Sequence, cast

from .models import Classification, DefectDetection, ImageQuality
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


def load_backend(specification: str) -> VisionBackend:
    """Load a backend instance or zero-argument factory from ``module:attribute``."""
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
