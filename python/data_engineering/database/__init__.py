"""Optional PostgreSQL connectivity and catalog inspection."""

from .config import DatabaseConfig, DatabaseConfigurationError
from .connection import (
    DatabaseConnectionError,
    DatabaseQueryError,
    test_database_connection,
)
from .vision_inspection import DatabaseInspection, inspect_database
from .dataset import extract_defect_dataset_rows

__all__ = [
    "DatabaseConfig",
    "DatabaseConfigurationError",
    "DatabaseConnectionError",
    "DatabaseInspection",
    "DatabaseQueryError",
    "inspect_database",
    "extract_defect_dataset_rows",
    "test_database_connection",
]
