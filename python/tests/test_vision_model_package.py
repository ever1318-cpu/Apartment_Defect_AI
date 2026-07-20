import json
import os
from pathlib import Path

import pytest

from data_engineering.cli import main
from data_engineering.io import write_json
from vision_ai.model_package import (
    build_model_package,
    check_runtime_compatibility,
    generate_checksums,
    validate_model_package,
)
from vision_ai.onnx_backend import OnnxVisionBackend
from vision_ai.package_models import (
    CompatibilityManifest,
    ModelPackageValidation,
    default_deployment_profiles,
)


def _training_run(tmp_path: Path) -> Path:
    tmp_path.mkdir(parents=True, exist_ok=True)
    run = tmp_path / "run"
    run.mkdir()
    (run / "model.onnx").write_bytes(b"synthetic-onnx")
    write_json(
        run / "run_manifest.json",
        {
            "run_id": "run-002",
            "created_at": "2026-07-20T00:00:00+00:00",
            "status": "completed",
            "backend": "pytorch",
            "dataset_version": "dataset-8",
            "stages": {},
            "artifacts": ["model.onnx"],
        },
    )
    write_json(
        run / "export_metadata.json",
        {
            "format": "onnx",
            "path": "model.onnx",
            "opset": 17,
            "dynamic_batch": True,
            "input_name": "images",
            "input_shape": [1, 3, 224, 224],
            "output_names": [
                "quality",
                "space_scores",
                "trade_scores",
                "component_scores",
                "boxes",
                "detection_scores",
                "detection_labels",
            ],
        },
    )
    write_json(
        run / "environment_metadata.json",
        {"python": "3.11", "torch": "2.2.0", "device": "cpu"},
    )
    write_json(
        run / "label_mapping.json",
        {
            "tasks": {
                f"classification:{task}": _vocabulary(labels)
                for task, labels in {
                    "space": ["bathroom", "kitchen"],
                    "trade": ["finishing", "plumbing"],
                    "component": ["floor", "wall"],
                }.items()
            }
            | {"detection": _vocabulary(["crack", "leak"])},
        },
    )
    write_json(
        run / "training_spec.json",
        {
            "dataset_version": "dataset-8",
            "tasks": {
                "classification": True,
                "detection": True,
                "severity": False,
                "classification_tasks": ["space", "trade", "component"],
            },
            "split_paths": {
                "train": "train.jsonl",
                "validation": "validation.jsonl",
                "test": "test.jsonl",
            },
            "label_mapping_path": "label_mapping.json",
            "image_preprocessing": {"resize": [224, 224], "normalize": "0-1"},
            "augmentation": {},
            "batch_size": 1,
            "epochs": 1,
            "learning_rate": 0.001,
            "random_seed": 42,
            "output_directory": "runs",
            "model_artifact_name": "model-artifact.json",
        },
    )
    return run


def _vocabulary(labels):
    return {
        "labels": labels,
        "label_to_index": {label: index for index, label in enumerate(labels)},
        "index_to_label": {str(index): label for index, label in enumerate(labels)},
        "unknown_policy": "error",
        "reserved_labels": [],
    }


def _package(tmp_path: Path, name="package") -> Path:
    output = tmp_path / name
    build_model_package(
        _training_run(tmp_path),
        output,
        "apartment-defect",
        "1.0.0",
        created_at="2026-07-20T00:00:00+00:00",
    )
    return output


def test_builds_complete_valid_package_and_deterministic_checksums(tmp_path) -> None:
    package = _package(tmp_path)
    expected = {
        "model.onnx",
        "model_manifest.json",
        "compatibility_manifest.json",
        "checksums.json",
        "label_mapping.json",
        "preprocessing.json",
        "deployment_profiles.json",
        "README.txt",
    }
    assert {path.name for path in package.iterdir()} == expected
    assert validate_model_package(package, strict=True).valid
    assert generate_checksums(package) == generate_checksums(package)
    checksum_paths = list(
        json.loads((package / "checksums.json").read_text())["files"]
    )
    assert checksum_paths == sorted(checksum_paths)
    assert "checksums.json" not in checksum_paths


def test_builder_rejects_collision_and_missing_inputs(tmp_path) -> None:
    run = _training_run(tmp_path)
    output = tmp_path / "existing"
    output.mkdir()
    with pytest.raises(FileExistsError):
        build_model_package(run, output, "model", "1")
    (run / "model.onnx").unlink()
    with pytest.raises(FileNotFoundError, match="ONNX model"):
        build_model_package(run, tmp_path / "missing-model", "model", "1")
    (run / "run_manifest.json").unlink()
    with pytest.raises(FileNotFoundError, match="training manifest"):
        build_model_package(run, tmp_path / "missing-manifest", "model", "1")


def test_checksum_mismatch_missing_and_strict_extra_file(tmp_path) -> None:
    package = _package(tmp_path)
    (package / "model.onnx").write_bytes(b"tampered")
    result = validate_model_package(package)
    assert not result.valid
    assert any(item.status == "mismatch" for item in result.checksum_results)

    package = _package(tmp_path / "second")
    (package / "README.txt").unlink()
    assert any("missing required" in error for error in validate_model_package(package).errors)

    package = _package(tmp_path / "third")
    (package / "extra.txt").write_text("extra")
    assert validate_model_package(package).valid
    assert not validate_model_package(package, strict=True).valid


