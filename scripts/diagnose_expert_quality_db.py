"""Credential-safe read-only PostgreSQL connectivity diagnostic."""

from __future__ import annotations

import re
import sys

from data_engineering.database.config import DatabaseConfig, DatabaseConfigurationError
from data_engineering.database.connection import load_psycopg


def redact(message: str) -> str:
    """Remove password/DSN fragments while preserving actionable server errors."""
    message = re.sub(r"(?i)(password\s*[=:]\s*)\S+", r"\1[REDACTED]", message)
    message = re.sub(r"(?i)(postgres(?:ql)?://[^:\s]+:)[^@\s]+@", r"\1[REDACTED]@", message)
    return message


def main() -> int:
    try:
        config = DatabaseConfig.from_environment()
    except DatabaseConfigurationError as exc:
        print(f"CONFIGURATION_ERROR={exc}")
        return 2

    print(f"HOST={config.host}")
    print(f"PORT={config.port}")
    print(f"DATABASE={config.database}")
    print(f"USER={config.user}")
    print(f"SSLMODE={config.sslmode}")
    print("READ_ONLY_TEST=starting")

    try:
        with load_psycopg().connect(**config.connection_parameters()) as connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SET TRANSACTION READ ONLY")
                    cursor.execute("SELECT current_database(), current_user")
                    database, user = cursor.fetchone()
    except Exception as exc:
        print(f"CONNECTION_ERROR_TYPE={type(exc).__name__}")
        print(f"CONNECTION_ERROR={redact(str(exc))}")
        return 3

    print("READ_ONLY_TEST=PASS")
    print(f"CONNECTED_DATABASE={database}")
    print(f"CONNECTED_USER={user}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())