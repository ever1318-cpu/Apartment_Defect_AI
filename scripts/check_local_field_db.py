"""Read-only PostgreSQL readiness check for the local field API."""

from __future__ import annotations

from data_engineering.database.config import DatabaseConfig
from data_engineering.database.connection import connect_database


def main() -> int:
    config = DatabaseConfig.from_prefixed_environment("LOCAL_APARTMENT_DB_")
    connection = connect_database(config)
    with connection:
        with connection.transaction():
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                if cursor.fetchone()[0] != 1:
                    raise RuntimeError("local PostgreSQL readiness query failed")
    print("LOCAL_FIELD_DATABASE=READY")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
