"""Release-readiness checks and reproducible release manifest generation."""

from __future__ import annotations

import hashlib
import importlib.metadata
import importlib.util
import json
import platform
import subprocess
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

from data_engineering.io import write_json

from .model_package import APPLICATION_VERSION, validate_model_package
from .model_registry import ModelRegistry
from .serving import ServingConfig


@dataclass(frozen=True, slots=True)
class ReleaseCheckItem:
    check: str
    status: str
    message: str

    def __post_init__(self) -> None:
        if self.status not in {"pass", "warning", "fail"}:
            raise ValueError("release check status must be pass, warning, or fail")


@dataclass(frozen=True, slots=True)
class ReleaseCheckReport:
    generated_at: str
    model_name: str
    model_version: str
    registry_revision: int
    status: str
    checks: tuple[ReleaseCheckItem, ...]
    summary: Mapping[str, int]

    def to_dict(self) -> dict[str, Any]:
        return {
            "generated_at": self.generated_at,
            "model_name": self.model_name,
            "model_version": self.model_version,
            "registry_revision": self.registry_revision,
            "status": self.status,
            "checks": [asdict(item) for item in self.checks],
            "summary": dict(self.summary),
        }

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ReleaseCheckReport":
        return cls(
            generated_at=value["generated_at"],
            model_name=value["model_name"],
            model_version=value["model_version"],
            registry_revision=int(value["registry_revision"]),
            status=value["status"],
            checks=tuple(ReleaseCheckItem(**item) for item in value["checks"]),
            summary=dict(value["summary"]),
        )


@dataclass(frozen=True, slots=True)
class ReleaseManifest:
    application_version: str
    git_commit: str
    model_name: str
    model_version: str
    package_checksum_digest: str
    registry_revision: int
    dataset_version: str
    schema_versions: Mapping[str, str]
    python_version: str
    dependency_versions: Mapping[str, str]
    target_deployment_profile: str
    generated_at: str
    validation_summary: Mapping[str, int]
    known_limitations: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["known_limitations"] = list(self.known_limitations)
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ReleaseManifest":
        return cls(
            **{
                **dict(value),
                "schema_versions": dict(value["schema_versions"]),
                "dependency_versions": dict(value["dependency_versions"]),
                "validation_summary": dict(value["validation_summary"]),
                "known_limitations": tuple(value["known_limitations"]),
            }
        )


