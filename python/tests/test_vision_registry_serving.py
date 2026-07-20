import base64
import json
import os
import sys
import time
import threading
from pathlib import Path

import pytest

from data_engineering.cli import main
from data_engineering.io import write_json
from vision_ai.model_package import build_model_package
from vision_ai.model_registry import ModelRegistry
from vision_ai.release_readiness import (
    ReleaseCheckReport,
    ReleaseManifest,
    run_release_check,
)
from vision_ai.models import BoundingBox, Classification, DefectDetection, ImageQuality
from vision_ai.serving import (
    InferenceCache,
    ModelManager,
    ServiceError,
    ServiceMetrics,
    ServingConfig,
    ServingService,
)
from vision_ai.serving_app import create_serving_app


def _package(tmp_path: Path, version: str = "1.0.0") -> Path:
    tmp_path.mkdir(parents=True, exist_ok=True)
    run = tmp_path / f"run-{version}"
    run.mkdir()
    (run / "model.onnx").write_bytes(f"onnx-{version}".encode())
    write_json(
        run / "run_manifest.json",
        {
            "run_id": f"run-{version}",
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
            "opset": 17,
            "dynamic_batch": True,
            "input_name": "images",
            "input_shape": [1, 3, 224, 224],
        },
    )
    write_json(run / "environment_metadata.json", {"torch": "2.2.0"})
    labels = ["bathroom", "kitchen"]
    tasks = {
        f"classification:{task}": _vocabulary(labels)
        for task in ("space", "trade", "component")
    }
    tasks["detection"] = _vocabulary(["crack"])
    write_json(run / "label_mapping.json", {"tasks": tasks})
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
            "image_preprocessing": {"resize": [224, 224]},
            "augmentation": {},
            "batch_size": 1,
            "epochs": 1,
            "learning_rate": 0.001,
            "random_seed": 42,
            "output_directory": "runs",
            "model_artifact_name": "artifact.json",
        },
    )
    output = tmp_path / f"package-{version}"
    build_model_package(
        run,
        output,
        "apartment-defect",
        version,
        created_at="2026-07-20T00:00:00+00:00",
    )
    return output


def _vocabulary(labels):
    return {
        "labels": labels,
        "label_to_index": {label: index for index, label in enumerate(labels)},
        "index_to_label": {str(index): label for index, label in enumerate(labels)},
        "unknown_policy": "error",
        "reserved_labels": [],
    }


def _png(width=1, height=1):
    return (
        b"\x89PNG\r\n\x1a\n"
        + b"\x00\x00\x00\rIHDR"
        + width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + b"\x08\x02\x00\x00\x00"
    )


def test_registry_registers_owned_copy_and_rejects_duplicates(tmp_path) -> None:
    package = _package(tmp_path)
    registry = ModelRegistry(tmp_path / "registry")
    entry = registry.register(
        package,
        "apartment-defect",
        "1.0.0",
        registered_at="2026-07-20T00:00:00+00:00",
    )
    assert entry.stage == "development"
    assert registry.package_directory(entry).is_dir()
    assert registry.package_directory(entry) != package.resolve()
    assert registry.read().revision == 1
    with pytest.raises(FileExistsError):
        registry.register(package, "apartment-defect", "1.0.0")


def test_registry_rejects_invalid_package_paths_symlink_and_revision(tmp_path) -> None:
    package = _package(tmp_path)
    registry = ModelRegistry(tmp_path / "registry")
    with pytest.raises(ValueError, match="unsafe model name"):
        registry.register(package, "../escape", "1.0.0")
    (package / "model.onnx").write_bytes(b"tamper")
    with pytest.raises(ValueError, match="invalid model package"):
        registry.register(package, "apartment-defect", "1.0.0")

    package = _package(tmp_path / "valid")
    registry.register(package, "apartment-defect", "1.0.0", expected_revision=0)
    with pytest.raises(RuntimeError, match="revision conflict"):
        registry.promote(
            "apartment-defect", "1.0.0", "staging", expected_revision=0
        )

    link = tmp_path / "link-package"
    try:
        os.symlink(package, link, target_is_directory=True)
    except OSError:
        pytest.skip("symbolic links are unavailable")
    with pytest.raises(ValueError, match="symbolic links"):
        registry.register(link, "apartment-defect", "2.0.0")


