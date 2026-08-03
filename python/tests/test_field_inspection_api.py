import uuid

import pytest

from data_engineering.database.config import DatabaseConfig
from vision_ai.field_inspection import FieldServiceError, MemoryFieldInspectionService


def _session(service):
    return service.create_session(
        {
            "client_uuid": str(uuid.uuid4()),
            "household_id": str(uuid.uuid4()),
            "inspector_id": "점검매니저",
            "building_no": "101",
            "unit_no": "1501",
        },
        idempotency_key="create-field-session-0001",
    )


def _defect_payload(session_id):
    return {
        "client_uuid": str(uuid.uuid4()),
        "session_id": session_id,
        "defect_index": 1,
        "room_label": "거실",
        "x_norm": 0.4,
        "y_norm": 0.6,
        "taxonomy_version": "2.0.0",
        "raw_resident_opinion": "벽에 금이 갔어요",
        "standardized_opinion": "거실 벽면 균열",
        "final_classification": {
            "location_code": "LIVING",
            "part_code": "WALL",
            "part_detail_code": "WALL_CENTER",
            "work_kind_code": "FINISH",
        },
    }


def test_household_session_defect_confirmation_and_completion_flow():
    service = MemoryFieldInspectionService()
    session = _session(service)
    anchored = service.set_anchor(
        session["id"],
        {"room_label": "거실", "x_norm": 0.4, "y_norm": 0.6, "heading_deg": 90},
        expected_revision=1,
    )
    defect = service.upsert_defect(
        _defect_payload(session["id"]),
        idempotency_key="upsert-field-defect-0001",
        expected_revision=0,
    )
    confirmed = service.confirm_defect(
        defect["id"],
        {"final_classification": defect["final_classification"], "standardized_opinion": "거실 벽면 균열"},
        expected_revision=1,
    )
    summary = service.session_summary(session["id"])
    completed = service.complete_session(session["id"], expected_revision=anchored["revision"])

    assert confirmed["state"] == "USER_CONFIRMED"
    assert summary["counts"] == {
        "total": 1, "pending": 0, "confirmed": 1, "review_required": 0
    }
    assert completed["session"]["state"] == "COMPLETED"
    assert completed["next_unit_no"] == "1502"


def test_media_keeps_metadata_without_binary_or_credentials():
    service = MemoryFieldInspectionService()
    session = _session(service)
    defect = service.upsert_defect(
        _defect_payload(session["id"]),
        idempotency_key="upsert-field-defect-0002",
        expected_revision=0,
    )
    media = service.register_media(
        defect["id"],
        {
            "client_uuid": str(uuid.uuid4()),
            "role": "close",
            "mime_type": "image/jpeg",
            "size_bytes": 1234,
            "sha256": "a" * 64,
            "object_key": "field/101/1501/image.jpg",
            "metadata": {"heading_deg": 90, "distance_m": 1.2},
        },
        idempotency_key="register-field-media-0001",
    )
    text = repr(media).lower()
    assert media["role"] == "CLOSE"
    assert "password" not in text
    assert "dsn" not in text


def test_revision_conflict_and_idempotency():
    service = MemoryFieldInspectionService()
    session = _session(service)
    payload = _defect_payload(session["id"])
    first = service.upsert_defect(
        payload, idempotency_key="upsert-field-defect-0003", expected_revision=0
    )
    repeated = service.upsert_defect(
        payload, idempotency_key="upsert-field-defect-0003", expected_revision=999
    )
    assert repeated == first
    with pytest.raises(FieldServiceError, match="revision"):
        service.confirm_defect(
            first["id"], {"final_classification": {}}, expected_revision=999
        )


def test_session_can_be_resolved_by_the_stable_client_uuid():
    service = MemoryFieldInspectionService()
    session = _session(service)
    assert service.resolve_session(session["client_uuid"])["id"] == session["id"]


def test_offline_sync_reports_applied_and_rejected_operations():
    service = MemoryFieldInspectionService()
    session = _session(service)
    result = service.sync_batch(
        {
            "device_id": "S25-ULTRA",
            "operations": [
                {
                    "operation_id": "offline-upsert-operation-0001",
                    "kind": "UPSERT_DEFECT",
                    "base_revision": 0,
                    "payload": _defect_payload(session["id"]),
                },
                {
                    "operation_id": "offline-invalid-operation-0002",
                    "kind": "DROP_DATABASE",
                    "payload": {},
                },
            ],
        }
    )
    assert [item["state"] for item in result["results"]] == ["APPLIED", "REJECTED"]


def test_fastapi_exposes_field_operation_routes():
    pytest.importorskip("fastapi")
    from vision_ai.inspection_dev_app import create_inspection_dev_app

    app = create_inspection_dev_app(field_service=MemoryFieldInspectionService())
    paths = {route.path for route in app.routes}
    assert {
        "/v2/field/households/resolve",
        "/v2/field/taxonomy",
        "/v2/field/inspections/manager-stats",
        "/v2/field/gallery/households",
        "/v2/field/gallery/households/{building_no}/{unit_no}",
        "/v2/field/media/{media_id}/content",
        "/v2/field/sessions",
        "/v2/field/sessions/resolve",
        "/v2/field/sessions/{session_id}/anchor",
        "/v2/field/sessions/{session_id}/summary",
        "/v2/field/sessions/{session_id}/complete",
        "/v2/field/defects",
        "/v2/field/defects/{defect_id}/confirmation",
        "/v2/field/defects/{defect_id}/media",
        "/v2/field/sync/batches",
    } <= paths




