"""Framework-neutral serving core: configuration, lifecycle, caches, and metrics."""

from __future__ import annotations

import copy
import hashlib
import json
import logging
import os
import tempfile
import threading
import time
from collections import Counter, OrderedDict
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

from data_engineering.models import ImageRecord

from .model_registry import ModelRegistry, RegistryEntry
from .models import VisionPrediction
from .onnx_backend import OnnxVisionBackend
from .pipeline import PipelineConfig, VisionPipeline

LOGGER = logging.getLogger("apartment_defect_ai.serving")


@dataclass(frozen=True, slots=True)
class ServingConfig:
    registry_directory: str
    default_model_name: str
    host: str = "127.0.0.1"
    port: int = 8000
    workers: int = 1
    max_upload_bytes: int = 10 * 1024 * 1024
    max_batch_size: int = 16
    confidence_threshold: float = 0.25
    session_cache_size: int = 2
    inference_cache_enabled: bool = False
    inference_cache_size: int = 128
    compatibility_strictness: str = "fail"
    allowed_mime_types: tuple[str, ...] = (
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/tiff",
    )
    temp_directory: str | None = None
    logging_level: str = "INFO"
    request_timeout_seconds: float = 30.0
    model_load_timeout_seconds: float = 30.0
    max_concurrent_requests: int = 8
    max_batch_bytes: int = 40 * 1024 * 1024
    max_image_pixels: int = 25_000_000
    max_json_depth: int = 12

    def __post_init__(self) -> None:
        if not self.registry_directory or not self.default_model_name:
            raise ValueError("registry_directory and default_model_name are required")
        if not 1 <= self.port <= 65535 or self.workers <= 0:
            raise ValueError("invalid serving port or worker count")
        if min(
            self.max_upload_bytes,
            self.max_batch_size,
            self.session_cache_size,
            self.inference_cache_size,
            self.max_concurrent_requests,
            self.max_batch_bytes,
            self.max_image_pixels,
            self.max_json_depth,
        ) <= 0:
            raise ValueError("serving limits and cache sizes must be positive")
        if not 0 <= self.confidence_threshold <= 1:
            raise ValueError("confidence_threshold must be between 0 and 1")
        if self.compatibility_strictness not in {"warning", "fail"}:
            raise ValueError("compatibility_strictness must be warning or fail")
        if self.request_timeout_seconds <= 0 or self.model_load_timeout_seconds <= 0:
            raise ValueError("serving timeouts must be positive")

    def to_dict(self) -> dict[str, Any]:
        value = asdict(self)
        value["allowed_mime_types"] = list(self.allowed_mime_types)
        return value

    @classmethod
    def from_dict(cls, value: Mapping[str, Any]) -> "ServingConfig":
        return cls(
            **{
                **dict(value),
                "allowed_mime_types": tuple(
                    value.get(
                        "allowed_mime_types",
                        ("image/jpeg", "image/png", "image/webp", "image/tiff"),
                    )
                ),
            }
        )

    @classmethod
    def from_env(cls, prefix: str = "ADA_") -> "ServingConfig":
        required = {
            "registry_directory": os.environ.get(f"{prefix}REGISTRY"),
            "default_model_name": os.environ.get(f"{prefix}MODEL"),
        }
        values: dict[str, Any] = {
            **required,
            "host": os.environ.get(f"{prefix}HOST", "127.0.0.1"),
            "port": int(os.environ.get(f"{prefix}PORT", "8000")),
            "workers": int(os.environ.get(f"{prefix}WORKERS", "1")),
            "max_upload_bytes": int(
                os.environ.get(f"{prefix}MAX_UPLOAD_BYTES", str(10 * 1024 * 1024))
            ),
            "max_batch_size": int(os.environ.get(f"{prefix}MAX_BATCH_SIZE", "16")),
            "confidence_threshold": float(
                os.environ.get(f"{prefix}CONFIDENCE_THRESHOLD", "0.25")
            ),
            "session_cache_size": int(
                os.environ.get(f"{prefix}SESSION_CACHE_SIZE", "2")
            ),
            "inference_cache_enabled": _bool_env(
                os.environ.get(f"{prefix}INFERENCE_CACHE_ENABLED", "false")
            ),
            "inference_cache_size": int(
                os.environ.get(f"{prefix}INFERENCE_CACHE_SIZE", "128")
            ),
            "compatibility_strictness": os.environ.get(
                f"{prefix}COMPATIBILITY_STRICTNESS", "fail"
            ),
            "temp_directory": os.environ.get(f"{prefix}TEMP_DIRECTORY"),
            "logging_level": os.environ.get(f"{prefix}LOGGING_LEVEL", "INFO"),
            "request_timeout_seconds": float(
                os.environ.get(f"{prefix}REQUEST_TIMEOUT_SECONDS", "30")
            ),
            "model_load_timeout_seconds": float(
                os.environ.get(f"{prefix}MODEL_LOAD_TIMEOUT_SECONDS", "30")
            ),
            "max_concurrent_requests": int(
                os.environ.get(f"{prefix}MAX_CONCURRENT_REQUESTS", "8")
            ),
            "max_batch_bytes": int(
                os.environ.get(
                    f"{prefix}MAX_BATCH_BYTES", str(40 * 1024 * 1024)
                )
            ),
            "max_image_pixels": int(
                os.environ.get(f"{prefix}MAX_IMAGE_PIXELS", "25000000")
            ),
            "max_json_depth": int(
                os.environ.get(f"{prefix}MAX_JSON_DEPTH", "12")
            ),
        }
        allowed = os.environ.get(f"{prefix}ALLOWED_MIME_TYPES")
        if allowed:
            values["allowed_mime_types"] = tuple(
                item.strip() for item in allowed.split(",") if item.strip()
            )
        return cls(**values)


