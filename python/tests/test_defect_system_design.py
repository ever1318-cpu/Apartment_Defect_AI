from __future__ import annotations

import pytest

from data_engineering.database.config import DatabaseConfig
from data_engineering.database.dataset import extract_defect_dataset_rows
from vision_ai.defect_dataset import defect_rows_to_dataset
from vision_ai.defect_metadata import (
    ConfidencePolicy,
    DefectTaxonomy,
    build_defect_metadata,
)
from vision_ai.models import Classification, ImageQuality, VisionPrediction


def _prediction(**tasks) -> VisionPrediction:
    return VisionPrediction(
        image_id="image-1",
        model_version="defect-v1",
        quality=ImageQuality(0.95, True),
        classifications={
            task: tuple(Classification(label, confidence) for label, confidence in values)
            for task, values in tasks.items()
        },
    )


def test_defect_rows_create_grouped_hierarchical_dataset() -> None:
    items = defect_rows_to_dataset(
        [
            {
                "defect_id": 42,
                "photo_url": "s3://defects/a.jpg",
                "실": "욕실",
                "부위": "벽체",
                "상세부위": "벽타일",
                "공종": "타일공사",
                "하자원인": "접착불량",
                "고객민원내용": "타일이 들뜸",
            },
            {
                "defect_id": 42,
                "photo_url": "s3://defects/b.jpg",
                "실": "욕실",
                "부위": "벽체",
            },
        ],
        dataset_version="2026.07",
    )
    assert len(items) == 2
    assert {item.record.group_id for item in items} == {"defect:42"}
    assert items[0].annotation.classifications["part_detail"] == "벽타일"
    assert items[0].record.metadata["고객민원내용"] == "타일이 들뜸"


def test_dataset_rejects_missing_image_or_labels() -> None:
    with pytest.raises(ValueError, match="image path"):
        defect_rows_to_dataset([{"defect_id": 1, "부위": "벽체"}], dataset_version="v1")
    with pytest.raises(ValueError, match="hierarchical"):
        defect_rows_to_dataset(
            [{"defect_id": 1, "full_path": "a.jpg"}], dataset_version="v1"
        )


def test_metadata_applies_confidence_and_hierarchy_review_policy() -> None:
    prediction = _prediction(
        area=(("욕실", 0.96),),
        part=(("벽체", 0.92),),
        part_detail=(("창틀", 0.82), ("벽타일", 0.78)),
        work_kind=(("타일공사", 0.65),),
    )
    metadata = build_defect_metadata(
        prediction,
        context={"site_id": 7},
        taxonomy=DefectTaxonomy({"part:벽체": ("벽타일",)}),
    )
    assert metadata.fields["area"].status == "auto_accepted"
    assert metadata.fields["part_detail"].status == "confirmation_required"
    assert metadata.fields["work_kind"].status == "suggested"
    assert not metadata.hierarchy_valid
    assert metadata.review_required
    assert metadata.context["site_id"] == 7


def test_task_specific_threshold_can_prevent_auto_accept() -> None:
    metadata = build_defect_metadata(
        _prediction(cause=(("접착불량", 0.93),)),
        policy=ConfidencePolicy(task_thresholds={"cause": 0.97}),
    )
    assert metadata.fields["cause"].status == "confirmation_required"
    assert metadata.review_required


class _DatasetCursor:
    def __init__(self) -> None:
        self.executions = []

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def execute(self, sql, parameters=None):
        self.executions.append((str(sql), parameters))

    def fetchall(self):
        return [
            (
                42, "s3://bucket/a.jpg", None, "S1", "단지", "101", "1203",
                "욕실", "벽체", "벽타일", "접착불량", "타일공사",
                "2026-01-01", "타일 들뜸",
            )
        ]


class _DatasetTransaction:
    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class _DatasetConnection:
    def __init__(self) -> None:
        self.cursor_value = _DatasetCursor()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def transaction(self):
        return _DatasetTransaction()

    def cursor(self):
        return self.cursor_value


def test_db_dataset_extraction_is_read_only_and_parameterizes_limit() -> None:
    connection = _DatasetConnection()
    config = DatabaseConfig(
        host="example.invalid",
        database="db",
        user="reader",
        password="secret",
    )
    rows = extract_defect_dataset_rows(
        config, limit=10, connector=lambda **kwargs: connection
    )
    assert rows[0]["part_detail"] == "벽타일"
    assert connection.cursor_value.executions[0][0] == "SET TRANSACTION READ ONLY"
    sql, parameters = connection.cursor_value.executions[1]
    assert sql.lstrip().upper().startswith("SELECT")
    assert "COUNT(" not in sql.upper()
    assert parameters == [10]
