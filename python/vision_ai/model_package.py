"""Build and validate portable, checksummed Vision model packages."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import tempfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, Mapping, Sequence

from data_engineering.io import write_json

from .package_models import (
    ONNX_OUTPUT_NAMES,
    PACKAGE_FORMAT_VERSION,
    REQUIRED_PACKAGE_FILES,
    ChecksumResult,
    CompatibilityManifest,
    CompatibilityResult,
    DeploymentProfile,
    ModelManifest,
    ModelPackageValidation,
    default_deployment_profiles,
)
from .training_models import LabelMapping, TrainingSpec

APPLICATION_VERSION = "0.9.0"
CHECKSUM_ALGORITHM = "sha256"


def build_model_package(
    training_run_directory: str | Path,
    output_package_directory: str | Path,
    model_name: str,
    model_version: str,
    *,
    created_at: str | None = None,
    minimum_application_version: str = APPLICATION_VERSION,
    deployment_profiles: Mapping[str, DeploymentProfile] | None = None,
    notes: str = "",
) -> Path:
    """Create a complete package in a sibling temp directory, then rename it."""
    source = Path(training_run_directory)
    output = Path(output_package_directory)
    if output.exists():
        raise FileExistsError(f"model package directory already exists: {output}")
    if not source.is_dir():
        raise FileNotFoundError(f"training run directory does not exist: {source}")

    run_manifest = _read_json_file(source / "run_manifest.json", "training manifest")
    if run_manifest.get("status") != "completed":
        raise ValueError("training run manifest must have completed status")
    export = _read_json_file(source / "export_metadata.json", "export metadata")
    environment = _read_json_file(
        source / "environment_metadata.json", "environment metadata"
    )
    training_spec = TrainingSpec.from_dict(
        _read_json_file(source / "training_spec.json", "training spec")
    )
    LabelMapping.from_dict(
        _read_json_file(source / "label_mapping.json", "label mapping")
    )
    model_source = source / "model.onnx"
    if not model_source.is_file():
        raise FileNotFoundError(f"ONNX model does not exist: {model_source}")
    _reject_symlink(model_source)

    output.parent.mkdir(parents=True, exist_ok=True)
    temp = Path(
        tempfile.mkdtemp(prefix=f".{output.name}.tmp-", dir=str(output.parent))
    )
    try:
        _copy_regular(model_source, temp / "model.onnx")
        _copy_regular(source / "label_mapping.json", temp / "label_mapping.json")
        write_json(
            temp / "preprocessing.json",
            {
                "version": "1.0",
                "image_preprocessing": dict(training_spec.image_preprocessing),
            },
        )
        profiles = deployment_profiles or default_deployment_profiles()
        write_json(
            temp / "deployment_profiles.json",
            {
                "version": "1.0",
                "profiles": {
                    name: profile.to_dict()
                    for name, profile in sorted(profiles.items())
                },
            },
        )

        input_shape = tuple(export.get("input_shape", (1, 3, 224, 224)))
        dynamic_batch = bool(export.get("dynamic_batch", True))
        serialized_shape: tuple[int | str, ...] = (
            ("batch", *input_shape[1:]) if dynamic_batch else input_shape
        )
        outputs = _output_contracts(dynamic_batch)
        compatibility = CompatibilityManifest(
            python_min="3.11",
            python_max="3.13",
            onnxruntime_min="1.18",
            cuda_provider_required=False,
            supported_execution_providers=(
                "CPUExecutionProvider",
                "CUDAExecutionProvider",
            ),
            cpu_architectures=("x86_64", "AMD64", "arm64"),
            operating_system_profiles=("Windows", "Linux", "Darwin"),
            input_dtype="float32",
            input_shape=serialized_shape,
            dynamic_dimensions={"0": "batch"} if dynamic_batch else {},
            outputs=outputs,
            required_application_schema="vision-prediction-1",
            label_vocabulary_version="1.0",
            preprocessing_version="1.0",
        )
        write_json(temp / "compatibility_manifest.json", compatibility.to_dict())
        manifest = ModelManifest(
            package_format_version=PACKAGE_FORMAT_VERSION,
            model_name=model_name,
            model_version=model_version,
            model_artifact="model.onnx",
            model_artifact_format="onnx",
            created_at=created_at or datetime.now(timezone.utc).isoformat(),
            source_training_run_id=str(run_manifest["run_id"]),
            dataset_version=str(run_manifest["dataset_version"]),
            framework="pytorch",
            framework_version=str(environment.get("torch", "unknown")),
            onnx_opset=int(export.get("opset", 17)),
            dynamic_batch=dynamic_batch,
            input_contract={
                "name": str(export.get("input_name", "images")),
                "dtype": "float32",
                "shape": list(serialized_shape),
            },
            output_contract=outputs,
            label_mapping_file="label_mapping.json",
            preprocessing_file="preprocessing.json",
            compatibility_manifest_file="compatibility_manifest.json",
            checksum_manifest_file="checksums.json",
            deployment_profiles_file="deployment_profiles.json",
            minimum_application_version=minimum_application_version,
            metadata={"source_backend": run_manifest.get("backend", "pytorch")},
            notes=notes,
        )
        write_json(temp / "model_manifest.json", manifest.to_dict())
        (temp / "README.txt").write_text(
            f"{model_name} {model_version}\n"
            "Portable Apartment Defect AI ONNX model package.\n"
            "Validate checksums and compatibility before inference.\n",
            encoding="utf-8",
        )
        write_json(
            temp / "checksums.json",
            {
                "algorithm": CHECKSUM_ALGORITHM,
                "files": generate_checksums(temp),
            },
        )
        result = validate_model_package(temp, strict=True)
        if not result.valid:
            raise ValueError(
                "generated model package is invalid: " + "; ".join(result.errors)
            )
        os.replace(temp, output)
        return output / "model_manifest.json"
    except Exception:
        shutil.rmtree(temp, ignore_errors=True)
        raise


def generate_checksums(package_directory: str | Path) -> dict[str, str]:
    root = Path(package_directory)
    values: dict[str, str] = {}
    for path in sorted(root.iterdir(), key=lambda item: item.name):
        if path.name == "checksums.json":
            continue
        _reject_symlink(path)
        if not path.is_file():
            raise ValueError(f"package entries must be regular files: {path.name}")
        relative = _safe_relative(path.name)
        values[relative] = _sha256(path)
    return values


def validate_model_package(
    package_directory: str | Path,
    *,
    strict: bool = False,
    runtime: Mapping[str, Any] | None = None,
) -> ModelPackageValidation:
    root = Path(package_directory)
    errors: list[str] = []
    warnings: list[str] = []
    checksum_results: list[ChecksumResult] = []
    compatibility_results: list[CompatibilityResult] = []
    version: str | None = None
    if not root.is_dir():
        return ModelPackageValidation(
            False,
            (f"model package directory does not exist: {root}",),
            (),
            (),
            (),
            None,
        )

    entries: set[str] = set()
    for path in root.iterdir():
        if path.is_symlink():
            errors.append(f"symbolic links are not allowed: {path.name}")
            continue
        if not path.is_file():
            errors.append(f"package entries must be regular files: {path.name}")
            continue
        try:
            entries.add(_safe_relative(path.name))
        except ValueError as exc:
            errors.append(str(exc))
    for required in REQUIRED_PACKAGE_FILES:
        if required not in entries:
            errors.append(f"missing required file: {required}")

    expected_entries = set(REQUIRED_PACKAGE_FILES)
    for extra in sorted(entries - expected_entries):
        message = f"unexpected package file: {extra}"
        (errors if strict else warnings).append(message)

    manifest: ModelManifest | None = None
    compatibility: CompatibilityManifest | None = None
    try:
        manifest = ModelManifest.from_dict(
            _read_json_file(root / "model_manifest.json", "model manifest")
        )
        version = manifest.package_format_version
    except Exception as exc:
        errors.append(f"invalid model manifest: {exc}")
    try:
        compatibility = CompatibilityManifest.from_dict(
            _read_json_file(
                root / "compatibility_manifest.json", "compatibility manifest"
            )
        )
    except Exception as exc:
        errors.append(f"invalid compatibility manifest: {exc}")
    try:
        mapping = LabelMapping.from_dict(
            _read_json_file(root / "label_mapping.json", "label mapping")
        )
        if not mapping.tasks:
            raise ValueError("label mapping must contain tasks")
    except Exception as exc:
        errors.append(f"invalid label mapping: {exc}")
    try:
        profile_data = _read_json_file(
            root / "deployment_profiles.json", "deployment profiles"
        )
        profiles = {
            name: DeploymentProfile.from_dict(value)
            for name, value in profile_data["profiles"].items()
        }
        if {"cpu", "gpu"} - set(profiles):
            raise ValueError("cpu and gpu deployment profiles are required")
    except Exception as exc:
        errors.append(f"invalid deployment profiles: {exc}")

    if manifest is not None:
        references = {
            manifest.model_artifact,
            manifest.label_mapping_file,
            manifest.preprocessing_file,
            manifest.compatibility_manifest_file,
            manifest.checksum_manifest_file,
            manifest.deployment_profiles_file,
        }
        for reference in sorted(references):
            try:
                safe = _safe_relative(reference)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            if safe not in entries:
                errors.append(f"manifest referenced file is missing: {safe}")
        if compatibility is not None:
            output_names = tuple(item.get("name") for item in manifest.output_contract)
            compatibility_names = tuple(
                item.get("name") for item in compatibility.outputs
            )
            if output_names != ONNX_OUTPUT_NAMES:
                errors.append("model output contract does not match ONNX backend")
            if output_names != compatibility_names:
                errors.append("manifest output contracts do not match")
            if manifest.input_contract.get("dtype") != compatibility.input_dtype:
                errors.append("manifest input dtype contracts do not match")
            if tuple(manifest.input_contract.get("shape", ())) != tuple(
                compatibility.input_shape
            ):
                errors.append("manifest input shape contracts do not match")
        application_status = (
            "pass"
            if _version_tuple(APPLICATION_VERSION)
            >= _version_tuple(manifest.minimum_application_version)
            else "fail"
        )
        compatibility_results.append(
            CompatibilityResult(
                "application_version",
                application_status,
                (
                    f"application {APPLICATION_VERSION} satisfies minimum "
                    f"{manifest.minimum_application_version}"
                    if application_status == "pass"
                    else f"application {APPLICATION_VERSION} is below minimum "
                    f"{manifest.minimum_application_version}"
                ),
            )
        )

    try:
        checksum_data = _read_json_file(root / "checksums.json", "checksums")
        if checksum_data.get("algorithm") != CHECKSUM_ALGORITHM:
            raise ValueError("checksum algorithm must be sha256")
        tracked = checksum_data["files"]
        if not isinstance(tracked, dict):
            raise ValueError("checksum files must be an object")
        for relative, expected in sorted(tracked.items()):
            try:
                safe = _safe_relative(relative)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            path = root / safe
            if not path.is_file():
                checksum_results.append(
                    ChecksumResult(safe, "missing", str(expected), None)
                )
                errors.append(f"checksum tracked file is missing: {safe}")
                continue
            actual = _sha256(path)
            status = "match" if actual == expected else "mismatch"
            checksum_results.append(
                ChecksumResult(safe, status, str(expected), actual)
            )
            if status == "mismatch":
                errors.append(f"checksum mismatch: {safe}")
        untracked = entries - set(tracked) - {"checksums.json"}
        for relative in sorted(untracked):
            checksum_results.append(ChecksumResult(relative, "unexpected"))
            message = f"file is not tracked by checksums: {relative}"
            (errors if strict else warnings).append(message)
    except Exception as exc:
        errors.append(f"invalid checksum manifest: {exc}")

    if compatibility is not None:
        compatibility_results.extend(
            check_runtime_compatibility(compatibility, runtime=runtime)
        )
        for result in compatibility_results:
            if result.status == "fail":
                errors.append(result.message)
            elif result.status == "warning":
                warnings.append(result.message)
    return ModelPackageValidation(
        not errors,
        tuple(dict.fromkeys(errors)),
        tuple(dict.fromkeys(warnings)),
        tuple(checksum_results),
        tuple(compatibility_results),
        version,
    )


def check_runtime_compatibility(
    manifest: CompatibilityManifest,
    *,
    runtime: Mapping[str, Any] | None = None,
) -> tuple[CompatibilityResult, ...]:
    current = dict(runtime or {})
    python_version = str(current.get("python_version", platform.python_version()))
    machine = str(current.get("cpu_architecture", platform.machine()))
    system = str(current.get("operating_system", platform.system()))
    providers = tuple(current.get("execution_providers", ("CPUExecutionProvider",)))
    onnxruntime_version = current.get("onnxruntime_version")
    results = [
        _range_result(
            "python",
            python_version,
            manifest.python_min,
            manifest.python_max,
        ),
        CompatibilityResult(
            "cpu_architecture",
            "pass" if machine in manifest.cpu_architectures else "fail",
            (
                f"CPU architecture {machine} is supported"
                if machine in manifest.cpu_architectures
                else f"CPU architecture {machine} is not supported"
            ),
        ),
        CompatibilityResult(
            "operating_system",
            "pass" if system in manifest.operating_system_profiles else "warning",
            (
                f"operating system {system} is supported"
                if system in manifest.operating_system_profiles
                else f"operating system {system} is not a tested profile"
            ),
        ),
    ]
    supported = set(manifest.supported_execution_providers)
    available = supported.intersection(providers)
    results.append(
        CompatibilityResult(
            "execution_provider",
            "pass" if available else "fail",
            (
                f"supported execution provider available: {sorted(available)[0]}"
                if available
                else "no supported execution provider is available"
            ),
        )
    )
    if manifest.cuda_provider_required and "CUDAExecutionProvider" not in providers:
        results.append(
            CompatibilityResult(
                "cuda_provider", "fail", "CUDAExecutionProvider is required"
            )
        )
    if onnxruntime_version is None:
        results.append(
            CompatibilityResult(
                "onnxruntime",
                "warning",
                "ONNX Runtime version could not be inspected",
            )
        )
    else:
        status = (
            "pass"
            if _version_tuple(str(onnxruntime_version))
            >= _version_tuple(manifest.onnxruntime_min)
            else "fail"
        )
        results.append(
            CompatibilityResult(
                "onnxruntime",
                status,
                (
                    f"ONNX Runtime {onnxruntime_version} satisfies minimum "
                    f"{manifest.onnxruntime_min}"
                    if status == "pass"
                    else f"ONNX Runtime {onnxruntime_version} is below minimum "
                    f"{manifest.onnxruntime_min}"
                ),
            )
        )
    return tuple(results)


def load_package_configuration(
    package_directory: str | Path,
    *,
    deployment_profile: str = "cpu",
) -> dict[str, Any]:
    root = Path(package_directory)
    validation = validate_model_package(root)
    if not validation.valid:
        raise ValueError("invalid model package: " + "; ".join(validation.errors))
    manifest = ModelManifest.from_dict(
        _read_json_file(root / "model_manifest.json", "model manifest")
    )
    labels = LabelMapping.from_dict(
        _read_json_file(root / manifest.label_mapping_file, "label mapping")
    )
    preprocessing = _read_json_file(
        root / manifest.preprocessing_file, "preprocessing"
    )
    profile_data = _read_json_file(
        root / manifest.deployment_profiles_file, "deployment profiles"
    )
    try:
        profile = DeploymentProfile.from_dict(
            profile_data["profiles"][deployment_profile]
        )
    except KeyError as exc:
        raise ValueError(
            f"unknown deployment profile {deployment_profile!r}"
        ) from exc
    classification = {
        task.removeprefix("classification:"): vocabulary.labels
        for task, vocabulary in labels.tasks.items()
        if task.startswith("classification:")
    }
    detection = labels.tasks.get("detection")
    return {
        "model_path": root / _safe_relative(manifest.model_artifact),
        "model_version": manifest.model_version,
        "classification_labels": classification,
        "detection_labels": detection.labels if detection else (),
        "preprocessing": preprocessing,
        "deployment_profile": profile,
        "manifest": manifest,
    }


def _output_contracts(dynamic_batch: bool) -> tuple[dict[str, Any], ...]:
    batch: int | str = "batch" if dynamic_batch else 1
    shapes = (
        [batch, 1],
        [batch, "space_classes"],
        [batch, "trade_classes"],
        [batch, "component_classes"],
        [batch, "detections", 4],
        [batch, "detections"],
        [batch, "detections"],
    )
    dtypes = ("float32",) * 6 + ("int64",)
    return tuple(
        {"name": name, "dtype": dtype, "shape": shape}
        for name, dtype, shape in zip(ONNX_OUTPUT_NAMES, dtypes, shapes)
    )


def _read_json_file(path: Path, description: str) -> dict[str, Any]:
    if not path.is_file():
        raise FileNotFoundError(f"{description} does not exist: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"{description} is not valid JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{description} must contain a JSON object")
    return value


def _safe_relative(value: str) -> str:
    path = PurePosixPath(value.replace("\\", "/"))
    if (
        path.is_absolute()
        or len(path.parts) != 1
        or path.name in {"", ".", ".."}
        or ":" in path.name
    ):
        raise ValueError(f"unsafe package path: {value!r}")
    return path.name


def _copy_regular(source: Path, destination: Path) -> None:
    _reject_symlink(source)
    if not source.is_file():
        raise FileNotFoundError(f"package source file does not exist: {source}")
    shutil.copy2(source, destination, follow_symlinks=False)


def _reject_symlink(path: Path) -> None:
    if path.is_symlink():
        raise ValueError(f"symbolic links are not allowed: {path}")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _version_tuple(value: str) -> tuple[int, ...]:
    parts = []
    for item in value.split("."):
        digits = "".join(character for character in item if character.isdigit())
        parts.append(int(digits or 0))
    return tuple(parts)


def _range_result(
    check: str, current: str, minimum: str, maximum: str
) -> CompatibilityResult:
    status = (
        "pass"
        if _version_tuple(minimum)
        <= _version_tuple(current)
        <= _version_tuple(maximum)
        else "fail"
    )
    return CompatibilityResult(
        check,
        status,
        (
            f"{check} {current} is within supported range {minimum}-{maximum}"
            if status == "pass"
            else f"{check} {current} is outside supported range {minimum}-{maximum}"
        ),
    )
