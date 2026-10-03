"""FastAPI router for field inspection persistence and offline synchronization."""

from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any

from .field_inspection import FieldServiceError, MemoryFieldInspectionService
from .postgres_inspection_store import PersistenceConflict

try:
    from fastapi import Request
    from fastapi.responses import FileResponse, JSONResponse
except ImportError:  # pragma: no cover - serving extra reports this at startup
    Request = Any
    JSONResponse = None


def register_field_routes(app: Any, service: Any | None = None) -> None:
    app.state.field_service = service or MemoryFieldInspectionService()

    @app.exception_handler(FieldServiceError)
    async def field_error(_request: Request, exc: FieldServiceError):
        return JSONResponse(
            status_code=exc.status_code,
            content={"code": exc.code, "message": str(exc), "trace_id": str(uuid.uuid4()), "retryable": exc.status_code >= 500},
        )

    @app.exception_handler(PersistenceConflict)
    async def persistence_conflict(_request: Request, exc: PersistenceConflict):
        return JSONResponse(
            status_code=409,
            content={"code": "REVISION_CONFLICT", "message": str(exc), "trace_id": str(uuid.uuid4()), "retryable": False},
        )

    @app.post("/v2/field/sessions", status_code=201)
    async def create_session(request: Request):
        return app.state.field_service.create_session(
            await request.json(), idempotency_key=_key(request)
        )

    @app.get("/v2/field/sessions/resolve")
    async def resolve_session(client_uuid: str):
        try:
            return app.state.field_service.resolve_session(client_uuid)
        except ValueError as exc:
            raise FieldServiceError("SESSION_NOT_FOUND", str(exc), 404) from exc

    @app.get("/v2/field/households/resolve")
    async def resolve_household(building_no: str, unit_no: str):
        try:
            return app.state.field_service.resolve_household(building_no, unit_no)
        except ValueError as exc:
            raise FieldServiceError("HOUSEHOLD_NOT_FOUND", str(exc), 404) from exc

    @app.get("/v2/field/taxonomy")
    async def taxonomy_catalog(floorplan_type: str, room_code: str | None = None, surface_code: str | None = None):
        try:
            payload = app.state.field_service.taxonomy_catalog(floorplan_type, room_code, surface_code)
            # Windows PowerShell 5.1 otherwise decodes JSON with the active ANSI code page.
            return JSONResponse(content=payload, media_type="application/json; charset=utf-8")
        except ValueError as exc:
            raise FieldServiceError("TAXONOMY_NOT_FOUND", str(exc), 404) from exc
    @app.get("/v2/field/inspections/manager-stats")
    async def manager_stats(inspector_id: str = "점검매니저"):
        return app.state.field_service.manager_statistics(inspector_id)

    @app.get("/v2/field/gallery/households")
    async def gallery_households():
        return {"households": app.state.field_service.gallery_households()}

    @app.get("/v2/field/gallery/households/{building_no}/{unit_no}")
    async def gallery_for_household(building_no: str, unit_no: str):
        try:
            return app.state.field_service.gallery_for_household(building_no, unit_no)
        except ValueError as exc:
            raise FieldServiceError("HOUSEHOLD_NOT_FOUND", str(exc), 404) from exc

    @app.get("/v2/field/media/{media_id}/content")
    async def media_content(media_id: str):
        try:
            media = app.state.field_service.gallery_media(media_id)
        except ValueError as exc:
            raise FieldServiceError("MEDIA_NOT_FOUND", str(exc), 404) from exc
        object_key = str(media.get("object_key", ""))
        if not object_key.startswith("field-media/"):
            raise FieldServiceError("MEDIA_NOT_FOUND", "media object is unavailable", 404)
        root = Path(app.state.field_media_root).resolve()
        file_path = _media_file_path(root, object_key, media.get("metadata"))
        if file_path is None:
            raise FieldServiceError("MEDIA_NOT_FOUND", "media file is unavailable", 404)
        return FileResponse(file_path, media_type=str(media.get("mime_type", "image/jpeg")))

    @app.put("/v2/field/sessions/{session_id}/anchor")
    async def set_anchor(session_id: str, request: Request):
        return app.state.field_service.set_anchor(
            session_id, await request.json(), expected_revision=_revision(request)
        )

    @app.get("/v2/field/sessions/{session_id}/summary")
    async def summary(session_id: str):
        return app.state.field_service.session_summary(session_id)

    @app.post("/v2/field/sessions/{session_id}/complete")
    async def complete(session_id: str, request: Request):
        return app.state.field_service.complete_session(
            session_id, expected_revision=_revision(request)
        )

    @app.put("/v2/field/defects", status_code=201)
    async def upsert_defect(request: Request):
        return app.state.field_service.upsert_defect(
            await request.json(),
            idempotency_key=_key(request),
            expected_revision=_revision(request, default=0),
        )

    @app.post("/v2/field/defects/{defect_id}/confirmation")
    async def confirm_defect(defect_id: str, request: Request):
        return app.state.field_service.confirm_defect(
            defect_id, await request.json(), expected_revision=_revision(request)
        )

    @app.post("/v2/field/defects/{defect_id}/media", status_code=201)
    async def register_media(defect_id: str, request: Request):
        return app.state.field_service.register_media(
            defect_id, await request.json(), idempotency_key=_key(request)
        )

    @app.post("/v2/field/sync/batches")
    async def sync_batch(request: Request):
        return app.state.field_service.sync_batch(await request.json())


def _key(request: Any) -> str:
    return request.headers.get("idempotency-key", "")


def _revision(request: Any, default: int | None = None) -> int:
    value = request.headers.get("if-match", "").strip().strip('"')
    if not value and default is not None:
        return default
    if not value.isdigit():
        raise FieldServiceError("INVALID_REVISION", "If-Match must contain a numeric revision")
    return int(value)


def _media_file_path(root: Path, object_key: str, metadata: Any) -> Path | None:
    """Resolve a gallery image without allowing paths outside the media root.

    Older mobile builds registered the display filename while the upload service
    replaced separators such as ``·`` with ``_``.  The deterministic local
    photo id gives those older records a safe, precise recovery path.
    """
    candidate = (root / Path(object_key).name).resolve()
    if root in candidate.parents and candidate.is_file():
        return candidate
    local_photo_id = metadata.get("local_photo_id") if isinstance(metadata, dict) else None
    try:
        suffix = f"P{int(local_photo_id):05d}"
    except (TypeError, ValueError):
        return None
    matches = [path.resolve() for path in root.glob(f"*{suffix}.*") if path.is_file()]
    safe_matches = [path for path in matches if root in path.parents]
    return safe_matches[0] if len(safe_matches) == 1 else None