def test_path_traversal_manifest_mismatch_and_invalid_mapping(tmp_path) -> None:
    package = _package(tmp_path)
    checksums = json.loads((package / "checksums.json").read_text())
    checksums["files"]["../outside"] = "0" * 64
    write_json(package / "checksums.json", checksums)
    assert any(
        "unsafe package path" in error
        for error in validate_model_package(package).errors
    )

    package = _package(tmp_path / "second")
    manifest = json.loads((package / "model_manifest.json").read_text())
    manifest["model_artifact"] = "wrong.onnx"
    write_json(package / "model_manifest.json", manifest)
    assert any(
        "referenced file is missing" in error
        for error in validate_model_package(package).errors
    )

    package = _package(tmp_path / "third")
    mapping = json.loads((package / "label_mapping.json").read_text())
    mapping["tasks"]["detection"]["label_to_index"]["crack"] = 4
    write_json(package / "label_mapping.json", mapping)
    assert any(
        "invalid label mapping" in error
        for error in validate_model_package(package).errors
    )


def test_symbolic_links_are_rejected_when_supported(tmp_path) -> None:
    package = _package(tmp_path)
    target = package / "extra-link"
    try:
        os.symlink(package / "model.onnx", target)
    except OSError:
        pytest.skip("symbolic links are unavailable")
    result = validate_model_package(package)
    assert not result.valid
    assert any("symbolic links" in error for error in result.errors)


def test_default_cpu_gpu_profiles_and_compatibility_statuses(tmp_path) -> None:
    profiles = default_deployment_profiles()
    assert profiles["cpu"].execution_providers == ("CPUExecutionProvider",)
    assert profiles["gpu"].execution_providers[0] == "CUDAExecutionProvider"
    compatibility = CompatibilityManifest.from_dict(
        json.loads(
            (_package(tmp_path) / "compatibility_manifest.json").read_text()
        )
    )
    passed = check_runtime_compatibility(
        compatibility,
        runtime={
            "python_version": "3.11.9",
            "cpu_architecture": "AMD64",
            "operating_system": "Windows",
            "execution_providers": ["CPUExecutionProvider"],
            "onnxruntime_version": "1.18.0",
        },
    )
    assert all(item.status == "pass" for item in passed)
    warning = check_runtime_compatibility(
        compatibility,
        runtime={
            "python_version": "3.11",
            "cpu_architecture": "AMD64",
            "operating_system": "FreeBSD",
            "execution_providers": ["CPUExecutionProvider"],
        },
    )
    assert any(item.status == "warning" for item in warning)
    failed = check_runtime_compatibility(
        compatibility,
        runtime={
            "python_version": "2.7",
            "cpu_architecture": "unknown",
            "operating_system": "Windows",
            "execution_providers": [],
            "onnxruntime_version": "1.0",
        },
    )
    assert any(item.status == "fail" for item in failed)


def test_validation_json_round_trip(tmp_path) -> None:
    result = validate_model_package(_package(tmp_path))
    assert ModelPackageValidation.from_dict(result.to_dict()) == result


class _Input:
    name = "images"


class _Session:
    def get_inputs(self):
        return [_Input()]

    def run(self, output_names, input_feed):
        return []


def test_package_directory_configures_onnx_backend_and_raw_path_stays_compatible(
    tmp_path,
) -> None:
    package = _package(tmp_path)
    calls = []

    def factory(path, providers):
        calls.append((path, providers))
        return _Session()

    backend = OnnxVisionBackend(
        package,
        session_factory=factory,
        input_loader=lambda path: "tensor",
        deployment_profile="cpu",
    )
    assert backend.model_version == "1.0.0"
    assert backend.detection_labels == ("crack", "leak")
    assert calls[0][0] == (package / "model.onnx").resolve()
    assert calls[0][1] == ("CPUExecutionProvider",)

    raw = tmp_path / "raw.onnx"
    raw.write_bytes(b"raw")
    raw_backend = OnnxVisionBackend(
        raw,
        model_version="raw-1",
        session_factory=factory,
        input_loader=lambda path: "tensor",
    )
    assert raw_backend.model_version == "raw-1"


def test_cli_package_validate_and_inspect_end_to_end(tmp_path, capsys) -> None:
    run = _training_run(tmp_path)
    package = tmp_path / "cli-package"
    assert main(
        [
            "vision-package-model",
            str(run),
            str(package),
            "--model-name",
            "apartment-defect",
            "--model-version",
            "2.0.0",
        ]
    ) == 0
    assert main(["vision-validate-model-package", str(package), "--strict"]) == 0
    assert main(["vision-inspect-model-package", str(package)]) == 0
    assert (tmp_path / "cli-package-validation.json").is_file()
    assert "2.0.0" in capsys.readouterr().out


def test_build_failure_leaves_no_incomplete_output(tmp_path, monkeypatch) -> None:
    run = _training_run(tmp_path)
    output = tmp_path / "failed-package"

    def fail(directory):
        raise RuntimeError("injected checksum failure")

    monkeypatch.setattr("vision_ai.model_package.generate_checksums", fail)
    with pytest.raises(RuntimeError, match="injected"):
        build_model_package(run, output, "model", "1")
    assert not output.exists()
    assert not list(tmp_path.glob(".failed-package.tmp-*"))
