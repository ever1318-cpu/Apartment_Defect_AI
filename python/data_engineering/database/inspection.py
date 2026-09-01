"""Read-only PostgreSQL catalog inspection."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Callable, Iterable

from .config import DatabaseConfig
from .connection import DatabaseQueryError, connect_database

_CANDIDATE_KEYWORDS = (
    "image", "img", "photo", "picture", "file", "path",
    "label", "defect", "category", "class", "damage",
    "이미지", "사진", "파일", "경로", "라벨", "하자", "분류",
)
_SYSTEM_SCHEMAS = ("pg_catalog", "information_schema")


@dataclass(frozen=True)
class RelationInfo:
    schema: str
    name: str
    kind: str
    estimated_rows: int


@dataclass(frozen=True)
class ColumnInfo:
    schema: str
    table: str
    name: str
    data_type: str
    nullable: bool
    default: str | None


@dataclass(frozen=True)
class ConstraintInfo:
    schema: str
    table: str
    name: str
    definition: str


@dataclass(frozen=True)
class CandidateColumn:
    schema: str
    table: str
    column: str
    matched_keywords: tuple[str, ...]


@dataclass(frozen=True)
class DatabaseInspection:
    host: str
    database: str
    user: str
    sslmode: str
    schemas: tuple[str, ...]
    relations: tuple[RelationInfo, ...]
    columns: tuple[ColumnInfo, ...]
    primary_keys: tuple[ConstraintInfo, ...]
    foreign_keys: tuple[ConstraintInfo, ...]
    candidates: tuple[CandidateColumn, ...]

    def to_dict(self) -> dict[str, object]:
        tables = sum(item.kind == "table" for item in self.relations)
        views = sum(item.kind == "view" for item in self.relations)
        return {
            "connection": {
                "host": self.host,
                "database": self.database,
                "user": self.user,
                "sslmode": self.sslmode,
            },
            "summary": {
                "schema_count": len(self.schemas),
                "table_count": tables,
                "view_count": views,
                "column_count": len(self.columns),
            },
            "schemas": list(self.schemas),
            "relations": [asdict(item) for item in self.relations],
            "columns": [asdict(item) for item in self.columns],
            "primary_keys": [asdict(item) for item in self.primary_keys],
            "foreign_keys": [asdict(item) for item in self.foreign_keys],
            "candidates": [
                {
                    **asdict(item),
                    "matched_keywords": list(item.matched_keywords),
                }
                for item in self.candidates
            ],
        }


def inspect_database(
    config: DatabaseConfig,
    *,
    schema: str | None = None,
    table: str | None = None,
    include_system: bool = False,
    connector: Callable[..., Any] | None = None,
) -> DatabaseInspection:
    connection = connect_database(config, connector=connector)
    filters = (schema, table, include_system)
    try:
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SET TRANSACTION READ ONLY")
                    schemas = _fetch_schemas(cursor, schema, include_system)
                    relations = _fetch_relations(cursor, filters)
                    columns = _fetch_columns(cursor, filters)
                    primary_keys = _fetch_constraints(cursor, filters, "p")
                    foreign_keys = _fetch_constraints(cursor, filters, "f")
    except Exception as exc:
        if isinstance(exc, DatabaseQueryError):
            raise
        raise DatabaseQueryError("database catalog inspection failed") from exc

    column_infos = tuple(
        ColumnInfo(
            str(row[0]), str(row[1]), str(row[2]), str(row[3]),
            bool(row[4]), None if row[5] is None else str(row[5]),
        )
        for row in columns
    )
    candidates = tuple(
        CandidateColumn(
            item.schema,
            item.table,
            item.name,
            tuple(keyword for keyword in _CANDIDATE_KEYWORDS if keyword in item.name.lower()),
        )
        for item in column_infos
        if any(keyword in item.name.lower() for keyword in _CANDIDATE_KEYWORDS)
    )
    return DatabaseInspection(
        config.host,
        config.database,
        config.user,
        config.sslmode,
        tuple(str(row[0]) for row in schemas),
        tuple(
            RelationInfo(
                str(row[0]),
                str(row[1]),
                "view" if str(row[2]) in {"v", "m"} else "table",
                max(0, int(float(row[3] or 0))),
            )
            for row in relations
        ),
        column_infos,
        tuple(ConstraintInfo(*(str(value) for value in row[:4])) for row in primary_keys),
        tuple(ConstraintInfo(*(str(value) for value in row[:4])) for row in foreign_keys),
        candidates,
    )


def _where(filters: tuple[str | None, str | None, bool], aliases: tuple[str, str]) -> tuple[str, list[str]]:
    schema, table, include_system = filters
    clauses: list[str] = []
    parameters: list[str] = []
    schema_column, table_column = aliases
    if not include_system:
        clauses.append(f"{schema_column} NOT IN (%s, %s)")
        parameters.extend(_SYSTEM_SCHEMAS)
        clauses.append(f"{schema_column} NOT LIKE 'pg_toast%%'")
    if schema:
        clauses.append(f"{schema_column} = %s")
        parameters.append(schema)
    if table:
        clauses.append(f"{table_column} = %s")
        parameters.append(table)
    return (" AND ".join(clauses) if clauses else "TRUE"), parameters


def _fetch_schemas(cursor: Any, schema: str | None, include_system: bool) -> Iterable[Any]:
    clauses = ["TRUE"]
    parameters: list[str] = []
    if not include_system:
        clauses.extend(("nspname NOT IN (%s, %s)", "nspname NOT LIKE 'pg_toast%%'"))
        parameters.extend(_SYSTEM_SCHEMAS)
    if schema:
        clauses.append("nspname = %s")
        parameters.append(schema)
    cursor.execute(
        "SELECT nspname FROM pg_catalog.pg_namespace "
        f"WHERE {' AND '.join(clauses)} ORDER BY nspname",
        parameters,
    )
    return cursor.fetchall()


def _fetch_relations(cursor: Any, filters: tuple[str | None, str | None, bool]) -> Iterable[Any]:
    where, parameters = _where(filters, ("n.nspname", "c.relname"))
    cursor.execute(
        "SELECT n.nspname, c.relname, c.relkind, c.reltuples "
        "FROM pg_catalog.pg_class AS c "
        "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
        f"WHERE c.relkind IN ('r','p','v','m','f') AND {where} "
        "ORDER BY n.nspname, c.relname",
        parameters,
    )
    return cursor.fetchall()


def _fetch_columns(cursor: Any, filters: tuple[str | None, str | None, bool]) -> Iterable[Any]:
    where, parameters = _where(filters, ("n.nspname", "c.relname"))
    cursor.execute(
        "SELECT n.nspname, c.relname, a.attname, "
        "pg_catalog.format_type(a.atttypid, a.atttypmod), NOT a.attnotnull, "
        "pg_catalog.pg_get_expr(d.adbin, d.adrelid) "
        "FROM pg_catalog.pg_attribute AS a "
        "JOIN pg_catalog.pg_class AS c ON c.oid = a.attrelid "
        "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
        "LEFT JOIN pg_catalog.pg_attrdef AS d "
        "ON d.adrelid = a.attrelid AND d.adnum = a.attnum "
        f"WHERE a.attnum > 0 AND NOT a.attisdropped AND {where} "
        "ORDER BY n.nspname, c.relname, a.attnum",
        parameters,
    )
    return cursor.fetchall()


def _fetch_constraints(
    cursor: Any,
    filters: tuple[str | None, str | None, bool],
    constraint_type: str,
) -> Iterable[Any]:
    where, parameters = _where(filters, ("n.nspname", "c.relname"))
    cursor.execute(
        "SELECT n.nspname, c.relname, con.conname, "
        "pg_catalog.pg_get_constraintdef(con.oid, true) "
        "FROM pg_catalog.pg_constraint AS con "
        "JOIN pg_catalog.pg_class AS c ON c.oid = con.conrelid "
        "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
        f"WHERE con.contype = %s AND {where} "
        "ORDER BY n.nspname, c.relname, con.conname",
        [constraint_type, *parameters],
    )
    return cursor.fetchall()