def test_registry_promotion_replaces_production_and_increments_revision(tmp_path) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    first = _package(tmp_path / "first", "1.0.0")
    second = _package(tmp_path / "second", "2.0.0")
    registry.register(first, "apartment-defect", "1.0.0", stage="production")
    registry.register(second, "apartment-defect", "2.0.0")
    promoted = registry.promote(
        "apartment-defect",
        "2.0.0",
        "production",
        previous_production_stage="archived",
    )
    assert promoted.stage == "production"
    assert registry.production("apartment-defect").model_version == "2.0.0"
    assert registry.get("apartment-defect", "1.0.0").stage == "archived"
    assert registry.read().revision == 3


def test_registry_recovers_corruption_stale_lock_and_times_out_live_lock(tmp_path) -> None:
    registry = ModelRegistry(
        tmp_path / "registry", lock_timeout_seconds=0.01, stale_lock_seconds=0.01
    )
    registry.register(
        _package(tmp_path),
        "apartment-defect",
        "1.0.0",
        stage="production",
    )
    registry.index_path.write_text("{broken", encoding="utf-8")
    assert registry.read().revision == 1
    assert json.loads(registry.index_path.read_text())["revision"] == 1

    lock = registry.locks_directory / "registry.lock"
    lock.write_text("stale", encoding="utf-8")
    old = time.time() - 10
    os.utime(lock, (old, old))
    registry.promote("apartment-defect", "1.0.0", "staging")
    assert registry.read().revision == 2

    registry.stale_lock_seconds = 100
    lock.write_text("live", encoding="utf-8")
    with pytest.raises(RuntimeError, match="timed out"):
        registry.promote("apartment-defect", "1.0.0", "production")


def test_registry_mutation_failures_leave_state_and_copy_clean(
    tmp_path, monkeypatch
) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        _package(tmp_path), "apartment-defect", "1.0.0", stage="development"
    )
    before = registry.read().to_dict()

    def fail_write(index):
        raise RuntimeError("atomic replacement failed")

    monkeypatch.setattr(registry, "_write", fail_write)
    with pytest.raises(RuntimeError, match="atomic replacement"):
        registry.promote("apartment-defect", "1.0.0", "production")
    assert registry.read().to_dict() == before

    second_registry = ModelRegistry(tmp_path / "copy-registry")
    package = _package(tmp_path / "copy", "2.0.0")
    monkeypatch.setattr("vision_ai.model_registry.shutil.copytree", lambda *a, **k: (_ for _ in ()).throw(OSError("copy failed")))
    with pytest.raises(OSError, match="copy failed"):
        second_registry.register(package, "apartment-defect", "2.0.0")
    assert not (second_registry.models_directory / "apartment-defect" / "2.0.0").exists()
    assert not list(second_registry.models_directory.rglob("*.tmp-*"))


class FakeBackend:
    backend_name = "fake"

    def __init__(self, version, calls=None, fail=False):
        self.model_version = version
        self.calls = calls if calls is not None else []
        self.fail = fail
        self.closed = False

    def assess_quality(self, image_path):
        self.calls.append(image_path)
        if self.fail:
            raise RuntimeError("secret backend detail")
        return ImageQuality(0.9, True)

    def classify(self, image_path, task):
        return [Classification("bathroom", 0.9)]

    def detect(self, image_path):
        return [DefectDetection("crack", 0.9, BoundingBox(0.1, 0.1, 0.4, 0.4))]

    def close(self):
        self.closed = True


def _service(tmp_path, *, cache=False, cache_size=2, fail=False):
    registry = ModelRegistry(tmp_path / "registry")
    package = _package(tmp_path)
    registry.register(package, "apartment-defect", "1.0.0", stage="production")
    created = []

    def factory(entry, path):
        backend = FakeBackend(entry.model_version, fail=fail)
        created.append(backend)
        return backend

    config = ServingConfig(
        registry_directory=str(registry.directory),
        default_model_name="apartment-defect",
        max_upload_bytes=100,
        max_batch_size=2,
        session_cache_size=cache_size,
        inference_cache_enabled=cache,
        inference_cache_size=2,
    )
    return ServingService(config, registry=registry, backend_factory=factory), created


