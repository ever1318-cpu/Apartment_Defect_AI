"""Optional PostgreSQL connectivity and catalog inspection."""

from .config import DatabaseConfig, DatabaseConfigurationError
from .connection import (
    DatabaseConnectionError,
    DatabaseQueryError,
    test_database_connection,
)
from .inspection import DatabaseInspection, inspect_database

__all__ = [
    "DatabaseConfig",
    "DatabaseConfigurationError",
    "DatabaseConnectionError",
    "DatabaseInspection",
    "DatabaseQueryError",
    "inspect_database",
    "test_database_connection",
]