@dataclass(frozen=True, slots=True)
class APIError:
    code: str
    message: str
    details: Mapping[str, Any] = field(default_factory=dict)
    request_id: str = ""

    def to_dict(self) -> dict[str, Any]:
        return {"error": asdict(self)}


class ServiceError(Exception):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int = 400,
        details: Mapping[str, Any] | None = None,
    ):
        super().__init__(message)
        self.code = code
        self.status_code = status_code
        self.details = dict(details or {})


class ServiceMetrics:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._start_time = time.time()
        self._counters = Counter(
            {
                "request_count": 0,
                "success_count": 0,
                "error_count": 0,
                "batch_request_count": 0,
                "image_count": 0,
                "model_load_count": 0,
                "model_load_failure_count": 0,
                "cache_hit_count": 0,
                "cache_miss_count": 0,
                "model_reload_count": 0,
                "model_reload_failure_count": 0,
                "request_rejection_count": 0,
                "timeout_count": 0,
            }
        )
        self._durations = {
            "inference": _duration_initial(),
            "request": _duration_initial(),
        }
        self._models: Counter[str] = Counter()
        self._errors: Counter[str] = Counter()
        self._response_status: Counter[str] = Counter()
        self._batch_sizes: Counter[str] = Counter()
        self._gauges: dict[str, Any] = {
            "readiness_state": False,
            "active_model_name": None,
            "active_model_version": None,
            "session_cache_size": 0,
            "inference_cache_size": 0,
            "concurrent_requests": 0,
        }

    def increment(self, name: str, amount: int = 1) -> None:
        with self._lock:
            self._counters[name] += amount

    def model(self, name: str, version: str) -> None:
        with self._lock:
            self._models[f"{name}:{version}"] += 1

    def error(self, error_type: str) -> None:
        with self._lock:
            self._errors[error_type] += 1
            self._counters["error_count"] += 1

    def duration(self, kind: str, seconds: float) -> None:
        with self._lock:
            values = self._durations[kind]
            values["count"] += 1
            values["sum"] += seconds
            values["min"] = seconds if values["min"] is None else min(values["min"], seconds)
            values["max"] = seconds if values["max"] is None else max(values["max"], seconds)

    def gauge(self, name: str, value: Any) -> None:
        with self._lock:
            self._gauges[name] = value

    def response(self, status_code: int) -> None:
        with self._lock:
            self._response_status[str(status_code)] += 1

    def batch_size(self, size: int) -> None:
        boundary = "1" if size <= 1 else "2-4" if size <= 4 else "5-16" if size <= 16 else "17+"
        with self._lock:
            self._batch_sizes[boundary] += 1

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            return {
                **dict(self._counters),
                "inference_duration": dict(self._durations["inference"]),
                "request_duration": dict(self._durations["request"]),
                "by_model": dict(sorted(self._models.items())),
                "by_error_type": dict(sorted(self._errors.items())),
                "by_response_status": dict(sorted(self._response_status.items())),
                "batch_size_buckets": dict(sorted(self._batch_sizes.items())),
                **copy.deepcopy(self._gauges),
                "process_start_time": self._start_time,
                "uptime_seconds": max(0.0, time.time() - self._start_time),
            }


