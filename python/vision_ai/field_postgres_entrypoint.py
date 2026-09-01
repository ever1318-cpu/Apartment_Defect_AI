"""Local/staging PostgreSQL-backed FastAPI factory for field integration."""

from __future__ import annotations

import os
from pathlib import Path

from data_engineering.database.config import DatabaseConfig

from .inspection_dev_app import create_inspection_dev_app
from .inspection_v2 import FakeInspectionService
from .postgres_inspection_store import PostgresInspectionStore


def create_field_postgres_app():
    config = DatabaseConfig.from_prefixed_environment("LOCAL_APARTMENT_DB_")
    media_root = Path(os.getenv("FIELD_MEDIA_ROOT", "workspace/field-media"))
    if not media_root.is_absolute():
        media_root = Path.cwd() / media_root
    app = create_inspection_dev_app(
        service=FakeInspectionService(upload_root=media_root),
        field_service=PostgresInspectionStore(config),
    )
    app.state.field_media_root = str(media_root)
    return app


app = create_field_postgres_app()
