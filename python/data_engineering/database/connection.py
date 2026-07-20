"""Lazy psycopg connection boundary with credential-safe errors."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from .config import DatabaseConfig


class DatabaseConnectionError(RuntimeError):
    """Raised when PostgreSQL cannot be reached or authenticated."""


class DatabaseQueryError(RuntimeError):
    """Raised when a connected database query fails."""


@dataclass(frozen=True)
class DatabaseConnectionInfo:
    host: str
    database: str
    user: str
    postgres_version: str
    sslmode: str

    def to_dict(self) -> dict[str, object]:
        return {
            "status": "success",
            "host": self.host,
            "database": self.database,
            "user": self.user,
            "postgres_version": self.postgres_version,
            "sslmode": self.sslmode,
        }


def load_psycopg() -> Any:
    try:
        import psycopg
    except ImportError as exc:
        raise DatabaseConnectionError(
            "PostgreSQL support is not installed; install `psycopg[binary]`"
        ) from exc
    return psycopg


def connect_database(
    config: DatabaseConfig,
    *,
    connector: Callable[..., Any] | None = None,
) -> Any:
    connect = connector or load_psycopg().connect
    try:
        return connect(**config.connection_parameters())
    except Exception as exc:
        raise DatabaseConnectionError(
            "database connection failed; verify the configured endpoint and credentials"
        ) from exc


def test_database_connection(
    config: DatabaseConfig,
    *,
    connector: Callable[..., Any] | None = None,
) -> DatabaseConnectionInfo:
    connection = connect_database(config, connector=connector)
    try:
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SET TRANSACTION READ ONLY")
                    cursor.execute(
                        "SELECT current_database(), current_user, version()"
                    )
                    row = cursor.fetchone()
                    if row is None or len(row) < 3:
                        raise DatabaseQueryError(
                            "database connection query returned no result"
                        )
    except DatabaseQueryError:
        raise
    except Exception as exc:
        raise DatabaseQueryError(
            "database connection query failed"
        ) from exc
    return DatabaseConnectionInfo(
        host=config.host,
        database=str(row[0]),
        user=str(row[1]),
        postgres_version=str(row[2]),
        sslmode=config.sslmode,
    )
