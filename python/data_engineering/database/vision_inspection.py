"""Read-only PostgreSQL catalog inspection and vision-data discovery."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Any, Callable

from .config import DatabaseConfig
from .connection import DatabaseQueryError, connect_database

IMAGE_KEYWORDS = ("image", "img", "photo", "picture", "file", "filepath", "file_path",
                  "path", "이미지", "사진", "파일", "경로")
LABEL_KEYWORDS = ("defect", "label", "category", "class", "damage", "inspection",
                  "하자", "라벨", "분류", "점검")
SYSTEM_SCHEMAS = ("pg_catalog", "information_schema")


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
class PrimaryKeyInfo:
    schema: str
    table: str
    name: str
    columns: tuple[str, ...]


@dataclass(frozen=True)
class ForeignKeyInfo:
    schema: str
    table: str
    name: str
    columns: tuple[str, ...]
    referenced_schema: str
    referenced_table: str
    referenced_columns: tuple[str, ...]


@dataclass(frozen=True)
class CandidateInfo:
    schema: str
    table: str
    score: int
    categories: tuple[str, ...]
    reasons: tuple[str, ...]
    matched_columns: tuple[str, ...]


def _serialize(item: object) -> dict[str, object]:
    return {
        key: list(value) if isinstance(value, tuple) else value
        for key, value in asdict(item).items()
    }


@dataclass(frozen=True)
class DatabaseInspection:
    host: str
    database: str
    user: str
    sslmode: str
    schemas: tuple[str, ...]
    relations: tuple[RelationInfo, ...]
    columns: tuple[ColumnInfo, ...]
    primary_keys: tuple[PrimaryKeyInfo, ...]
    foreign_keys: tuple[ForeignKeyInfo, ...]
    candidates: tuple[CandidateInfo, ...]

    def to_dict(self, *, top_candidates: int | None = None) -> dict[str, object]:
        selected = self.candidates[:top_candidates] if top_candidates else self.candidates
        return {
            "connection": {"host": self.host, "database": self.database,
                           "user": self.user, "sslmode": self.sslmode},
            "summary": {
                "schema_count": len(self.schemas),
                "table_count": sum(x.kind == "table" for x in self.relations),
                "view_count": sum(x.kind == "view" for x in self.relations),
                "column_count": len(self.columns),
                "candidate_count": len(self.candidates),
            },
            "schemas": list(self.schemas),
            "relations": [asdict(x) for x in self.relations],
            "columns": [asdict(x) for x in self.columns],
            "primary_keys": [_serialize(x) for x in self.primary_keys],
            "foreign_keys": [_serialize(x) for x in self.foreign_keys],
            "candidates": [_serialize(x) for x in selected],
        }

    def summary_text(self, *, top_candidates: int | None = None) -> str:
        selected = self.candidates[:top_candidates] if top_candidates else self.candidates
        image_tables = [f"{x.schema}.{x.table}" for x in selected if "image" in x.categories]
        label_tables = [f"{x.schema}.{x.table}" for x in selected if "label_defect" in x.categories]
        image_columns = _candidate_columns(self.columns, IMAGE_KEYWORDS)
        label_columns = _candidate_columns(self.columns, LABEL_KEYWORDS)
        joins = [
            f"{x.schema}.{x.table}({', '.join(x.columns)}) -> "
            f"{x.referenced_schema}.{x.referenced_table}({', '.join(x.referenced_columns)})"
            for x in self.foreign_keys
            if {x.table, x.referenced_table} & {candidate.table for candidate in selected}
        ]
        next_tables = list(dict.fromkeys((*image_tables, *label_tables)))
        values = [
            ("사용자 스키마 수", str(len(self.schemas))),
            ("사용자 테이블 수", str(sum(x.kind == "table" for x in self.relations))),
            ("뷰 수", str(sum(x.kind == "view" for x in self.relations))),
            ("이미지 후보 테이블", _display(image_tables)),
            ("이미지 경로 후보 컬럼", _display(image_columns)),
            ("라벨·하자 후보 테이블", _display(label_tables)),
            ("라벨·분류 후보 컬럼", _display(label_columns)),
            ("이미지와 라벨을 연결할 가능성이 있는 PK/FK", _display(joins)),
            ("다음 단계에서 확인해야 할 테이블 목록", _display(next_tables)),
        ]
        return "\n".join(f"{title}\n{body}\n" for title, body in values)


def _display(values: list[str]) -> str:
    return "\n".join(f"- {value}" for value in values) if values else "- 없음"


def _matches(name: str, keywords: tuple[str, ...]) -> tuple[str, ...]:
    folded = name.casefold()
    return tuple(word for word in keywords if word.casefold() in folded)


def _candidate_columns(columns: tuple[ColumnInfo, ...], keywords: tuple[str, ...]) -> list[str]:
    return [f"{x.schema}.{x.table}.{x.name}" for x in columns if _matches(x.name, keywords)]


def _find_candidates(relations: tuple[RelationInfo, ...],
                     columns: tuple[ColumnInfo, ...]) -> tuple[CandidateInfo, ...]:
    grouped: dict[tuple[str, str], list[ColumnInfo]] = {}
    for column in columns:
        grouped.setdefault((column.schema, column.table), []).append(column)
    result = []
    for relation in relations:
        image_table = _matches(relation.name, IMAGE_KEYWORDS)
        label_table = _matches(relation.name, LABEL_KEYWORDS)
        image_columns, label_columns, reasons = [], [], []
        if image_table:
            reasons.append("table name: " + ", ".join(image_table))
        if label_table:
            reasons.append("table name: " + ", ".join(label_table))
        for column in grouped.get((relation.schema, relation.name), []):
            image_match = _matches(column.name, IMAGE_KEYWORDS)
            label_match = _matches(column.name, LABEL_KEYWORDS)
            if image_match:
                image_columns.append(column.name)
                reasons.append(f"column {column.name}: " + ", ".join(image_match))
            if label_match:
                label_columns.append(column.name)
                reasons.append(f"column {column.name}: " + ", ".join(label_match))
        categories = tuple(name for name, present in (
            ("image", image_table or image_columns),
            ("label_defect", label_table or label_columns),
        ) if present)
        if categories:
            score = 3 * (bool(image_table) + bool(label_table)) + len(image_columns) + len(label_columns)
            result.append(CandidateInfo(
                relation.schema, relation.name, score, categories, tuple(reasons),
                tuple(dict.fromkeys((*image_columns, *label_columns))),
            ))
    return tuple(sorted(result, key=lambda x: (-x.score, x.schema, x.table)))


def inspect_database(config: DatabaseConfig, *, schema: str | None = None,
                     table: str | None = None, include_views: bool = False,
                     connector: Callable[..., Any] | None = None) -> DatabaseInspection:
    connection = connect_database(config, connector=connector)
    try:
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SET TRANSACTION READ ONLY")
                    schemas = _query(cursor, _schemas_sql(schema), _parameters(schema, None))
                    args = _parameters(schema, table)
                    kinds = "('r','p','f','v','m')" if include_views else "('r','p','f')"
                    relations = _query(cursor, _relations_sql(kinds, schema, table), args)
                    columns = _query(cursor, _columns_sql(kinds, schema, table), args)
                    primary_keys = _query(cursor, _pk_sql(kinds, schema, table), args)
                    foreign_keys = _query(cursor, _fk_sql(kinds, schema, table), args)
    except Exception as exc:
        raise DatabaseQueryError("database catalog inspection failed") from exc
    relation_info = tuple(RelationInfo(str(x[0]), str(x[1]),
                          "view" if str(x[2]) in {"v", "m"} else "table",
                          max(0, int(float(x[3] or 0)))) for x in relations)
    column_info = tuple(ColumnInfo(str(x[0]), str(x[1]), str(x[2]), str(x[3]),
                                  bool(x[4]), None if x[5] is None else str(x[5]))
                        for x in columns)
    pks = tuple(PrimaryKeyInfo(str(x[0]), str(x[1]), str(x[2]),
                              tuple(str(v) for v in x[3])) for x in primary_keys)
    fks = tuple(ForeignKeyInfo(
        str(x[0]), str(x[1]), str(x[2]), tuple(str(v) for v in x[3]),
        str(x[4]), str(x[5]), tuple(str(v) for v in x[6])) for x in foreign_keys)
    return DatabaseInspection(config.host, config.database, config.user, config.sslmode,
                              tuple(str(x[0]) for x in schemas), relation_info,
                              column_info, pks, fks, _find_candidates(relation_info, column_info))


def _query(cursor: Any, sql: str, parameters: list[str]) -> tuple[Any, ...]:
    cursor.execute(sql, parameters)
    return tuple(cursor.fetchall())


def _parameters(schema: str | None, table: str | None) -> list[str]:
    return [*SYSTEM_SCHEMAS, *([schema] if schema else []), *([table] if table else [])]


def _where(schema: str | None, table: str | None, schema_col: str,
           table_col: str) -> str:
    clauses = [f"{schema_col} NOT IN (%s, %s)", f"{schema_col} NOT LIKE 'pg_toast%%'",
               f"{schema_col} NOT LIKE 'pg_temp_%%'"]
    if schema:
        clauses.append(f"{schema_col} = %s")
    if table:
        clauses.append(f"{table_col} = %s")
    return " AND ".join(clauses)


def _schemas_sql(schema: str | None) -> str:
    return ("SELECT nspname FROM pg_catalog.pg_namespace WHERE "
            + _where(schema, None, "nspname", "nspname") + " ORDER BY nspname")


def _relations_sql(kinds: str, schema: str | None, table: str | None) -> str:
    return ("SELECT n.nspname, c.relname, c.relkind, c.reltuples "
            "FROM pg_catalog.pg_class AS c JOIN pg_catalog.pg_namespace AS n "
            "ON n.oid = c.relnamespace WHERE c.relkind IN " + kinds + " AND "
            + _where(schema, table, "n.nspname", "c.relname")
            + " ORDER BY n.nspname, c.relname")


def _columns_sql(kinds: str, schema: str | None, table: str | None) -> str:
    return ("SELECT n.nspname, c.relname, a.attname, "
            "pg_catalog.format_type(a.atttypid, a.atttypmod), NOT a.attnotnull, "
            "pg_catalog.pg_get_expr(d.adbin, d.adrelid) FROM pg_catalog.pg_attribute AS a "
            "JOIN pg_catalog.pg_class AS c ON c.oid = a.attrelid "
            "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
            "LEFT JOIN pg_catalog.pg_attrdef AS d ON d.adrelid = a.attrelid "
            "AND d.adnum = a.attnum WHERE c.relkind IN " + kinds
            + " AND a.attnum > 0 AND NOT a.attisdropped AND "
            + _where(schema, table, "n.nspname", "c.relname")
            + " ORDER BY n.nspname, c.relname, a.attnum")


def _pk_sql(kinds: str, schema: str | None, table: str | None) -> str:
    return ("SELECT n.nspname, c.relname, con.conname, "
            "array_agg(a.attname ORDER BY k.ord) FROM pg_catalog.pg_constraint AS con "
            "JOIN pg_catalog.pg_class AS c ON c.oid = con.conrelid "
            "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
            "JOIN unnest(con.conkey) WITH ORDINALITY AS k(attnum, ord) ON true "
            "JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid AND a.attnum = k.attnum "
            "WHERE con.contype = 'p' AND c.relkind IN " + kinds + " AND "
            + _where(schema, table, "n.nspname", "c.relname")
            + " GROUP BY n.nspname, c.relname, con.conname "
            "ORDER BY n.nspname, c.relname, con.conname")


def _fk_sql(kinds: str, schema: str | None, table: str | None) -> str:
    return ("SELECT n.nspname, c.relname, con.conname, "
            "array_agg(a.attname ORDER BY k.ord), rn.nspname, rc.relname, "
            "array_agg(ra.attname ORDER BY k.ord) FROM pg_catalog.pg_constraint AS con "
            "JOIN pg_catalog.pg_class AS c ON c.oid = con.conrelid "
            "JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace "
            "JOIN pg_catalog.pg_class AS rc ON rc.oid = con.confrelid "
            "JOIN pg_catalog.pg_namespace AS rn ON rn.oid = rc.relnamespace "
            "JOIN unnest(con.conkey, con.confkey) WITH ORDINALITY "
            "AS k(attnum, ref_attnum, ord) ON true "
            "JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid AND a.attnum = k.attnum "
            "JOIN pg_catalog.pg_attribute AS ra ON ra.attrelid = rc.oid "
            "AND ra.attnum = k.ref_attnum WHERE con.contype = 'f' AND c.relkind IN "
            + kinds + " AND " + _where(schema, table, "n.nspname", "c.relname")
            + " GROUP BY n.nspname, c.relname, con.conname, rn.nspname, rc.relname "
            "ORDER BY n.nspname, c.relname, con.conname")
