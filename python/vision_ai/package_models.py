"""Serializable model-package, compatibility, and deployment contracts."""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from typing import Any, Mapping

PACKAGE_FORMAT_VERSION = "1.0"
REQUIRED_PACKAGE_FILES = (
    "model.onnx",
    "model_manifest.json",
    "compatibility_manifest.json",
    "checksums.json",
    "label_mapping.json",
    "preprocessing.json",
    "deployment_profiles.json",
    "README.txt",
)
ONNX_OUTPUT_NAMES = (
    "quality",
    "space_scores",
    "trade_scores",
    "component_scores",
    "boxes",
    "detection_scores",
    "detection_labels",
)


@dataclass(frozen=True, slots=True)
class DeploymentProfile:
    name: str
    execution_providers: tuple[str, ...]
    expected_batch_size: int = 1
    latency_target_ms: float | None = None
    settings: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.name.strip() or not self.execution_providers:
            raise ValueError("deployment profile name and providers are required")
        if self.expected_batch_size <= 0:
            raise ValueError("expected_batch_size must be positive")
        if self.latency_target_ms is not None and self.latency_target_ms <= 0:
            raise ValueError("latency_target_ms must be positive")

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "execution_providers": list(self.execution_providers),
            "expected_batch_size": self.expected_batch_size,
            "latency_target_ms": self.latency_target_ms,
            "settings": dict(self.settings),
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "DeploymentProfile":
        return cls(
            name=str(value["name"]),
            execution_providers=tuple(value["execution_providers"]),
            expected_batch_size=int(value.get("expected_batch_size", 1)),
            latency_target_ms=value.get("latency_target_ms"),
            settings=dict(value.get("settings", {})),
        )


def default_deployment_profiles() -> dict[str, DeploymentProfile]:
    return {
        "cpu": DeploymentProfile(
            "cpu",
            ("CPUExecutionProvider",),
            settings={
                "intra_op_threads": 0,
                "inter_op_threads": 0,
                "graph_optimization_level": "all",
                "memory_arena": True,
            },
        ),
        "gpu": DeploymentProfile(
            "gpu",
            ("CUDAExecutionProvider", "CPUExecutionProvider"),
            settings={
                "allow_cpu_fallback": True,
                "device_id": 0,
                "gpu_memory_limit": None,
                "arena_extend_strategy": "next_power_of_two",
                "convolution_algorithm_search": "default",
            },
        ),
    }


@dataclass(frozen=True, slots=True)
class ModelManifest:
    package_format_version: str
    model_name: str
    model_version: str
    model_artifact: str
    model_artifact_format: str
    created_at: str
    source_training_run_id: str
    dataset_version: str
    framework: str
    framework_version: str
    onnx_opset: int
    dynamic_batch: bool
    input_contract: Mapping[str, Any]
    output_contract: tuple[Mapping[str, Any], ...]
    label_mapping_file: str
    preprocessing_file: str
    compatibility_manifest_file: str
    checksum_manifest_file: str
    deployment_profiles_file: str
    minimum_application_version: str
    metadata: Mapping[str, Any] = field(default_factory=dict)
    notes: str = ""

    def __post_init__(self) -> None:
        for value in (
            self.package_format_version,
            self.model_name,
            self.model_version,
            self.model_artifact,
            self.created_at,
            self.source_training_run_id,
            self.dataset_version,
        ):
            if not value.strip():
                raise ValueError("model manifest string fields cannot be empty")
        if self.model_artifact_format != "onnx":
            raise ValueError("only ONNX model packages are supported")
        if self.onnx_opset <= 0:
            raise ValueError("onnx_opset must be positive")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["output_contract"] = [dict(item) for item in self.output_contract]
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ModelManifest":
        return cls(
            **{
                **dict(value),
                "input_contract": dict(value["input_contract"]),
                "output_contract": tuple(
                    dict(item) for item in value["output_contract"]
                ),
                "metadata": dict(value.get("metadata", {})),
                "notes": value.get("notes", ""),
            }
        )


@dataclass(frozen=True, slots=True)
class CompatibilityManifest:
    python_min: str
    python_max: str
    onnxruntime_min: str
    cuda_provider_required: bool
    supported_execution_providers: tuple[str, ...]
    cpu_architectures: tuple[str, ...]
    operating_system_profiles: tuple[str, ...]
    input_dtype: str
    input_shape: tuple[int | str, ...]
    dynamic_dimensions: Mapping[str, str]
    outputs: tuple[Mapping[str, Any], ...]
    required_application_schema: str
    label_vocabulary_version: str
    preprocessing_version: str

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        for key in (
            "supported_execution_providers",
            "cpu_architectures",
            "operating_system_profiles",
            "input_shape",
            "outputs",
        ):
            value[key] = list(value[key])
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "CompatibilityManifest":
        return cls(
            python_min=value["python_min"],
            python_max=value["python_max"],
            onnxruntime_min=value["onnxruntime_min"],
            cuda_provider_required=bool(value["cuda_provider_required"]),
            supported_execution_providers=tuple(
                value["supported_execution_providers"]
            ),
            cpu_architectures=tuple(value["cpu_architectures"]),
            operating_system_profiles=tuple(value["operating_system_profiles"]),
            input_dtype=value["input_dtype"],
            input_shape=tuple(value["input_shape"]),
            dynamic_dimensions=dict(value["dynamic_dimensions"]),
            outputs=tuple(dict(item) for item in value["outputs"]),
            required_application_schema=value["required_application_schema"],
            label_vocabulary_version=value["label_vocabulary_version"],
            preprocessing_version=value["preprocessing_version"],
        )


@dataclass(frozen=True, slots=True)
class CompatibilityResult:
    check: str
    status: str
    message: str

    def __post_init__(self) -> None:
        if self.status not in {"pass", "warning", "fail"}:
            raise ValueError("compatibility status must be pass, warning, or fail")


@dataclass(frozen=True, slots=True)
class ChecksumResult:
    path: str
    status: str
    expected: str | None = None
    actual: str | None = None

    def __post_init__(self) -> None:
        if self.status not in {"match", "missing", "mismatch", "unexpected"}:
            raise ValueError("invalid checksum status")


@dataclass(frozen=True, slots=True)
class ModelPackageValidation:
    valid: bool
    errors: tuple[str, ...]
    warnings: tuple[str, ...]
    checksum_results: tuple[ChecksumResult, ...]
    compatibility_results: tuple[CompatibilityResult, ...]
    inspected_package_version: str | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "valid": self.valid,
            "errors": list(self.errors),
            "warnings": list(self.warnings),
            "checksum_results": [asdict(item) for item in self.checksum_results],
            "compatibility_results": [
                asdict(item) for item in self.compatibility_results
            ],
            "inspected_package_version": self.inspected_package_version,
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ModelPackageValidation":
        return cls(
            valid=bool(value["valid"]),
            errors=tuple(value.get("errors", ())),
            warnings=tuple(value.get("warnings", ())),
            checksum_results=tuple(
                ChecksumResult(**item)
                for item in value.get("checksum_results", ())
            ),
            compatibility_results=tuple(
                CompatibilityResult(**item)
                for item in value.get("compatibility_results", ())
            ),
            inspected_package_version=value.get("inspected_package_version"),
        )
