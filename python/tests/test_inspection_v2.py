import hashlib
import uuid

import pytest

from vision_ai.inspection_v2 import (
    MAX_ASSISTANT_TURNS,
    FakeInspectionService,
    InspectionContractError,
)


def _draft(*, opinion="벽면이 젖어 있고 물이 번지는 것 같습니다"):
    return {
        "client_uuid": str(uuid.uuid4()),
        "taxonomy_version": "2.0.0",
        "site": {"site_id": "site-1"},
        "location": {
            "building": "101",
            "unit": "1001",
            "area": "거실",
            "wall_direction": "N",
        },
        "capture_pair": {
            "wide_media_id": str(uuid.uuid4()),
            "close_media_id": str(uuid.uuid4()),
        },
        "raw_opinion": opinion,
    }


def _key(name):
    return f"test-idempotency-{name}-0001"


def test_full_fake_flow_reaches_user_confirmation() -> None:
    service = FakeInspectionService()
    inspection = service.create_inspection(
        _draft(), idempotency_key=_key("create")
    )
    analysis = service.analyze(
        inspection["id"],
        {
            "model_name": "apartment-defect-convnext",
            "model_version": "2.0.0",
            "top_k": 3,
        },
        idempotency_key=_key("analysis"),
    )
    assert analysis["status"] == "COMPLETED"
    assert analysis["image_quality"] is None
    assert analysis["quality_policy"] == "separate-validator-required"
    assert len(analysis["candidates"]) == 3

    session = service.create_assistant_session(
        {"inspection_id": inspection["id"], "analysis_id": analysis["id"]},
        idempotency_key=_key("session"),
    )
    assert session["state"] == "NEEDS_CLARIFICATION"
    assert session["question"]["id"] == "moisture"

    proposed = service.answer(
        session["id"],
        {"question_id": "moisture", "option_id": "wet"},
        idempotency_key=_key("answer"),
    )
    assert proposed["state"] == "PROPOSED"
    assert proposed["proposal"]["suspected_cause_code"] == "CAUSE_LEAK"

    revision = service.get_inspection(inspection["id"])["revision"]
    confirmed = service.confirm(
        inspection["id"],
        {
            "classification": proposed["proposal"],
            "confirmation_source": "accepted",
        },
        idempotency_key=_key("confirm"),
        expected_revision=revision,
    )
    assert confirmed["state"] == "USER_CONFIRMED"
    assert confirmed["classification"]["priority_code"] == "P2"


def test_idempotency_returns_same_resource_without_duplicate() -> None:
    service = FakeInspectionService()
    payload = _draft()
    first = service.create_inspection(payload, idempotency_key=_key("same"))
    second = service.create_inspection(payload, idempotency_key=_key("same"))
    assert first == second
    assert len(service.inspections) == 1


def test_revision_conflict_prevents_silent_overwrite() -> None:
    service = FakeInspectionService()
    inspection = service.create_inspection(
        _draft(opinion="일반 하자"), idempotency_key=_key("create-conflict")
    )
    analysis = service.analyze(
        inspection["id"], {}, idempotency_key=_key("analyze-conflict")
    )
    session = service.create_assistant_session(
        {"inspection_id": inspection["id"], "analysis_id": analysis["id"]},
        idempotency_key=_key("session-conflict"),
    )
    current = session
    while current["state"] == "NEEDS_CLARIFICATION":
        question = current["question"]
        current = service.answer(
            session["id"],
            {"question_id": question["id"], "option_id": "unknown"},
            idempotency_key=_key(f"answer-{current['turn_count']}"),
        )
    assert current["turn_count"] == MAX_ASSISTANT_TURNS
    with pytest.raises(InspectionContractError, match="revision changed") as error:
        service.confirm(
            inspection["id"],
            {
                "classification": current["proposal"],
                "confirmation_source": "accepted",
            },
            idempotency_key=_key("stale-confirm"),
            expected_revision=1,
        )
    assert error.value.status_code == 409


def test_p1_and_missing_detail_require_review() -> None:
    service = FakeInspectionService()
    inspection = service.create_inspection(
        _draft(opinion="일반 하자"), idempotency_key=_key("create-p1")
    )
    analysis = service.analyze(
        inspection["id"], {}, idempotency_key=_key("analyze-p1")
    )
    session = service.create_assistant_session(
        {"inspection_id": inspection["id"], "analysis_id": analysis["id"]},
        idempotency_key=_key("session-p1"),
    )
    while session["state"] == "NEEDS_CLARIFICATION":
        session = service.answer(
            session["id"],
            {
                "question_id": session["question"]["id"],
                "option_id": "unknown",
            },
            idempotency_key=_key(f"p1-answer-{session['turn_count']}"),
        )
    proposal = dict(session["proposal"])
    proposal["priority_code"] = "P1"
    proposal["part_detail_code"] = ""
    revision = service.get_inspection(inspection["id"])["revision"]
    confirmed = service.confirm(
        inspection["id"],
        {"classification": proposal, "confirmation_source": "corrected"},
        idempotency_key=_key("p1-confirm"),
        expected_revision=revision,
    )
    assert confirmed["state"] == "REVIEW_REQUIRED"
    assert confirmed["review_required"] is True