def test_serving_config_environment_and_optional_app_import(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("ADA_REGISTRY", str(tmp_path / "registry"))
    monkeypatch.setenv("ADA_MODEL", "apartment-defect")
    monkeypatch.setenv("ADA_INFERENCE_CACHE_ENABLED", "true")
    config = ServingConfig.from_env()
    assert config.inference_cache_enabled
    monkeypatch.setitem(sys.modules, "fastapi", None)
    with pytest.raises(RuntimeError, match="optional dependencies"):
        create_serving_app(config)


def test_service_predict_cache_metrics_and_image_id_replacement(tmp_path) -> None:
    service, created = _service(tmp_path, cache=True)
    image = _png()
    first = service.predict(
        image, mime_type="image/png", image_id="first"
    )
    second = service.predict(
        image, mime_type="image/png", image_id="second"
    )
    assert first.image_id == "first"
    assert second.image_id == "second"
    assert len(created) == 1
    assert len(created[0].calls) == 1
    assert not Path(created[0].calls[0]).exists()
    metrics = service.metrics.snapshot()
    assert metrics["cache_miss_count"] == 1
    assert metrics["cache_hit_count"] == 1
    assert metrics["success_count"] == 2
    assert metrics["by_model"]["apartment-defect:1.0.0"] == 2


def test_readiness_changes_after_production_registration(tmp_path) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    manager = ModelManager(
        registry,
        "apartment-defect",
        backend_factory=lambda entry, path: FakeBackend(entry.model_version),
    )
    assert not manager.ready()
    registry.register(
        _package(tmp_path),
        "apartment-defect",
        "1.0.0",
        stage="production",
    )
    assert manager.ready()


def test_service_batch_partial_errors_and_limits_are_sanitized(tmp_path) -> None:
    service, _ = _service(tmp_path)
    image = _png()
    results = service.predict_batch(
        [
            {"image": image, "mime_type": "image/png", "image_id": "ok"},
            {"image": b"", "mime_type": "image/png", "image_id": "bad"},
        ]
    )
    assert [item["status"] for item in results] == ["success", "error"]
    assert results[1]["error"]["code"] == "INVALID_IMAGE"
    with pytest.raises(ServiceError) as batch:
        service.predict_batch([{}] * 3)
    assert batch.value.code == "BATCH_TOO_LARGE"
    with pytest.raises(ServiceError) as media:
        service.predict(image, mime_type="text/plain", image_id="bad")
    assert media.value.code == "UNSUPPORTED_MEDIA_TYPE"
    with pytest.raises(ServiceError) as size:
        service.predict(b"x" * 101, mime_type="image/png", image_id="large")
    assert size.value.code == "PAYLOAD_TOO_LARGE"
    with pytest.raises(ServiceError) as invalid:
        service.predict(b"not-a-png", mime_type="image/png", image_id="invalid")
    assert invalid.value.code == "INVALID_IMAGE"

    failed, _ = _service(tmp_path / "failed", fail=True)
    with pytest.raises(ServiceError) as inference:
        failed.predict(image, mime_type="image/png", image_id="failed")
    assert inference.value.code == "INFERENCE_FAILURE"
    assert "secret" not in str(inference.value)


def test_model_manager_lru_and_registry_revision_refresh(tmp_path) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        _package(tmp_path / "one", "1.0.0"),
        "apartment-defect",
        "1.0.0",
        stage="production",
    )
    registry.register(
        _package(tmp_path / "two", "2.0.0"),
        "apartment-defect",
        "2.0.0",
    )
    created = []

    def factory(entry, path):
        backend = FakeBackend(entry.model_version)
        created.append(backend)
        return backend

    manager = ModelManager(
        registry, "apartment-defect", cache_size=1, backend_factory=factory
    )
    assert manager.resolve()[0].model_version == "1.0.0"
    manager.resolve("apartment-defect", "2.0.0")
    assert not created[0].closed
    registry.promote("apartment-defect", "2.0.0", "production")
    assert manager.resolve()[0].model_version == "2.0.0"
    assert len(created) == 2
    manager.close()
    assert all(item.closed for item in created)


