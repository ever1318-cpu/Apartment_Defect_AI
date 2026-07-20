"""Framework-neutral Vision AI inference foundations."""

from .backends import CallableVisionBackend, ReferenceVisionBackend, load_backend
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
from .pipeline import PipelineConfig, VisionBackend, VisionPipeline

__all__ = [
    "BoundingBox",
    "CallableVisionBackend",
    "Classification",
    "DefectDetection",
    "ImageFileInfo",
    "ImageQuality",
    "InferenceFailure",
    "InferenceResult",
    "InferenceRunner",
    "InferenceSummary",
    "PipelineConfig",
    "PolygonMask",
    "ReferenceVisionBackend",
    "VisionBackend",
    "VisionPipeline",
    "VisionPrediction",
    "inspect_image_file",
    "load_backend",
    "resolve_image_path",
]
