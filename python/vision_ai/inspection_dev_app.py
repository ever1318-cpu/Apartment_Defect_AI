"""Standalone development app for API v2 contract and device E2E testing."""

from __future__ import annotations

import uuid
from typing import Any

from .inspection_v2 import FakeInspectionService, InspectionContractError
from .field_app import register_field_routes

try:
    from fastapi import FastAPI, Request
    from fastapi.responses import JSONResponse
except ImportError:  # pragma: no cover - reported clearly by the factory
    FastAPI = None
    Request = Any
    JSONResponse = None


def create_inspection_dev_app(
    service: FakeInspectionService | None = None,
    field_service: Any | None = None,
) -> Any:
    if FastAPI is None:
        raise RuntimeError("fastapi is required for the development API")

    app = FastAPI(title="Apartment Defect AI Development API", version="2.0.0")
    app.state.inspection_v2 = service or FakeInspectionService()
    register_field_routes(app, field_service)

    @app.exception_handler(InspectionContractError)
    async def contract_error(_request: Request, exc: InspectionContractError):
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "code": exc.code,
                "message": str(exc),
                "trace_id": str(uuid.uuid4()),
                "retryable": exc.status_code >= 500,
            },
        )

    @app.get("/health")
    async def health():
        database_ready = True
        if hasattr(app.state.field_service, "healthcheck"):
            try:
                database_ready = bool(app.state.field_service.healthcheck())
            except Exception:  # Development health must not disclose connection details.
                database_ready = False
        return {
            "status": "healthy" if database_ready else "degraded",
            "persistence": (
                "postgresql" if database_ready and
                app.state.field_service.__class__.__name__ == "PostgresInspectionStore"
                else "memory-only"
            ),
            "database": "ready" if database_ready else "unavailable",
            "field_api": "/v2/field",
            "field_media_root": getattr(app.state, "field_media_root", None),
        }

    @app.get("/v2/taxonomies/{version}")
    async def taxonomy(version: str):
        return app.state.inspection_v2.taxonomy(version)

    @app.post("/v2/media/upload-sessions", status_code=201)
    async def create_upload_session(request: Request):
        return app.state.inspection_v2.create_upload_session(
            await request.json(),
            idempotency_key=_idempotency_key(request),
        )

    @app.put("/v2/media/uploads/{media_id}")
    async def upload_media(media_id: str, request: Request):
        return app.state.inspection_v2.accept_upload(
            media_id,
            await request.body(),
            content_type=request.headers.get("content-type", ""),
        )

    @app.post("/v2/inspections", status_code=201)
    async def create_inspection(request: Request):
        return app.state.inspection_v2.create_inspection(
            await request.json(),
            idempotency_key=_idempotency_key(request),
        )

    @app.get("/v2/inspections/{inspection_id}")
    async def get_inspection(inspection_id: str):
        return app.state.inspection_v2.get_inspection(inspection_id)

    @app.post("/v2/inspections/{inspection_id}/analysis", status_code=202)
    async def analyze(inspection_id: str, request: Request):
        return app.state.inspection_v2.analyze(
            inspection_id,
            await request.json(),
            idempotency_key=_idempotency_key(request),
        )

    @app.get("/v2/analyses/{analysis_id}")
    async def get_analysis(analysis_id: str):
        return app.state.inspection_v2.get_analysis(analysis_id)

    @app.post("/v2/assistant/sessions", status_code=201)
    async def create_assistant(request: Request):
        return app.state.inspection_v2.create_assistant_session(
            await request.json(),
            idempotency_key=_idempotency_key(request),
        )

    @app.post("/v2/assistant/sessions/{session_id}/messages")
    async def answer(session_id: str, request: Request):
        return app.state.inspection_v2.answer(
            session_id,
            await request.json(),
            idempotency_key=_idempotency_key(request),
        )

    @app.post("/v2/inspections/{inspection_id}/confirmation")
    async def confirm(inspection_id: str, request: Request):
        revision = request.headers.get("if-match", "").strip().strip('"')
        if not revision.isdigit():
            raise InspectionContractError(
                "INVALID_REVISION", "If-Match must contain the numeric revision"
            )
        return app.state.inspection_v2.confirm(
            inspection_id,
            await request.json(),
            idempotency_key=_idempotency_key(request),
            expected_revision=int(revision),
        )

    return app


def _idempotency_key(request: Any) -> str:
    return request.headers.get("idempotency-key", "")


app = create_inspection_dev_app()
