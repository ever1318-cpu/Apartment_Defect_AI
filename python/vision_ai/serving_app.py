"""Optional FastAPI application factory; no serving dependency is imported eagerly."""

import base64
import binascii
import json
import logging
import time
import uuid
from typing import Any, Mapping

from .serving import APIError, ServiceError, ServingConfig, ServingService

LOGGER = logging.getLogger("apartment_defect_ai.serving")


def create_serving_app(
    config: ServingConfig,
    *,
    service: ServingService | None = None,
) -> Any:
    try:
        from fastapi import FastAPI, Request
        from fastapi.responses import JSONResponse
    except ImportError as exc:
        raise RuntimeError(
            "Vision serving requires optional dependencies; "
            "install with `pip install -e \".[serving]\"`"
        ) from exc

    runtime = service or ServingService(config)
    app = FastAPI(title="Apartment Defect AI", version="1.0")
    app.state.service = runtime
    app.state.ready = False

    @app.on_event("startup")
    async def startup() -> None:
        app.state.ready = runtime.models.ready()
        runtime.metrics.gauge("readiness_state", app.state.ready)

    @app.on_event("shutdown")
    async def shutdown() -> None:
        runtime.models.close()

    @app.middleware("http")
    async def request_context(request: Request, call_next):
        request_id = request.headers.get("x-request-id") or str(uuid.uuid4())
        request.state.request_id = request_id
        started = time.perf_counter()
        try:
            response = await call_next(request)
        except Exception:
            LOGGER.error(
                json.dumps(
                    {
                        "request_id": request_id,
                        "status": "error",
                        "error_code": "INTERNAL_ERROR",
                    }
                )
            )
            response = JSONResponse(
                status_code=500,
                content=APIError(
                    "INTERNAL_ERROR",
                    "internal server error",
                    request_id=request_id,
                ).to_dict(),
            )
        response.headers["x-request-id"] = request_id
        runtime.metrics.response(response.status_code)
        LOGGER.info(
            json.dumps(
                {
                    "request_id": request_id,
                    "status": response.status_code,
                    "duration_seconds": time.perf_counter() - started,
                }
            )
        )
        return response

    @app.exception_handler(ServiceError)
    async def service_error(request: Request, exc: ServiceError):
        return JSONResponse(
            status_code=exc.status_code,
            content=APIError(
                exc.code,
                str(exc),
                exc.details,
                request.state.request_id,
            ).to_dict(),
        )

    @app.get("/health")
    async def health():
        return {"status": "healthy"}

    @app.get("/ready")
    async def ready(request: Request):
        value = runtime.models.ready()
        app.state.ready = value
        runtime.metrics.gauge("readiness_state", value)
        if not value:
            raise ServiceError(
                "MODEL_NOT_READY", "production model is not ready", status_code=503
            )
        return {"status": "ready"}

    @app.get("/v1/models")
    async def list_models():
        return {
            "revision": runtime.registry.read().revision,
            "models": [item.to_dict() for item in runtime.registry.list()],
        }

    @app.get("/v1/models/{model_name}")
    async def get_model_versions(model_name: str):
        values = runtime.registry.list(model_name)
        if not values:
            raise ServiceError("MODEL_NOT_FOUND", "model not found", status_code=404)
        return {"models": [item.to_dict() for item in values]}

    @app.get("/v1/models/{model_name}/{model_version}")
    async def get_model(model_name: str, model_version: str):
        try:
            return runtime.registry.get(model_name, model_version).to_dict()
        except (KeyError, ValueError):
            raise ServiceError(
                "MODEL_NOT_FOUND", "model version not found", status_code=404
            ) from None

    @app.post("/v1/predict")
    async def predict(request: Request):
        payload = await _json_payload(request)
        item = _prediction_item(payload, config)
        prediction = runtime.predict(**item)
        return prediction.to_dict()

    @app.post("/v1/predict/batch")
    async def predict_batch(request: Request):
        payload = await _json_payload(request)
        items = payload.get("items")
        if not isinstance(items, list):
            raise ServiceError("INVALID_REQUEST", "items must be an array")
        if len(items) > config.max_batch_size:
            raise ServiceError(
                "BATCH_TOO_LARGE",
                "batch exceeds configured maximum",
                status_code=413,
            )
        encoded_size = sum(
            len(item.get("image_base64", ""))
            for item in items
            if isinstance(item, Mapping)
            and isinstance(item.get("image_base64", ""), str)
        )
        if encoded_size > ((config.max_batch_bytes + 2) // 3) * 4 + 4:
            runtime.metrics.increment("request_rejection_count")
            raise ServiceError(
                "PAYLOAD_TOO_LARGE",
                "batch payload exceeds configured maximum",
                status_code=413,
            )
        fail_fast = bool(payload.get("fail_fast", False))
        runtime.metrics.increment("batch_request_count")
        results = []
        for item in items:
            try:
                prepared = _prediction_item(item, config)
                prediction = runtime.predict(**prepared)
                results.append(
                    {"status": "success", "prediction": prediction.to_dict()}
                )
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
        return {"results": results}

    @app.get("/v1/metrics")
    async def metrics():
        return runtime.metrics.snapshot()

    return app


async def _json_payload(request: Any) -> Mapping[str, Any]:
    content_type = request.headers.get("content-type", "").split(";")[0].strip()
    if content_type != "application/json":
        raise ServiceError(
            "UNSUPPORTED_MEDIA_TYPE",
            "serving API accepts application/json base64 requests",
            status_code=415,
        )
    try:
        value = await request.json()
    except Exception:
        raise ServiceError("INVALID_REQUEST", "request body is not valid JSON") from None
    if not isinstance(value, dict):
        raise ServiceError("INVALID_REQUEST", "request body must be an object")
    if _json_depth(value) > request.app.state.service.config.max_json_depth:
        raise ServiceError("INVALID_REQUEST", "request JSON is too deeply nested")
    return value


def _prediction_item(
    payload: Mapping[str, Any], config: ServingConfig
) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise ServiceError("INVALID_REQUEST", "prediction item must be an object")
    encoded = payload.get("image_base64")
    if not isinstance(encoded, str) or not encoded:
        raise ServiceError("INVALID_REQUEST", "image_base64 is required")
    if len(encoded) > ((config.max_upload_bytes + 2) // 3) * 4 + 4:
        raise ServiceError(
            "PAYLOAD_TOO_LARGE",
            "encoded image exceeds configured maximum size",
            status_code=413,
        )
    try:
        image = base64.b64decode(encoded, validate=True)
    except (binascii.Error, ValueError):
        raise ServiceError("INVALID_IMAGE", "image_base64 is invalid") from None
    image_id = payload.get("image_id") or str(uuid.uuid4())
    if not isinstance(image_id, str) or not image_id.strip():
        raise ServiceError("INVALID_REQUEST", "image_id must be a non-empty string")
    mime_type = payload.get("mime_type")
    if not isinstance(mime_type, str):
        raise ServiceError("INVALID_REQUEST", "mime_type is required")
    return {
        "image": image,
        "mime_type": mime_type,
        "image_id": image_id,
        "model_name": payload.get("model_name"),
        "model_version": payload.get("model_version"),
        "confidence_threshold": payload.get("confidence_threshold"),
    }


def _json_depth(value: Any) -> int:
    if isinstance(value, Mapping):
        return 1 + max((_json_depth(item) for item in value.values()), default=0)
    if isinstance(value, list):
        return 1 + max((_json_depth(item) for item in value), default=0)
    return 1
