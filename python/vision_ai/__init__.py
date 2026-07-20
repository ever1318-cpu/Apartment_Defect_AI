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
from .evaluation import EvaluationConfig, evaluate_predictions
from .evaluation_models import (
    ClassificationMetrics,
    DetectionMetrics,
    EvaluationIssue,
    EvaluationReport,
    GroundTruthAnnotation,
    LabelMetrics,
    SeverityMetrics,
)
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
from .model_package import (
    build_model_package,
    generate_checksums,
    load_package_configuration,
    validate_model_package,
)
from .package_models import (
    CompatibilityManifest,
    CompatibilityResult,
    DeploymentProfile,
    ModelManifest,
    ModelPackageValidation,
    default_deployment_profiles,
)
from .onnx_backend import OnnxVisionBackend, create_onnx_session
from .pipeline import PipelineConfig, VisionBackend, VisionPipeline
from .pytorch_training import (
    ONNX_OUTPUT_NAMES,
    PyTorchTrainingBackend,
    TrainingDatasetLoader,
    export_pytorch_checkpoint,
)
from .training import (
    ReferenceTrainingBackend,
    TrainingBackend,
    TrainingRunner,
    load_training_backend,
)
from .training_dataset import TrainingDatasetBuildResult, build_training_dataset
from .training_models import (
    LabelMapping,
    LabelVocabulary,
    MetricEntry,
    TrainingRunResult,
    TrainingSample,
    TrainingSpec,
    TrainingTasks,
)

__all__ = [
    "BoundingBox",
    "BackendRegistry",
    "CallableVisionBackend",
    "Classification",
    "CompatibilityManifest",
    "CompatibilityResult",
    "DefectDetection",
    "DEFAULT_BACKEND_REGISTRY",
    "DetectionMetrics",
    "DeploymentProfile",
    "EvaluationConfig",
    "EvaluationIssue",
    "EvaluationReport",
    "GroundTruthAnnotation",
    "ImageFileInfo",
    "ImageQuality",
    "InferenceFailure",
    "InferenceResult",
    "InferenceRunner",
    "InferenceSummary",
    "LabelMetrics",
    "LabelMapping",
    "LabelVocabulary",
    "MetricEntry",
    "ModelManifest",
    "ModelPackageValidation",
    "PipelineConfig",
    "OnnxVisionBackend",
    "PolygonMask",
    "PyTorchTrainingBackend",
    "ReferenceVisionBackend",
    "ReferenceTrainingBackend",
    "SeverityMetrics",
    "TrainingBackend",
    "TrainingDatasetLoader",
    "TrainingDatasetBuildResult",
    "TrainingRunner",
    "TrainingRunResult",
    "TrainingSample",
    "TrainingSpec",
    "TrainingTasks",
    "VisionBackend",
    "VisionPipeline",
    "VisionPrediction",
    "ONNX_OUTPUT_NAMES",
    "build_default_registry",
    "build_model_package",
    "build_training_dataset",
    "ClassificationMetrics",
    "create_backend",
    "create_onnx_session",
    "evaluate_predictions",
    "export_pytorch_checkpoint",
    "generate_checksums",
    "inspect_image_file",
    "load_backend",
    "load_package_configuration",
    "load_training_backend",
    "resolve_image_path",
    "validate_model_package",
    "default_deployment_profiles",
]