def test_memory_taxonomy_catalog_limits_suggestions_to_five() -> None:
    result = MemoryFieldInspectionService().taxonomy_catalog(
        "84A-1", room_code="living", surface_code="WALL"
    )
    assert result["source"] == "fallback"
    assert [room["code"] for room in result["rooms"]][:2] == ["entry", "living"]
    assert len(result["details"]) <= 5
    assert result["details"]
    assert result["causes"]
def test_memory_field_service_healthcheck_is_ready():
    assert MemoryFieldInspectionService().healthcheck() is True


def test_manager_statistics_starts_at_zero():
    result = MemoryFieldInspectionService().manager_statistics("점검매니저")
    assert result["total_households"] == 0
    assert result["today_households"] == 0
    assert result["start_date"] is None


def test_memory_gallery_groups_media_by_household():
    service = MemoryFieldInspectionService()
    session_id = "session-1"
    service.sessions[session_id] = {
        "id": session_id, "household_id": "household-1", "building_no": "101",
        "unit_no": "1404", "created_on": "2026-07-27", "state": "COMPLETED",
    }
    service.defects["defect-1"] = {
        "id": "defect-1", "session_id": session_id, "defect_index": 1,
        "room_label": "안방", "raw_resident_opinion": "천장 균열",
        "standardized_opinion": "안방 천장 균열",
    }
    service.media["media-1"] = {
        "id": "media-1", "defect_id": "defect-1", "role": "CLOSE",
        "mime_type": "image/jpeg", "object_key": "field-media/example.jpg",
    }

    assert service.gallery_households()[0]["building_no"] == "101"
    detail = service.gallery_for_household("101", "1404")
    assert detail["defects"][0]["room_label"] == "안방"
    assert detail["defects"][0]["media"][0]["id"] == "media-1"


def test_memory_common_area_session_keeps_location_metadata():
    service = MemoryFieldInspectionService()
    session = service.create_session(
        {
            "client_uuid": str(uuid.uuid4()),
            "household_id": str(uuid.uuid4()),
            "inspector_id": "Master",
            "building_no": "COMMON",
            "unit_no": "0000",
            "inspection_kind": "COMMON_AREA",
            "common_area_label": "106동 지하주차장",
        },
        idempotency_key="common-area-session",
    )
    assert session["inspection_kind"] == "COMMON_AREA"
    assert session["common_area_label"] == "106동 지하주차장"


def test_memory_common_gallery_returns_location_label():
    service = MemoryFieldInspectionService()
    service.sessions["common-session"] = {
        "id": "common-session", "household_id": "common-household",
        "building_no": "COMMON", "unit_no": "0000", "state": "COMPLETED",
        "common_area_label": "기타 하자",
    }
    service.defects["common-defect"] = {
        "id": "common-defect", "session_id": "common-session", "defect_index": 1,
        "room_label": "기타 하자", "raw_resident_opinion": "기타 위치 하자",
        "standardized_opinion": "기타 위치 하자",
    }

    detail = service.gallery_for_household("COMMON", "0000")
    assert detail["defects"][0]["common_area_label"] == "기타 하자"


def test_household_resolution_returns_floorplan_and_manager():
    result = MemoryFieldInspectionService().resolve_household("101", "1501")
    assert result["building_no"] == "101"
    assert result["unit_no"] == "1501"
    assert result["floorplan_type"] == "84A-1"
    assert result["owner_display_name"] == "점검매니저"


def test_local_database_prefix_is_isolated_from_rds_settings():
    config = DatabaseConfig.from_prefixed_environment(
        "LOCAL_APARTMENT_DB_",
        {
            "LOCAL_APARTMENT_DB_HOST": "127.0.0.1",
            "LOCAL_APARTMENT_DB_NAME": "apartment_defect_local",
            "LOCAL_APARTMENT_DB_USER": "postgres",
            "LOCAL_APARTMENT_DB_PASSWORD": "local-secret",
            "LOCAL_APARTMENT_DB_SSLMODE": "disable",
            "APARTMENT_DB_HOST": "production.example",
        },
    )
    assert config.host == "127.0.0.1"
    assert config.database == "apartment_defect_local"
    assert config.sslmode == "disable"
    assert "local-secret" not in repr(config)


def test_local_seed_is_idempotent_and_contains_ulsan_households():
    from pathlib import Path

    seed = (
        Path(__file__).resolve().parents[2]
        / "database"
        / "seeds"
        / "001_ulsan_down_local.sql"
    ).read_text(encoding="utf-8")
    assert "ON CONFLICT" in seed
    assert "generate_series(101, 120)" in seed
    assert "generate_series(1, 25)" in seed
    assert "점검매니저" in seed
    assert "COMMIT;" in seed