def test_reload_failure_keeps_previous_production_session(tmp_path) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        _package(tmp_path / "one", "1.0.0"),
        "apartment-defect",
        "1.0.0",
        stage="production",
    )
    registry.register(
        _package(tmp_path / "two", "2.0.0"),
        "apartment-defect",
        "2.0.0",
    )

    def factory(entry, path):
        if entry.model_version == "2.0.0":
            raise RuntimeError("new model broken")
        return FakeBackend(entry.model_version)

    metrics = ServiceMetrics()
    manager = ModelManager(
        registry,
        "apartment-defect",
        backend_factory=factory,
        metrics=metrics,
    )
    old_entry, old_backend = manager.resolve()
    registry.promote("apartment-defect", "2.0.0", "production")
    fallback_entry, fallback_backend = manager.resolve()
    assert fallback_entry == old_entry
    assert fallback_backend is old_backend
    assert metrics.snapshot()["model_reload_failure_count"] == 1


def test_serving_concurrency_timeout_dimensions_and_extended_metrics(tmp_path) -> None:
    service, _ = _service(tmp_path)
    object.__setattr__(service.config, "request_timeout_seconds", 0.01)
    service._concurrency = threading.BoundedSemaphore(1)
    service._concurrency.acquire()
    with pytest.raises(ServiceError) as timeout:
        service.predict(_png(), mime_type="image/png", image_id="timeout")
    service._concurrency.release()
    assert timeout.value.code == "REQUEST_TIMEOUT"
    with pytest.raises(ServiceError) as dimensions:
        service.predict(
            _png(100_000, 100_000), mime_type="image/png", image_id="bomb"
        )
    assert dimensions.value.code == "INVALID_IMAGE"
    metrics = service.metrics.snapshot()
    assert metrics["request_rejection_count"] >= 1
    assert metrics["timeout_count"] >= 1
    assert metrics["concurrent_requests"] == 0
    assert "batch_size_buckets" in metrics


def test_inference_cache_lru_returns_independent_predictions() -> None:
    cache = InferenceCache(1)
    prediction = FakeBackend("1").assess_quality
    from vision_ai.models import VisionPrediction

    value = VisionPrediction("one", "1", ImageQuality(1.0, True))
    cache.put("first", value)
    fetched = cache.get("first", "two")
    assert fetched.image_id == "two"
    cache.put("second", value)
    assert cache.get("first", "three") is None
    assert prediction is not None


def test_registry_cli_register_promote_and_list_json(tmp_path, capsys) -> None:
    package = _package(tmp_path)
    registry = tmp_path / "registry"
    assert main(
        [
            "vision-register-model",
            str(registry),
            str(package),
            "--model-name",
            "apartment-defect",
            "--model-version",
            "1.0.0",
        ]
    ) == 0
    assert main(
        [
            "vision-promote-model",
            str(registry),
            "apartment-defect",
            "1.0.0",
            "--stage",
            "production",
        ]
    ) == 0
    assert main(["vision-list-models", str(registry)]) == 0
    output = capsys.readouterr().out
    assert '"revision": 2' in output
    assert '"stage": "production"' in output


def test_release_check_pass_warning_fail_and_manifest_round_trip(
    tmp_path, monkeypatch
) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        _package(tmp_path),
        "apartment-defect",
        "1.0.0",
        stage="production",
    )
    warning, warning_manifest = run_release_check(
        registry.directory,
        "apartment-defect",
        "1.0.0",
        generated_at="2026-07-20T00:00:00+00:00",
    )
    assert warning.status == "warning"
    assert ReleaseCheckReport.from_dict(warning.to_dict()) == warning
    assert ReleaseManifest.from_dict(warning_manifest.to_dict()) == warning_manifest

    monkeypatch.setattr(
        "vision_ai.release_readiness.importlib.util.find_spec",
        lambda name: object(),
    )
    passed, _ = run_release_check(
        registry.directory,
        "apartment-defect",
        "1.0.0",
        smoke_inference=lambda package: None,
    )
    assert passed.status == "pass"

    registry.promote("apartment-defect", "1.0.0", "staging")
    failed, _ = run_release_check(
        registry.directory,
        "apartment-defect",
        "1.0.0",
        smoke_inference=lambda package: None,
    )
    assert failed.status == "fail"


