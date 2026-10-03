"""Read-only extraction of the curated defect/image training source."""

from __future__ import annotations

from typing import Any, Callable

from .config import DatabaseConfig
from .connection import DatabaseQueryError, connect_database

_COLUMNS = (
    "defect_id",
    "photo_url",
    "original_full_path",
    "site_code",
    "site_name",
    "dong",
    "ho",
    "area",
    "part",
    "part_detail",
    "cause",
    "work_kind",
    "received_at",
    "defect_description",
)

_DATASET_SQL = """
SELECT
  defect_id,
  photo_url,
  original_full_path,
  site_code,
  "단지명" AS site_name,
  "동" AS dong,
  "호" AS ho,
  "실" AS area,
  "부위" AS part,
  "상세부위" AS part_detail,
  "하자원인" AS cause,
  "공종" AS work_kind,
  "접수일시" AS received_at,
  "고객민원내용" AS defect_description
FROM public.woohaja_defect_photo_tagged
WHERE defect_id IS NOT NULL
  AND COALESCE(NULLIF(photo_url, ''), NULLIF(original_full_path, '')) IS NOT NULL
  AND COALESCE(
    NULLIF("실", ''), NULLIF("부위", ''), NULLIF("상세부위", ''),
    NULLIF("하자원인", ''), NULLIF("공종", '')
  ) IS NOT NULL
ORDER BY defect_id, id
"""


def extract_defect_dataset_rows(
    config: DatabaseConfig,
    *,
    limit: int | None = None,
    connector: Callable[..., Any] | None = None,
) -> tuple[dict[str, Any], ...]:
    """Extract curated metadata; never reads image bytes or modifies the database."""
    if limit is not None and limit < 1:
        raise ValueError("limit must be positive")
    connection = connect_database(config, connector=connector)
    try:
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SET TRANSACTION READ ONLY")
                    sql = _DATASET_SQL + ("LIMIT %s" if limit is not None else "")
                    cursor.execute(sql, [limit] if limit is not None else None)
                    rows = cursor.fetchall()
    except Exception as exc:
        raise DatabaseQueryError("defect dataset extraction failed") from exc
    return tuple(
        {column: value for column, value in zip(_COLUMNS, row)}
        for row in rows
    )
