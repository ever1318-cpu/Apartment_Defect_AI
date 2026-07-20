from vision_ai.backends import CallableVisionBackend
from vision_ai.models import BoundingBox, Classification, DefectDetection, ImageQuality


def create_backend() -> CallableVisionBackend:
    return CallableVisionBackend(
        model_version="fixture-1",
        quality_fn=lambda path: ImageQuality(0.9, True),
        classification_fn=lambda path, task: (Classification(f"{task}-label", 0.8),),
        detection_fn=lambda path: (
            DefectDetection("crack", 0.9, BoundingBox(0.1, 0.1, 0.4, 0.4)),
        ),
    )