def test_release_check_cli_writes_machine_readable_artifacts(tmp_path) -> None:
    registry = ModelRegistry(tmp_path / "registry")
    registry.register(
        _package(tmp_path),
        "apartment-defect",
        "1.0.0",
        stage="production",
    )
    output = tmp_path / "release"
    assert main(
        [
            "vision-release-check",
            "--registry",
            str(registry.directory),
            "--model",
            "apartment-defect",
            "--version",
            "1.0.0",
            "--output",
            str(output),
        ]
    ) == 0
    assert (output / "release_check_report.json").is_file()
    assert (output / "release_manifest.json").is_file()
    assert main(
        [
            "vision-release-check",
            "--registry",
            str(registry.directory),
            "--model",
            "apartment-defect",
            "--version",
            "1.0.0",
            "--output",
            str(tmp_path / "strict-release"),
            "--strict",
        ]
    ) == 1


def test_serve_cli_reports_missing_optional_dependency(monkeypatch, tmp_path) -> None:
    monkeypatch.setitem(sys.modules, "uvicorn", None)
    with pytest.raises(RuntimeError, match=r"\[serving\]"):
        main(
            [
                "vision-serve",
                "--registry",
                str(tmp_path / "registry"),
                "--model",
                "apartment-defect",
            ]
        )


@pytest.mark.docker
def test_dockerfile_and_ci_have_required_security_and_validation() -> None:
    root = Path(__file__).parents[2]
    dockerfile = (root / "Dockerfile").read_text(encoding="utf-8")
    workflow = (root / ".github" / "workflows" / "ci.yml").read_text(
        encoding="utf-8"
    )
    assert "python:3.11-slim-bookworm" in dockerfile
    assert "USER app" in dockerfile
    assert "HEALTHCHECK" in dockerfile and "/ready" in dockerfile
    assert "VOLUME" in dockerfile
    assert "STOPSIGNAL SIGTERM" in dockerfile
    assert "TMPDIR=/tmp/apartment-defect-ai" in dockerfile
    assert "actions/setup-python@v5" in workflow
    assert '["3.11", "3.12", "3.13"]' in workflow
    assert "compileall" in workflow
    assert "git diff --check" in workflow
    assert "dataset/schemas" in workflow
    assert ".[test,serving]" in workflow
    assert "onnx-smoke:" in workflow
    assert "training-smoke:" in workflow
    assert "schema-static:" in workflow
    assert "docker-build:" in workflow
    assert "windows-latest" in workflow


@pytest.mark.integration
@pytest.mark.serving
def test_fastapi_endpoints_when_optional_dependencies_are_installed(tmp_path) -> None:
    pytest.importorskip("fastapi")
    pytest.importorskip("httpx")
    from fastapi.testclient import TestClient

    service, _ = _service(tmp_path, cache=True)
    app = create_serving_app(service.config, service=service)
    image = base64.b64encode(_png()).decode()
    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "healthy"}
        assert client.get("/ready").status_code == 200
        assert client.get("/v1/models").status_code == 200
        response = client.post(
            "/v1/predict",
            json={
                "image_base64": image,
                "mime_type": "image/png",
                "image_id": "api-image",
            },
            headers={"x-request-id": "request-1"},
        )
        assert response.status_code == 200
        assert response.json()["image_id"] == "api-image"
        assert response.headers["x-request-id"] == "request-1"
        batch = client.post(
            "/v1/predict/batch",
            json={
                "items": [
                    {"image_base64": image, "mime_type": "image/png"},
                    {"image_base64": "", "mime_type": "image/png"},
                ]
            },
        )
        assert batch.status_code == 200
        assert [item["status"] for item in batch.json()["results"]] == [
            "success",
            "error",
        ]
        assert client.get("/v1/metrics").json()["request_count"] >= 1
