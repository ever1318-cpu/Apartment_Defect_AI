"""Environment-only PostgreSQL configuration."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Mapping


class DatabaseConfigurationError(ValueError):
    """Raised when required database settings are missing or invalid."""


@dataclass(frozen=True)
class DatabaseConfig:
    host: str
    database: str
    user: str
    password: str = field(repr=False)
    port: int = 5432
    sslmode: str = "require"
    connect_timeout: int = 10

    @classmethod
    def from_environment(
        cls, environment: Mapping[str, str] | None = None
    ) -> "DatabaseConfig":
        return cls.from_prefixed_environment("APARTMENT_DB_", environment)

    @classmethod
    def from_prefixed_environment(
        cls,
        prefix: str,
        environment: Mapping[str, str] | None = None,
    ) -> "DatabaseConfig":
        values = os.environ if environment is None else environment
        required = {
            "host": f"{prefix}HOST",
            "database": f"{prefix}NAME",
            "user": f"{prefix}USER",
            "password": f"{prefix}PASSWORD",
        }
        missing = [
            variable
            for variable in required.values()
            if not values.get(variable, "").strip()
        ]
        if missing:
            raise DatabaseConfigurationError(
                "missing database configuration: " + ", ".join(sorted(missing))
            )
        try:
            port = int(values.get(f"{prefix}PORT", "5432"))
        except ValueError as exc:
            raise DatabaseConfigurationError(
                f"{prefix}PORT must be an integer"
            ) from exc
        if not 1 <= port <= 65535:
            raise DatabaseConfigurationError(
                f"{prefix}PORT must be between 1 and 65535"
            )
        sslmode = values.get(f"{prefix}SSLMODE", "require").strip()
        if not sslmode:
            raise DatabaseConfigurationError(f"{prefix}SSLMODE must not be empty")
        return cls(
            host=values[required["host"]].strip(),
            database=values[required["database"]].strip(),
            user=values[required["user"]].strip(),
            password=values[required["password"]],
            port=port,
            sslmode=sslmode,
        )

    def connection_parameters(self) -> dict[str, object]:
        """Return keyword parameters without constructing a printable DSN."""
        return {
            "host": self.host,
            "port": self.port,
            "dbname": self.database,
            "user": self.user,
            "password": self.password,
            "sslmode": self.sslmode,
            "connect_timeout": self.connect_timeout,
        }