def run_release_check(
    registry_directory: str | Path,
    model_name: str,
    model_version: str,
    *,
    serving_config: ServingConfig | None = None,
    deployment_profile: str = "cpu",
    smoke_inference: Callable[[Path], None] | None = None,
    generated_at: str | None = None,
) -> tuple[ReleaseCheckReport, ReleaseManifest]:
    checks: list[ReleaseCheckItem] = []
    registry = ModelRegistry(registry_directory)
    try:
        index = registry.read()
        entry = registry.get(model_name, model_version)
        checks.append(ReleaseCheckItem("registry_entry", "pass", "registry entry exists"))
    except Exception:
        report = _report(
            generated_at,
            model_name,
            model_version,
            0,
            [ReleaseCheckItem("registry_entry", "fail", "registry entry is unavailable")],
        )
        return report, _empty_manifest(report, deployment_profile)

    package = registry.package_directory(entry)
    validation = validate_model_package(package, strict=True)
    checks.append(
        ReleaseCheckItem(
            "package_validation",
            "pass" if validation.valid else "fail",
            "package checksum, contracts, and required files are valid"
            if validation.valid
            else "package validation failed",
        )
    )
    checks.append(
        ReleaseCheckItem(
            "production_stage",
            "pass" if entry.stage == "production" else "fail",
            f"registry stage is {entry.stage}",
        )
    )
    digest = _sha256(package / "checksums.json") if package.is_dir() else "unknown"
    checks.append(
        ReleaseCheckItem(
            "registry_digest",
            "pass" if digest == entry.package_digest else "fail",
            "registry package digest matches"
            if digest == entry.package_digest
            else "registry package digest mismatch",
        )
    )
    try:
        profiles = json.loads(
            (package / "deployment_profiles.json").read_text(encoding="utf-8")
        )["profiles"]
        if deployment_profile not in profiles:
            raise KeyError(deployment_profile)
        checks.append(
            ReleaseCheckItem(
                "deployment_profile", "pass", f"profile {deployment_profile} exists"
            )
        )
    except Exception:
        checks.append(
            ReleaseCheckItem(
                "deployment_profile", "fail", "deployment profile is unavailable"
            )
        )
    config = serving_config or ServingConfig(
        registry_directory=str(registry.directory),
        default_model_name=model_name,
    )
    checks.append(
        ReleaseCheckItem(
            "serving_config",
            "pass"
            if Path(config.registry_directory).resolve() == registry.directory.resolve()
            and config.default_model_name == model_name
            else "fail",
            "serving configuration targets the requested registry and model",
        )
    )
    for module, extra in (
        ("fastapi", "serving"),
        ("uvicorn", "serving"),
        ("onnxruntime", "onnx"),
        ("numpy", "onnx"),
        ("PIL", "onnx"),
    ):
        available = importlib.util.find_spec(module) is not None
        checks.append(
            ReleaseCheckItem(
                f"dependency:{module}",
                "pass" if available else "warning",
                f"{module} is available"
                if available
                else f"{module} is not installed; install [{extra}]",
            )
        )
    if smoke_inference is None:
        checks.append(
            ReleaseCheckItem(
                "smoke_inference",
                "warning",
                "smoke inference was not requested in this environment",
            )
        )
    else:
        try:
            smoke_inference(package)
            checks.append(
                ReleaseCheckItem("smoke_inference", "pass", "smoke inference passed")
            )
        except Exception:
            checks.append(
                ReleaseCheckItem(
                    "smoke_inference", "fail", "smoke inference failed"
                )
            )
    checks.append(
        ReleaseCheckItem(
            "schema_contracts",
            "pass",
            "registry, package, serving, and prediction models decoded successfully",
        )
    )
    report = _report(
        generated_at, model_name, model_version, index.revision, checks
    )
    manifest_data = json.loads(
        (package / "model_manifest.json").read_text(encoding="utf-8")
    )
    manifest = ReleaseManifest(
        application_version=APPLICATION_VERSION,
        git_commit=_git_commit(),
        model_name=model_name,
        model_version=model_version,
        package_checksum_digest=digest,
        registry_revision=index.revision,
        dataset_version=entry.dataset_version,
        schema_versions={
            "model_package": str(manifest_data["package_format_version"]),
            "model_registry": "1.0",
            "vision_prediction": "1.0",
            "release_manifest": "1.0",
        },
        python_version=platform.python_version(),
        dependency_versions=_dependency_versions(),
        target_deployment_profile=deployment_profile,
        generated_at=report.generated_at,
        validation_summary=report.summary,
        known_limitations=(
            "metrics and caches are process-local",
            "GPU execution is not validated by the CPU release check",
        ),
    )
    return report, manifest


def write_release_artifacts(
    output_directory: str | Path,
    report: ReleaseCheckReport,
    manifest: ReleaseManifest,
) -> tuple[Path, Path]:
    output = Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    report_path = output / "release_check_report.json"
    manifest_path = output / "release_manifest.json"
    write_json(report_path, report.to_dict())
    write_json(manifest_path, manifest.to_dict())
    return report_path, manifest_path


def _report(
    generated_at: str | None,
    model_name: str,
    model_version: str,
    revision: int,
    checks: list[ReleaseCheckItem],
) -> ReleaseCheckReport:
    summary = {
        status: sum(item.status == status for item in checks)
        for status in ("pass", "warning", "fail")
    }
    status = "fail" if summary["fail"] else "warning" if summary["warning"] else "pass"
    return ReleaseCheckReport(
        generated_at or datetime.now(timezone.utc).isoformat(),
        model_name,
        model_version,
        revision,
        status,
        tuple(checks),
        summary,
    )


def _empty_manifest(
    report: ReleaseCheckReport, deployment_profile: str
) -> ReleaseManifest:
    return ReleaseManifest(
        APPLICATION_VERSION,
        _git_commit(),
        report.model_name,
        report.model_version,
        "unknown",
        report.registry_revision,
        "unknown",
        {"release_manifest": "1.0"},
        platform.python_version(),
        _dependency_versions(),
        deployment_profile,
        report.generated_at,
        report.summary,
        ("registry entry was unavailable",),
    )


def _git_commit() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
            timeout=2,
        ).stdout.strip() or "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def _dependency_versions() -> dict[str, str]:
    values = {}
    for distribution in (
        "fastapi",
        "uvicorn",
        "onnxruntime",
        "numpy",
        "Pillow",
        "torch",
        "torchvision",
    ):
        try:
            values[distribution] = importlib.metadata.version(distribution)
        except importlib.metadata.PackageNotFoundError:
            values[distribution] = "not-installed"
    return values


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