def test_invalid_taxonomy_and_short_idempotency_are_rejected() -> None:
    service = FakeInspectionService()
    payload = _draft()
    payload["taxonomy_version"] = "1.0.0"
    with pytest.raises(InspectionContractError) as mismatch:
        service.create_inspection(payload, idempotency_key=_key("wrong-taxonomy"))
    assert mismatch.value.code == "TAXONOMY_VERSION_MISMATCH"
    with pytest.raises(InspectionContractError) as short:
        service.create_inspection(_draft(), idempotency_key="short")
    assert short.value.code == "INVALID_IDEMPOTENCY_KEY"


def test_media_upload_session_validates_digest_before_inspection() -> None:
    service = FakeInspectionService()
    wide = b"wide-image"
    close = b"close-image"
    session = service.create_upload_session(
        {
            "client_uuid": str(uuid.uuid4()),
            "files": [
                {
                    "slot": "wide",
                    "mime_type": "image/jpeg",
                    "size_bytes": len(wide),
                    "sha256": hashlib.sha256(wide).hexdigest(),
                },
                {
                    "slot": "close",
                    "mime_type": "image/jpeg",
                    "size_bytes": len(close),
                    "sha256": hashlib.sha256(close).hexdigest(),
                },
            ],
        },
        idempotency_key=_key("upload-session"),
    )
    wide_id = session["uploads"][0]["media_id"]
    close_id = session["uploads"][1]["media_id"]
    service.accept_upload(wide_id, wide, content_type="image/jpeg")
    with pytest.raises(InspectionContractError) as digest_error:
        service.accept_upload(close_id, b"wrong-image", content_type="image/jpeg")
    assert digest_error.value.code == "SHA256_MISMATCH"

    draft = _draft()
    draft["capture_pair"] = {
        "wide_media_id": wide_id,
        "close_media_id": close_id,
    }
    with pytest.raises(InspectionContractError) as incomplete:
        service.create_inspection(
            draft, idempotency_key=_key("incomplete-media")
        )
    assert incomplete.value.code == "MEDIA_NOT_UPLOADED"

    service.accept_upload(close_id, close, content_type="image/jpeg")
    created = service.create_inspection(
        draft, idempotency_key=_key("complete-media")
    )
    assert created["capture_pair"]["wide_media_id"] == wide_id


def test_uploaded_media_is_persisted_to_the_configured_local_store(tmp_path) -> None:
    service = FakeInspectionService(upload_root=tmp_path)
    content = b"field-photo"
    session = service.create_upload_session(
        {
            "client_uuid": str(uuid.uuid4()),
            "files": [{
                "slot": "extra",
                "mime_type": "image/jpeg",
                "size_bytes": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }],
        },
        idempotency_key=_key("local-media-store"),
    )
    media_id = session["uploads"][0]["media_id"]
    result = service.accept_upload(media_id, content, content_type="image/jpeg")

    assert result["object_key"] == f"field-media/{media_id}"
    assert (tmp_path / f"{media_id}.jpg").read_bytes() == content


def test_uploaded_media_keeps_safe_client_inspection_filename(tmp_path) -> None:
    service = FakeInspectionService(upload_root=tmp_path)
    content = b"field-photo"
    filename = "101동1404호_안방_천정_0727-18-27-55_D001_CLOSE_P00111.jpg"
    session = service.create_upload_session(
        {
            "client_uuid": str(uuid.uuid4()),
            "files": [{
                "slot": "close",
                "file_name": filename,
                "mime_type": "image/jpeg",
                "size_bytes": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }],
        },
        idempotency_key=_key("named-local-media-store"),
    )
    media_id = session["uploads"][0]["media_id"]
    result = service.accept_upload(media_id, content, content_type="image/jpeg")

    assert result["object_key"] == f"field-media/{filename}"
    assert (tmp_path / filename).read_bytes() == content


def test_original_opinion_is_preserved_and_outputs_are_copies() -> None:
    service = FakeInspectionService()
    payload = _draft(opinion="입주자가 작성한 원문")
    created = service.create_inspection(payload, idempotency_key=_key("opinion"))
    created["raw_opinion"] = "외부에서 변경"
    stored = service.get_inspection(created["id"])
    assert stored["raw_opinion"] == "입주자가 작성한 원문"