class InferenceCache:
    def __init__(self, maximum_entries: int):
        if maximum_entries <= 0:
            raise ValueError("maximum_entries must be positive")
        self.maximum_entries = maximum_entries
        self._lock = threading.Lock()
        self._values: OrderedDict[str, dict[str, Any]] = OrderedDict()

    def get(self, key: str, image_id: str) -> VisionPrediction | None:
        with self._lock:
            value = self._values.get(key)
            if value is None:
                return None
            self._values.move_to_end(key)
            copied = copy.deepcopy(value)
        copied["image_id"] = image_id
        return VisionPrediction.from_dict(copied)

    def put(self, key: str, prediction: VisionPrediction) -> None:
        value = copy.deepcopy(prediction.to_dict())
        value["image_id"] = "__cached__"
        with self._lock:
            self._values[key] = value
            self._values.move_to_end(key)
            while len(self._values) > self.maximum_entries:
                self._values.popitem(last=False)

    def __len__(self) -> int:
        with self._lock:
            return len(self._values)


BackendFactory = Callable[[RegistryEntry, Path], Any]


class ModelManager:
    def __init__(
        self,
        registry: ModelRegistry,
        default_model_name: str,
        *,
        cache_size: int = 2,
        backend_factory: BackendFactory | None = None,
        metrics: ServiceMetrics | None = None,
        load_timeout_seconds: float = 30.0,
    ):
        self.registry = registry
        self.default_model_name = default_model_name
        self.cache_size = cache_size
        self.backend_factory = backend_factory or (
            lambda entry, package: OnnxVisionBackend(package)
        )
        self.metrics = metrics or ServiceMetrics()
        self.load_timeout_seconds = load_timeout_seconds
        self._lock = threading.RLock()
        self._revision = -1
        self._cache: OrderedDict[tuple[str, str], Any] = OrderedDict()
        self._retired: list[Any] = []
        self._active_default: tuple[RegistryEntry, Any] | None = None

    def resolve(
        self, model_name: str | None = None, model_version: str | None = None
    ) -> tuple[RegistryEntry, Any]:
        with self._lock:
            index = self.registry.read()
            if index.revision != self._revision:
                if self._revision >= 0:
                    self.metrics.increment("model_reload_count")
                self._revision = index.revision
            name = model_name or self.default_model_name
            try:
                entry = (
                    self.registry.get(name, model_version)
                    if model_version
                    else self.registry.production(name)
                )
            except KeyError as exc:
                raise ServiceError(
                    "MODEL_NOT_FOUND", str(exc), status_code=404
                ) from None
            key = (entry.model_name, entry.model_version)
            backend = self._cache.get(key)
            if backend is not None:
                self._cache.move_to_end(key)
                if model_version is None:
                    self._active_default = (entry, backend)
                return entry, backend
            try:
                started = time.monotonic()
                backend = self.backend_factory(entry, self.registry.package_directory(entry))
                if time.monotonic() - started > self.load_timeout_seconds:
                    _close(backend)
                    self.metrics.increment("timeout_count")
                    raise TimeoutError("model load timed out")
            except Exception:
                self.metrics.increment("model_load_failure_count")
                if model_version is None and self._active_default is not None:
                    self.metrics.increment("model_reload_failure_count")
                    return self._active_default
                raise ServiceError(
                    "MODEL_NOT_READY",
                    "model could not be loaded",
                    status_code=503,
                ) from None
            self.metrics.increment("model_load_count")
            self._cache[key] = backend
            if model_version is None:
                self._active_default = (entry, backend)
            while len(self._cache) > self.cache_size:
                _, removed = self._cache.popitem(last=False)
                self._retired.append(removed)
            return entry, backend

    def ready(self) -> bool:
        try:
            self.resolve()
            return True
        except ServiceError:
            return False

    def close(self) -> None:
        with self._lock:
            self._retired.extend(self._cache.values())
            self._cache.clear()
            for backend in self._retired:
                _close(backend)
            self._retired.clear()
            self._active_default = None

    def _evict_all(self) -> None:
        self._retired.extend(self._cache.values())
        self._cache.clear()


