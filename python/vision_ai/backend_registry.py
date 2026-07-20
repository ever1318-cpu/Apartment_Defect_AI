"""Named backend factories without coupling the domain pipeline to runtimes."""

from __future__ import annotations

from importlib import import_module
from typing import Any, Callable, cast

from .pipeline import VisionBackend

BackendFactory = Callable[..., VisionBackend]


class BackendRegistry:
    def __init__(self) -> None:
        self._factories: dict[str, BackendFactory] = {}

    def register(self, name: str, factory: BackendFactory) -> None:
        normalized = _backend_name(name)
        if normalized in self._factories:
            raise ValueError(f"backend already registered: {normalized}")
        if not callable(factory):
            raise ValueError("backend factory must be callable")
        self._factories[normalized] = factory

    def create(self, name: str, **options: Any) -> VisionBackend:
        normalized = _backend_name(name)
        factory = self._factories.get(normalized)
        if factory is not None:
            return _validate_backend(factory(**options))
        if ":" in name:
            return _load_dynamic_backend(name, options)
        available = ", ".join(self.names())
        raise ValueError(
            f"unknown backend {name!r}; available backends: {available or '<none>'}"
        )

    def names(self) -> tuple[str, ...]:
        return tuple(sorted(self._factories))


def _backend_name(name: str) -> str:
    normalized = name.strip().lower()
    if not normalized:
        raise ValueError("backend name cannot be empty")
    return normalized


def _load_dynamic_backend(
    specification: str, options: dict[str, Any]
) -> VisionBackend:
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
        if all(hasattr(value, member) for member in required)
        else value(**options)
        if callable(value)
        else value
    )
    return _validate_backend(backend)


def _validate_backend(value: object) -> VisionBackend:
    required = ("model_version", "assess_quality", "classify", "detect")
    missing = [name for name in required if not hasattr(value, name)]
    if missing:
        raise ValueError(f"backend is missing required members: {', '.join(missing)}")
    model_version = getattr(value, "model_version")
    if not isinstance(model_version, str) or not model_version.strip():
        raise ValueError("backend model_version must be a non-empty string")
    if any(not callable(getattr(value, name)) for name in required[1:]):
        raise ValueError("backend inference members must be callable")
    return cast(VisionBackend, value)


def build_default_registry() -> BackendRegistry:
    from .backends import ReferenceVisionBackend
    from .onnx_backend import OnnxVisionBackend

    registry = BackendRegistry()
    registry.register("reference", ReferenceVisionBackend)
    registry.register("onnx", OnnxVisionBackend)
    return registry


DEFAULT_BACKEND_REGISTRY = build_default_registry()
