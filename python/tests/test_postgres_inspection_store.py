import uuid

import pytest

from data_engineering.database.config import DatabaseConfig
from vision_ai.postgres_inspection_store import (
    PersistenceConflict,
    PostgresInspectionStore,
)


class Cursor:
    def __init__(self, rows):
        self.rows = iter(rows)
        self.executed = []

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def execute(self, sql, params=()):
        self.executed.append((sql, params))

    def fetchone(self):
        return next(self.rows)


class Transaction:
    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False


class Connection:
    def __init__(self, rows):
        self.cursor_value = Cursor(rows)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def transaction(self):
        return Transaction()

    def cursor(self):
        return self.cursor_value


def config():
    return DatabaseConfig(
        host="db.invalid", database="test", user="tester", password="secret"
    )


def test_create_session_is_transactional_idempotent_and_parameterized():
    session_id = uuid.uuid4()
    client_id = uuid.uuid4()
    connection = Connection(
        [None, (session_id, client_id, "ANCHOR_REQUIRED", 1)]
    )
    store = PostgresInspectionStore(config(), connector=lambda **_: connection)
    result = store.create_session(
        {
            "client_uuid": str(client_id),
            "household_id": str(uuid.uuid4()),
            "inspector_id": "점검매니저",
        },
        idempotency_key="session-idempotency-0001",
    )
    assert result["id"] == str(session_id)
    assert result["revision"] == 1
    sql_text = "\n".join(sql for sql, _ in connection.cursor_value.executed)
    assert "INSERT INTO apartment_ai.inspection_sessions" in sql_text
    assert "secret" not in sql_text
    assert all("%s" in sql for sql, _ in connection.cursor_value.executed)


def test_cached_request_rejects_key_reuse_with_different_payload():
    cursor = Cursor([("a" * 64, {"id": "saved"})])
    with pytest.raises(PersistenceConflict):
        PostgresInspectionStore._cached(
            cursor, "create_session", "session-idempotency-0002", "b" * 64
        )


def test_schema_contains_raw_opinion_revision_and_required_indexes():
    from pathlib import Path

    sql = (
        Path(__file__).parents[2]
        / "database"
        / "migrations"
        / "001_apartment_ai_core.sql"
    ).read_text(encoding="utf-8")
    assert "raw_resident_opinion text NOT NULL" in sql
    assert "revision integer NOT NULL DEFAULT 1" in sql
    assert "idempotency_records" in sql
    assert "sync_operations" in sql
    assert "ix_defects_session_created" in sql