class ServingService:
    def __init__(
        self,
        config: ServingConfig,
        *,
        registry: ModelRegistry | None = None,
        backend_factory: BackendFactory | None = None,
        metrics: ServiceMetrics | None = None,
    ):
        self.config = config
        self.registry = registry or ModelRegistry(config.registry_directory)
        self.registry.initialize()
        self.metrics = metrics or ServiceMetrics()
        self.models = ModelManager(
            self.registry,
            config.default_model_name,
            cache_size=config.session_cache_size,
            backend_factory=backend_factory,
            metrics=self.metrics,
            load_timeout_seconds=config.model_load_timeout_seconds,
        )
        self.inference_cache = (
            InferenceCache(config.inference_cache_size)
            if config.inference_cache_enabled
            else None
        )
        self._concurrency = threading.BoundedSemaphore(config.max_concurrent_requests)
        self._concurrent_lock = threading.Lock()
        self._concurrent = 0

    def predict(
        self,
        image: bytes,
        *,
        mime_type: str,
        image_id: str,
        model_name: str | None = None,
        model_version: str | None = None,
        confidence_threshold: float | None = None,
    ) -> VisionPrediction:
        acquired = self._concurrency.acquire(
            timeout=self.config.request_timeout_seconds
        )
        if not acquired:
            self.metrics.increment("request_count")
            self.metrics.increment("request_rejection_count")
            self.metrics.increment("timeout_count")
            self.metrics.error("REQUEST_TIMEOUT")
            raise ServiceError(
                "REQUEST_TIMEOUT",
                "request timed out waiting for capacity",
                status_code=503,
            )
        with self._concurrent_lock:
            self._concurrent += 1
            self.metrics.gauge("concurrent_requests", self._concurrent)
        try:
            return self._predict_impl(
                image,
                mime_type=mime_type,
                image_id=image_id,
                model_name=model_name,
                model_version=model_version,
                confidence_threshold=confidence_threshold,
            )
        finally:
            with self._concurrent_lock:
                self._concurrent -= 1
                self.metrics.gauge("concurrent_requests", self._concurrent)
            self._concurrency.release()

    def _predict_impl(
        self,
        image: bytes,
        *,
        mime_type: str,
        image_id: str,
        model_name: str | None = None,
        model_version: str | None = None,
        confidence_threshold: float | None = None,
    ) -> VisionPrediction:
        request_started = time.perf_counter()
        self.metrics.increment("request_count")
        self.metrics.increment("image_count")
        try:
            self._validate_image(image, mime_type)
            threshold = (
                self.config.confidence_threshold
                if confidence_threshold is None
                else confidence_threshold
            )
            if not 0 <= threshold <= 1:
                raise ServiceError("INVALID_REQUEST", "confidence threshold is invalid")
            entry, backend = self.models.resolve(model_name, model_version)
            self.metrics.gauge("readiness_state", True)
            self.metrics.gauge("active_model_name", entry.model_name)
            self.metrics.gauge("active_model_version", entry.model_version)
            self.metrics.gauge("session_cache_size", len(self.models._cache))
            key = _cache_key(
                image,
                entry,
                threshold,
                preprocessing_version="1.0",
            )
            if self.inference_cache is not None:
                cached = self.inference_cache.get(key, image_id)
                if cached is not None:
                    self.metrics.increment("cache_hit_count")
                    self.metrics.increment("success_count")
                    self.metrics.model(entry.model_name, entry.model_version)
                    self.metrics.gauge(
                        "inference_cache_size", len(self.inference_cache)
                    )
                    return cached
                self.metrics.increment("cache_miss_count")
            suffix = _mime_suffix(mime_type)
            started = time.perf_counter()
            path = _temporary_image(image, suffix, self.config.temp_directory)
            try:
                prediction = VisionPipeline(
                    backend,
                    PipelineConfig(
                        classification_threshold=threshold,
                        detection_threshold=threshold,
                    ),
                ).predict(
                    ImageRecord(
                        image_id=image_id,
                        image_path=str(path),
                        group_id="serving-request",
                        label="unknown",
                    )
                )
            except ServiceError:
                raise
            except Exception:
                raise ServiceError(
                    "INFERENCE_FAILURE",
                    "model inference failed",
                    status_code=500,
                ) from None
            finally:
                path.unlink(missing_ok=True)
            self.metrics.duration("inference", time.perf_counter() - started)
            if time.perf_counter() - started > self.config.request_timeout_seconds:
                self.metrics.increment("timeout_count")
                raise ServiceError(
                    "REQUEST_TIMEOUT", "inference exceeded request timeout", status_code=504
                )
            self.metrics.increment("success_count")
            self.metrics.model(entry.model_name, entry.model_version)
            if self.inference_cache is not None:
                self.inference_cache.put(key, prediction)
                self.metrics.gauge(
                    "inference_cache_size", len(self.inference_cache)
                )
            return prediction
        except ServiceError as exc:
            self.metrics.error(exc.code)
            raise
        finally:
            self.metrics.duration("request", time.perf_counter() - request_started)

    def predict_batch(
        self,
        items: Sequence[Mapping[str, Any]],
        *,
        fail_fast: bool = False,
    ) -> list[dict[str, Any]]:
        if len(items) > self.config.max_batch_size:
            raise ServiceError(
                "BATCH_TOO_LARGE",
                "batch exceeds configured maximum",
                status_code=413,
            )
        self.metrics.increment("batch_request_count")
        self.metrics.batch_size(len(items))
        total_bytes = sum(
            len(item.get("image", b""))
            for item in items
            if isinstance(item, Mapping)
        )
        if total_bytes > self.config.max_batch_bytes:
            self.metrics.increment("request_rejection_count")
            raise ServiceError(
                "PAYLOAD_TOO_LARGE",
                "batch payload exceeds configured maximum",
                status_code=413,
            )
        results = []
        for item in items:
            try:
                prediction = self.predict(**item)
                results.append({"status": "success", "prediction": prediction.to_dict()})
            except ServiceError as exc:
                if fail_fast:
                    raise
                results.append(
                    {
                        "status": "error",
                        "error": {
                            "code": exc.code,
                            "message": str(exc),
                            "details": exc.details,
                        },
                    }
                )
        return results

    def _validate_image(self, image: bytes, mime_type: str) -> None:
        if mime_type not in self.config.allowed_mime_types:
            raise ServiceError(
                "UNSUPPORTED_MEDIA_TYPE",
                "image media type is not supported",
                status_code=415,
            )
        if len(image) > self.config.max_upload_bytes:
            raise ServiceError(
                "PAYLOAD_TOO_LARGE",
                "image exceeds configured maximum size",
                status_code=413,
            )
        if not image:
            raise ServiceError("INVALID_IMAGE", "image payload is empty")
        signatures = {
            "image/jpeg": image.startswith(b"\xff\xd8"),
            "image/png": image.startswith(b"\x89PNG\r\n\x1a\n"),
            "image/webp": (
                len(image) >= 12
                and image.startswith(b"RIFF")
                and image[8:12] == b"WEBP"
            ),
            "image/tiff": image.startswith((b"II*\x00", b"MM\x00*")),
        }
        if not signatures[mime_type]:
            raise ServiceError(
                "INVALID_IMAGE",
                "image bytes do not match the declared media type",
            )
        if mime_type == "image/png":
            if len(image) < 24 or image[12:16] != b"IHDR":
                raise ServiceError("INVALID_IMAGE", "PNG header is malformed")
            width = int.from_bytes(image[16:20], "big")
            height = int.from_bytes(image[20:24], "big")
            if width <= 0 or height <= 0:
                raise ServiceError("INVALID_IMAGE", "image dimensions are invalid")
            if width * height > self.config.max_image_pixels:
                raise ServiceError(
                    "INVALID_IMAGE", "image dimensions exceed configured maximum"
                )


def _cache_key(
    image: bytes,
    entry: RegistryEntry,
    confidence_threshold: float,
    *,
    preprocessing_version: str,
) -> str:
    digest = hashlib.sha256()
    digest.update(image)
    digest.update(
        json.dumps(
            {
                "model_name": entry.model_name,
                "model_version": entry.model_version,
                "confidence_threshold": confidence_threshold,
                "preprocessing_version": preprocessing_version,
            },
            sort_keys=True,
        ).encode("utf-8")
    )
    return digest.hexdigest()


def _temporary_image(image: bytes, suffix: str, directory: str | None) -> Path:
    with tempfile.NamedTemporaryFile(
        suffix=suffix, dir=directory, delete=False
    ) as stream:
        stream.write(image)
        return Path(stream.name)


def _mime_suffix(mime_type: str) -> str:
    return {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "image/tiff": ".tiff",
    }[mime_type]


def _duration_initial() -> dict[str, Any]:
    return {"count": 0, "sum": 0.0, "min": None, "max": None}


def _close(value: Any) -> None:
    close = getattr(value, "close", None)
    if callable(close):
        close()


def _bool_env(value: str) -> bool:
    normalized = value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    raise ValueError(f"invalid boolean environment value: {value!r}")
