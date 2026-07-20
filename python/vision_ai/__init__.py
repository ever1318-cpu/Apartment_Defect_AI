"""Framework-neutral Vision AI inference foundations."""

from .backends import CallableVisionBackend, load_backend
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
    "ImageQuality",
    "InferenceFailure",
    "InferenceResult",
    "InferenceRunner",
    "InferenceSummary",
    "PipelineConfig",
    "PolygonMask",
    "VisionBackend",
    "VisionPipeline",
    "VisionPrediction",
    "load_backend",
]
