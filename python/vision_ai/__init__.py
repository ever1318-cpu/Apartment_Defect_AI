"""Framework-neutral Vision AI inference foundations."""

from .backend_registry import (
    DEFAULT_BACKEND_REGISTRY,
    BackendRegistry,
    build_default_registry,
)
from .backends import (
    CallableVisionBackend,
    ReferenceVisionBackend,
    create_backend,
    load_backend,
)
from .image_io import ImageFileInfo, inspect_image_file, resolve_image_path
from .inference import (
    InferenceFailure,
    InferenceResult,
    InferenceRunner,
    InferenceSummary,
)
from .models import (
    BoundingBox,
    Classification,
    DefectDetection,
    ImageQuality,
    PolygonMask,
    VisionPrediction,
)
from .onnx_backend import OnnxVisionBackend, create_onnx_session
from .pipeline import PipelineConfig, VisionBackend, VisionPipeline

__all__ = [
    "BoundingBox",
    "BackendRegistry",
    "CallableVisionBackend",
    "Classification",
    "DefectDetection",
    "DEFAULT_BACKEND_REGISTRY",
    "ImageFileInfo",
    "ImageQuality",
    "InferenceFailure",
    "InferenceResult",
    "InferenceRunner",
    "InferenceSummary",
    "PipelineConfig",
    "OnnxVisionBackend",
    "PolygonMask",
    "ReferenceVisionBackend",
    "VisionBackend",
    "VisionPipeline",
    "VisionPrediction",
    "build_default_registry",
    "create_backend",
    "create_onnx_session",
    "inspect_image_file",
    "load_backend",
    "resolve_image_path",
]
