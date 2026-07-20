from __future__ import annotations

import builtins
import json

import pytest

from data_engineering.cli import main
from data_engineering.database.config import (
    DatabaseConfig,
    DatabaseConfigurationError,
)
from data_engineering.database.connection import (
    DatabaseConnectionError,
    DatabaseQueryError,
    test_database_connection as run_connection_test,
)
from data_engineering.database.inspection import inspect_database


def _environment(**overrides: str) -> dict[str, str]:
    values = {
        "APARTMENT_DB_HOST": "db.example.invalid",
        "APARTMENT_DB_NAME": "apartments",
        "APARTMENT_DB_USER": "inspector",
        "APARTMENT_DB_PASSWORD": "do-not-print",
    }
    values.update(overrides)
    return values


class FakeCursor:
    def __init__(self) -> None:
        self.query = ""
        self.parameters = None
        self.executions: list[tuple[str, object]] = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, query, parameters=None) -> None:
        self.query = str(query)
        self.parameters = parameters
        self.executions.append((self.query, parameters))

    def fetchone(self):
        return (
            "apartments",
            "inspector",
            "PostgreSQL 17.5 on test",
        )

    def fetchall(self):
        if "FROM pg_catalog.pg_namespace" in self.query:
            return [("audit",), ("public",)]
        if "FROM pg_catalog.pg_attribute" in self.query:
            return [
                ("public", "defect_images", "id", "bigint", False, None),
                (
                    "public",
                    "defect_images",
                    "file_path",
                    "text",
                    False,
                    None,
                ),
                (
                    "public",
                    "defect_labels",
                    "defect_name",
                    "text",
                    True,
                    "'unknown'::text",
                ),
            ]
        if "FROM pg_catalog.pg_constraint" in self.query:
            if self.parameters[0] == "p":
                return [
                    (
                        "public",
                        "defect_images",
                        "defect_images_pkey",
                        "PRIMARY KEY (id)",
                    )
                ]
            return [
                (
                    "public",
                    "defect_labels",
                    "defect_labels_image_fkey",
                    "FOREIGN KEY (image_id) REFERENCES defect_images(id)",
                )
            ]
        if "FROM pg_catalog.pg_class" in self.query:
            return [
                ("public", "defect_images", "r", 41.8),
                ("public", "defect_view", "v", -1),
            ]
        raise AssertionError(self.query)


class FakeTransaction:
    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeConnection:
    def __init__(self) -> None:
        self.cursor_instance = FakeCursor()
        self.transaction_entered = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def transaction(self):
        self.transaction_entered = True
        return FakeTransaction()

    def cursor(self):
        return self.cursor_instance


def test_config_requires_credentials_and_applies_safe_defaults() -> None:
    with pytest.raises(DatabaseConfigurationError):
        DatabaseConfig.from_environment({})
    config = DatabaseConfig.from_environment(_environment())
    assert config.port == 5432
    assert config.sslmode == "require"
    assert config.connect_timeout == 10
    assert "do-not-print" not in repr(config)


def test_connection_success_uses_read_only_transaction() -> None:
    connection = FakeConnection()
    captured = {}

    def connector(**kwargs):
        captured.update(kwargs)
        return connection

    result = run_connection_test(
        DatabaseConfig.from_environment(_environment()), connector=connector
    )
    assert result.database == "apartments"
    assert captured["port"] == 5432
    assert connection.transaction_entered
    assert connection.cursor_instance.executions[0][0] == "SET TRANSACTION READ ONLY"


def test_connection_failure_never_exposes_credentials(monkeypatch, capsys) -> None:
    for key, value in _environment().items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(
        "data_engineering.cli.test_database_connection",
        lambda config: (_ for _ in ()).throw(
            DatabaseConnectionError("driver leaked do-not-print")
        ),
    )
    assert main(["vision-db-test"]) == 3
    output = capsys.readouterr().out
    assert "do-not-print" not in output
    assert "driver leaked" not in output


def test_cli_query_failure_returns_four(monkeypatch, capsys) -> None:
    for key, value in _environment().items():
        monkeypatch.setenv(key, value)
    monkeypatch.setattr(
        "data_engineering.cli.test_database_connection",
        lambda config: (_ for _ in ()).throw(DatabaseQueryError("private detail")),
    )
    assert main(["vision-db-test", "--json"]) == 4
    output = capsys.readouterr().out
    assert "private detail" not in output


def test_inspection_reads_catalogs_filters_and_finds_candidates() -> None:
    connection = FakeConnection()
    report = inspect_database(
        DatabaseConfig.from_environment(_environment()),
        schema="public",
        table="defect_images",
        connector=lambda **kwargs: connection,
    )
    assert report.schemas == ("audit", "public")
    assert report.relations[0].estimated_rows == 41
    assert report.relations[1].kind == "view"
    assert report.primary_keys[0].name == "defect_images_pkey"
    assert report.foreign_keys[0].name == "defect_labels_image_fkey"
    assert {item.column for item in report.candidates} == {
        "file_path",
        "defect_name",
    }
    assert connection.cursor_instance.executions[0][0] == "SET TRANSACTION READ ONLY"
    catalog_parameters = [
        parameters
        for query, parameters in connection.cursor_instance.executions
        if parameters
    ]
    assert any("public" in parameters for parameters in catalog_parameters)
    assert any("defect_images" in parameters for parameters in catalog_parameters)


def test_cli_json_and_atomic_output(monkeypatch, tmp_path, capsys) -> None:
    for key, value in _environment().items():
        monkeypatch.setenv(key, value)
    connection = FakeConnection()
    monkeypatch.setattr(
        "data_engineering.database.connection.load_psycopg",
        lambda: type("Driver", (), {"connect": staticmethod(lambda **kwargs: connection)}),
    )
    monkeypatch.setattr(
        "data_engineering.database.inspection.connect_database",
        lambda config, **kwargs: connection,
    )
    assert main(["vision-db-test", "--json"]) == 0
    connection_value = json.loads(capsys.readouterr().out)
    assert connection_value["database"] == "apartments"
    assert "password" not in connection_value

    output = tmp_path / "inspection.json"
    assert main(["vision-db-inspect", "--json", "--output", str(output)]) == 0
    printed = json.loads(capsys.readouterr().out)
    saved = json.loads(output.read_text(encoding="utf-8"))
    assert printed == saved
    assert saved["summary"]["table_count"] == 1


def test_cli_missing_environment_returns_two(monkeypatch, capsys) -> None:
    for key in _environment():
        monkeypatch.delenv(key, raising=False)
    assert main(["vision-db-test", "--json"]) == 2
    value = json.loads(capsys.readouterr().out)
    assert value["status"] == "error"


def test_database_modules_do_not_import_psycopg_eagerly(monkeypatch) -> None:
    original_import = builtins.__import__

    def guarded_import(name, *args, **kwargs):
        if name == "psycopg" or name.startswith("psycopg."):
            raise AssertionError("psycopg imported eagerly")
        return original_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", guarded_import)
    DatabaseConfig.from_environment(_environment())
