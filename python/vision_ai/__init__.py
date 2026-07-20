"""Framework-neutral Vision AI inference foundations."""

from .models import (
    BoundingBox,
    Classification,
    DefectDetection,
    ImageQuality,
    PolygonMask,
    VisionPrediction,
)
from .pipeline import VisionPipeline

__all__ = [
    "BoundingBox",
    "Classification",
    "DefectDetection",
    "ImageQuality",
    "PolygonMask",
    "VisionPipeline",
    "VisionPrediction",
]
