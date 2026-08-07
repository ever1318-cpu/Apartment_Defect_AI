"""Contract-first fake inspection and assistant workflow for API v2 development."""

from __future__ import annotations

import copy
import re
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping

TAXONOMY_VERSION = "2.0.0"
MODEL_NAME = "apartment-defect-convnext"
MODEL_VERSION = "2.0.0"
MAX_ASSISTANT_TURNS = 3


class InspectionContractError(ValueError):
    def __init__(self, code: str, message: str, *, status_code: int = 400):
        super().__init__(message)
        self.code = code
        self.status_code = status_code


@dataclass(slots=True)
class FakeInspectionService:
    """Deterministic in-memory adapter used before real AI and persistence."""

    inspections: dict[str, dict[str, Any]] = field(default_factory=dict)
    analyses: dict[str, dict[str, Any]] = field(default_factory=dict)
    sessions: dict[str, dict[str, Any]] = field(default_factory=dict)
    media_uploads: dict[str, dict[str, Any]] = field(default_factory=dict)
    upload_root: Path | None = None
    idempotency: dict[tuple[str, str], dict[str, Any]] = field(
        default_factory=dict
    )

    def create_upload_session(
        self, payload: Mapping[str, Any], *, idempotency_key: str
    ) -> dict[str, Any]:
        cached = self._cached("upload_session", idempotency_key)
        if cached is not None:
            return cached
        _require_fields(payload, "client_uuid", "files")
        _uuid_text(payload["client_uuid"], "client_uuid")
        files = payload["files"]
        if not isinstance(files, list) or not 1 <= len(files) <= 4:
            raise InspectionContractError(
                "INVALID_FILES", "files must contain 1..4 items"
            )
        uploads = []
        for index, item in enumerate(files):
            _require_fields(item, "slot", "mime_type", "size_bytes", "sha256")
            if item["slot"] not in {"wide", "close", "extra"}:
                raise InspectionContractError("INVALID_SLOT", "invalid media slot")
            if int(item["size_bytes"]) < 1 or int(item["size_bytes"]) > 20_971_520:
                raise InspectionContractError("INVALID_SIZE", "invalid media size")
            sha256 = str(item["sha256"])
            if len(sha256) != 64 or any(ch not in "0123456789abcdef" for ch in sha256):
                raise InspectionContractError("INVALID_SHA256", "invalid sha256")
            media_id = str(uuid.uuid4())
            self.media_uploads[media_id] = {
                "metadata": copy.deepcopy(dict(item)),
                "uploaded": False,
                "size_bytes": None,
            }
            uploads.append(
                {
                    "media_id": media_id,
                    "slot": item["slot"],
                    "method": "PUT",
                    "upload_url": f"/v2/media/uploads/{media_id}",
                    "headers": {"Content-Type": item["mime_type"]},
                    "order": index,
                }
            )
        result = {
            "id": str(uuid.uuid4()),
            "expires_at": "2099-01-01T00:00:00Z",
            "uploads": uploads,
        }
        self._remember("upload_session", idempotency_key, result)
        return result

    def accept_upload(
        self, media_id: str, content: bytes, *, content_type: str
    ) -> dict[str, Any]:
        try:
            record = self.media_uploads[media_id]
        except KeyError:
            raise InspectionContractError(
                "MEDIA_NOT_FOUND", "media upload not found", status_code=404
            ) from None
        expected = record["metadata"]
        if content_type.split(";", 1)[0].strip() != expected["mime_type"]:
            raise InspectionContractError("MIME_MISMATCH", "content type mismatch")
        if len(content) != int(expected["size_bytes"]):
            raise InspectionContractError("SIZE_MISMATCH", "content size mismatch")
        import hashlib

        if hashlib.sha256(content).hexdigest() != expected["sha256"]:
            raise InspectionContractError("SHA256_MISMATCH", "content digest mismatch")
        client_name = expected.get("file_name")
        safe_name = _safe_upload_name(client_name, media_id, expected["mime_type"])
        # Preserve the established UUID object-key contract for older app
        # builds. New builds provide file_name and receive that readable name.
        object_key = f"field-media/{safe_name if client_name else media_id}"
        if self.upload_root is not None:
            destination = self.upload_root / safe_name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(content)
            record["stored_path"] = str(destination)
        record["object_key"] = object_key
        record["uploaded"] = True
        record["size_bytes"] = len(content)
        return {"media_id": media_id, "object_key": object_key, "status": "UPLOADED"}

    def taxonomy(self, version: str) -> dict[str, Any]:
        if version != TAXONOMY_VERSION:
            raise InspectionContractError(
                "TAXONOMY_NOT_FOUND",
                f"taxonomy {version!r} is unavailable",
                status_code=404,
            )
        return {
            "version": TAXONOMY_VERSION,
            "status": "draft",
            "rule_version": "draft-1",
            "axes": {
                "model": ["area", "part", "part_detail", "work_kind", "cause"],
                "product": [
                    "location",
                    "part",
                    "part_detail",
                    "symptom",
                    "work_kind",
                    "priority",
                    "suspected_cause",
                ],
            },
        }

    def create_inspection(
        self, payload: Mapping[str, Any], *, idempotency_key: str
    ) -> dict[str, Any]:
        cached = self._cached("create_inspection", idempotency_key)
        if cached is not None:
            return cached
        _require_fields(
            payload,
            "client_uuid",
            "taxonomy_version",
            "site",
            "location",
            "capture_pair",
            "raw_opinion",
        )
        if payload["taxonomy_version"] != TAXONOMY_VERSION:
            raise InspectionContractError(
                "TAXONOMY_VERSION_MISMATCH",
                f"taxonomy_version must be {TAXONOMY_VERSION}",
                status_code=409,
            )
        capture_pair = payload["capture_pair"]
        _require_fields(capture_pair, "wide_media_id", "close_media_id")
        for media_id in (
            str(capture_pair["wide_media_id"]),
            str(capture_pair["close_media_id"]),
        ):
            record = self.media_uploads.get(media_id)
            if record is not None and not record["uploaded"]:
                raise InspectionContractError(
                    "MEDIA_NOT_UPLOADED",
                    "capture media upload is incomplete",
                    status_code=409,
                )
        client_uuid = _uuid_text(payload["client_uuid"], "client_uuid")
        for value in self.inspections.values():
            if value["client_uuid"] == client_uuid:
                result = copy.deepcopy(value)
                self._remember("create_inspection", idempotency_key, result)
                return result
        identifier = str(uuid.uuid4())
        inspection = {
            "id": identifier,
            "client_uuid": client_uuid,
            "state": "CAPTURED",
            "revision": 1,
            "taxonomy_version": TAXONOMY_VERSION,
            "site": copy.deepcopy(payload["site"]),
            "location": copy.deepcopy(payload["location"]),
            "capture_pair": copy.deepcopy(payload["capture_pair"]),
            "raw_opinion": str(payload["raw_opinion"]),
            "classification": None,
            "review_required": False,
        }
        self.inspections[identifier] = inspection
        result = copy.deepcopy(inspection)
        self._remember("create_inspection", idempotency_key, result)
        return result

    def get_inspection(self, inspection_id: str) -> dict[str, Any]:
        return copy.deepcopy(self._inspection(inspection_id))

    def analyze(
        self,
        inspection_id: str,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        cached = self._cached(f"analyze:{inspection_id}", idempotency_key)
        if cached is not None:
            return cached
        inspection = self._inspection(inspection_id)
        if inspection["state"] not in {"CAPTURED", "ANALYSIS_PENDING"}:
            raise InspectionContractError(
                "INVALID_STATE",
                "analysis can only start from CAPTURED",
                status_code=409,
            )
        if payload.get("model_name", MODEL_NAME) != MODEL_NAME:
            raise InspectionContractError("MODEL_NOT_FOUND", "unknown model")
        if payload.get("model_version", MODEL_VERSION) != MODEL_VERSION:
            raise InspectionContractError("MODEL_NOT_FOUND", "unknown model version")
        top_k = int(payload.get("top_k", 3))
        if top_k < 1 or top_k > 5:
            raise InspectionContractError("INVALID_TOP_K", "top_k must be 1..5")
        analysis_id = str(uuid.uuid4())
        candidates = _fake_candidates(inspection["raw_opinion"], top_k)
        top = max(item["confidence"] for item in candidates)
        runner_up = sorted(
            (item["confidence"] for item in candidates), reverse=True
        )[1]
        needs_question = top < 0.85 or top - runner_up < 0.20
        analysis = {
            "id": analysis_id,
            "inspection_id": inspection_id,
            "status": "COMPLETED",
            "model_name": MODEL_NAME,
            "model_version": MODEL_VERSION,
            "preprocessing_version": "1.0",
            "taxonomy_version": TAXONOMY_VERSION,
            "image_quality": None,
            "quality_policy": "separate-validator-required",
            "contamination_flags": [],
            "candidates": candidates,
            "review_required": needs_question,
        }
        self.analyses[analysis_id] = analysis
        inspection["state"] = (
            "NEEDS_CLARIFICATION" if needs_question else "PROPOSED"
        )
        inspection["revision"] += 1
        result = copy.deepcopy(analysis)
        self._remember(f"analyze:{inspection_id}", idempotency_key, result)
        return result

    def get_analysis(self, analysis_id: str) -> dict[str, Any]:
        try:
            return copy.deepcopy(self.analyses[analysis_id])
        except KeyError:
            raise InspectionContractError(
                "ANALYSIS_NOT_FOUND", "analysis not found", status_code=404
            ) from None

    def create_assistant_session(
        self, payload: Mapping[str, Any], *, idempotency_key: str
    ) -> dict[str, Any]:
        cached = self._cached("assistant_session", idempotency_key)
        if cached is not None:
            return cached
        _require_fields(payload, "inspection_id", "analysis_id")
        inspection = self._inspection(str(payload["inspection_id"]))
        analysis = self.get_analysis(str(payload["analysis_id"]))
        if analysis["inspection_id"] != inspection["id"]:
            raise InspectionContractError(
                "ANALYSIS_INSPECTION_MISMATCH",
                "analysis does not belong to inspection",
                status_code=409,
            )
        session_id = str(uuid.uuid4())
        needs_question = inspection["state"] == "NEEDS_CLARIFICATION"
        session = {
            "id": session_id,
            "inspection_id": inspection["id"],
            "analysis_id": analysis["id"],
            "state": "NEEDS_CLARIFICATION" if needs_question else "PROPOSED",
            "turn_count": 0,
            "max_turns": MAX_ASSISTANT_TURNS,
            "question": _question(0) if needs_question else None,
            "proposal": None if needs_question else _proposal(analysis, ()),
            "answers": [],
        }
        self.sessions[session_id] = session
        result = _public_session(session)
        self._remember("assistant_session", idempotency_key, result)
        return result

    def answer(
        self,
        session_id: str,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        cached = self._cached(f"answer:{session_id}", idempotency_key)
        if cached is not None:
            return cached
        session = self._session(session_id)
        if session["state"] != "NEEDS_CLARIFICATION":
            raise InspectionContractError(
                "INVALID_STATE", "assistant is not awaiting an answer", status_code=409
            )
        _require_fields(payload, "question_id")
        question = session["question"]
        if payload["question_id"] != question["id"]:
            raise InspectionContractError(
                "QUESTION_MISMATCH", "answer does not match current question", status_code=409
            )
        if not payload.get("option_id") and not str(payload.get("free_text", "")).strip():
            raise InspectionContractError(
                "ANSWER_REQUIRED", "option_id or free_text is required"
            )
        session["answers"].append(
            {
                "question_id": question["id"],
                "option_id": payload.get("option_id"),
                "free_text": str(payload.get("free_text", "")).strip(),
            }
        )
        session["turn_count"] += 1
        if session["turn_count"] >= MAX_ASSISTANT_TURNS or _answer_is_decisive(payload):
            analysis = self.get_analysis(session["analysis_id"])
            session["state"] = "PROPOSED"
            session["question"] = None
            session["proposal"] = _proposal(analysis, tuple(session["answers"]))
            inspection = self._inspection(session["inspection_id"])
            inspection["state"] = "PROPOSED"
            inspection["revision"] += 1
        else:
            session["question"] = _question(session["turn_count"])
        result = _public_session(session)
        self._remember(f"answer:{session_id}", idempotency_key, result)
        return result

    def confirm(
        self,
        inspection_id: str,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
        expected_revision: int,
    ) -> dict[str, Any]:
        cached = self._cached(f"confirm:{inspection_id}", idempotency_key)
        if cached is not None:
            return cached
        inspection = self._inspection(inspection_id)
        if inspection["revision"] != expected_revision:
            raise InspectionContractError(
                "REVISION_CONFLICT", "inspection revision changed", status_code=409
            )
        if inspection["state"] != "PROPOSED":
            raise InspectionContractError(
                "INVALID_STATE", "inspection is not ready for confirmation", status_code=409
            )
        _require_fields(payload, "classification", "confirmation_source")
        source = str(payload["confirmation_source"])
        if source not in {"accepted", "corrected", "manual"}:
            raise InspectionContractError(
                "INVALID_CONFIRMATION_SOURCE", "invalid confirmation_source"
            )
        classification = payload["classification"]
        if not isinstance(classification, Mapping):
            raise InspectionContractError(
                "INVALID_CLASSIFICATION", "classification must be an object"
            )
        inspection["classification"] = copy.deepcopy(dict(classification))
        inspection["state"] = (
            "USER_CONFIRMED" if source == "accepted" else "USER_CORRECTED"
        )
        inspection["review_required"] = (
            classification.get("priority_code") == "P1"
            or not classification.get("part_detail_code")
        )
        if inspection["review_required"]:
            inspection["state"] = "REVIEW_REQUIRED"
        inspection["revision"] += 1
        result = copy.deepcopy(inspection)
        self._remember(f"confirm:{inspection_id}", idempotency_key, result)
        return result

    def _inspection(self, identifier: str) -> dict[str, Any]:
        try:
            return self.inspections[identifier]
        except KeyError:
            raise InspectionContractError(
                "INSPECTION_NOT_FOUND", "inspection not found", status_code=404
            ) from None

    def _session(self, identifier: str) -> dict[str, Any]:
        try:
            return self.sessions[identifier]
        except KeyError:
            raise InspectionContractError(
                "SESSION_NOT_FOUND", "assistant session not found", status_code=404
            ) from None

    def _cached(self, operation: str, key: str) -> dict[str, Any] | None:
        _idempotency_key(key)
        value = self.idempotency.get((operation, key))
        return copy.deepcopy(value) if value is not None else None

    def _remember(self, operation: str, key: str, value: Mapping[str, Any]) -> None:
        self.idempotency[(operation, key)] = copy.deepcopy(dict(value))


def _fake_candidates(opinion: str, top_k: int) -> list[dict[str, Any]]:
    text = opinion.lower()
    if any(token in text for token in ("물", "젖", "누수", "곰팡")):
        values = (
            ("cause", "CAUSE_LEAK", "누수", 0.72),
            ("cause", "CAUSE_CONDENSATION", "결로", 0.64),
            ("part", "PART_WALL", "벽", 0.61),
            ("part_detail", "DETAIL_WALLPAPER", "벽지", 0.55),
            ("work_kind", "WORK_PLUMBING", "설비공사", 0.51),
        )
    elif any(token in text for token in ("균열", "금", "갈라")):
        values = (
            ("cause", "CAUSE_CRACK", "파손(균열)", 0.79),
            ("part", "PART_WALL", "벽", 0.68),
            ("part_detail", "DETAIL_BASE", "바탕면", 0.57),
            ("work_kind", "WORK_FINISH", "내장공사", 0.49),
        )
    else:
        values = (
            ("cause", "CAUSE_CONSTRUCTION", "시공불량", 0.58),
            ("cause", "CAUSE_SCRATCH", "흠집", 0.52),
            ("part", "PART_WALL", "벽", 0.48),
            ("work_kind", "WORK_OTHER", "기타공사", 0.41),
        )
    return [
        {"axis": axis, "code": code, "label": label, "confidence": confidence}
        for axis, code, label, confidence in values[:top_k]
    ]


def _question(index: int) -> dict[str, Any]:
    questions = (
        {
            "id": "moisture",
            "text": "표면이 현재 젖어 있거나 물기가 만져지나요?",
            "options": [
                {"id": "wet", "label": "예, 젖어 있음"},
                {"id": "dry", "label": "아니요, 건조함"},
                {"id": "unknown", "label": "확인하기 어려움"},
            ],
        },
        {
            "id": "recurrence",
            "text": "같은 위치에서 이전에도 반복되었나요?",
            "options": [
                {"id": "repeated", "label": "반복됨"},
                {"id": "first", "label": "처음 발견"},
                {"id": "unknown", "label": "모름"},
            ],
        },
        {
            "id": "extent",
            "text": "하자가 한 지점인가요, 주변으로 번지고 있나요?",
            "options": [
                {"id": "spreading", "label": "번지고 있음"},
                {"id": "localized", "label": "한 지점"},
                {"id": "unknown", "label": "판단 어려움"},
            ],
        },
    )
    return copy.deepcopy(questions[min(index, len(questions) - 1)])


def _answer_is_decisive(payload: Mapping[str, Any]) -> bool:
    return payload.get("option_id") in {"wet", "dry", "spreading"}


def _proposal(
    analysis: Mapping[str, Any], answers: tuple[Mapping[str, Any], ...]
) -> dict[str, Any]:
    selected = {item.get("option_id") for item in answers}
    cause = "CAUSE_LEAK" if "wet" in selected else "CAUSE_CONSTRUCTION"
    priority = "P2" if "spreading" in selected or "wet" in selected else "P3"
    return {
        "area_code": "AREA_FROM_CAPTURE_CONTEXT",
        "part_code": "PART_WALL",
        "part_detail_code": "DETAIL_WALLPAPER",
        "symptom_code": "SYMPTOM_MOISTURE" if "wet" in selected else "SYMPTOM_DAMAGE",
        "work_kind_code": "WORK_PLUMBING" if "wet" in selected else "WORK_FINISH",
        "priority_code": priority,
        "suspected_cause_code": cause,
        "standardized_opinion": "벽면 하자 확인 필요",
        "prediction_id": analysis["id"],
    }


def _public_session(session: Mapping[str, Any]) -> dict[str, Any]:
    return {
        key: copy.deepcopy(session[key])
        for key in (
            "id",
            "inspection_id",
            "state",
            "turn_count",
            "max_turns",
            "question",
            "proposal",
        )
    }


def _require_fields(payload: Mapping[str, Any], *fields: str) -> None:
    if not isinstance(payload, Mapping):
        raise InspectionContractError("INVALID_REQUEST", "request must be an object")
    missing = [field for field in fields if field not in payload]
    if missing:
        raise InspectionContractError(
            "MISSING_FIELDS", f"missing required fields: {', '.join(missing)}"
        )


def _safe_upload_name(value: Any, media_id: str, mime_type: Any) -> str:
    """Keep the client inspection name while preventing path traversal.

    The Android app creates names such as
    ``101동1404호_안방_천정_0727-18-27-55_D001_CLOSE_P00111.jpg``.  The
    server treats that name as data, not as a path; legacy clients without a
    name retain the UUID-based name.
    """
    suffix = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
    }.get(str(mime_type).lower(), ".bin")
    raw = str(value or "").replace("\\", "/").rsplit("/", 1)[-1]
    # Permit Korean labels used by the field workflow, plus portable filename
    # characters. Everything else becomes an underscore.
    name = re.sub(r"[^0-9A-Za-z가-힣._-]+", "_", raw).strip("._")
    if not name:
        return f"{media_id}{suffix}"
    stem = Path(name).stem.strip("._")
    if not stem:
        return f"{media_id}{suffix}"
    return f"{stem}{suffix}"


def _uuid_text(value: Any, field_name: str) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, AttributeError):
        raise InspectionContractError(
            "INVALID_UUID", f"{field_name} must be a UUID"
        ) from None


def _idempotency_key(value: str) -> None:
    if not isinstance(value, str) or len(value.strip()) < 16:
        raise InspectionContractError(
            "INVALID_IDEMPOTENCY_KEY",
            "Idempotency-Key must contain at least 16 characters",
        )
