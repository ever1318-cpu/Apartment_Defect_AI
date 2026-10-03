"""Field-operation service contract for the offline-first Android client."""

from __future__ import annotations

import copy
import uuid
from datetime import date
from dataclasses import dataclass, field
from typing import Any, Mapping


class FieldServiceError(ValueError):
    def __init__(self, code: str, message: str, status_code: int = 400):
        super().__init__(message)
        self.code = code
        self.status_code = status_code


@dataclass(slots=True)
class MemoryFieldInspectionService:
    """Deterministic test adapter mirroring the PostgreSQL field boundary."""

    sessions: dict[str, dict[str, Any]] = field(default_factory=dict)
    defects: dict[str, dict[str, Any]] = field(default_factory=dict)
    media: dict[str, dict[str, Any]] = field(default_factory=dict)
    idempotency: dict[tuple[str, str], dict[str, Any]] = field(default_factory=dict)

    def healthcheck(self) -> bool:
        return True

    def resolve_household(self, building_no: str, unit_no: str) -> dict[str, Any]:
        if not building_no.strip() or not unit_no.strip():
            raise FieldServiceError("HOUSEHOLD_NOT_FOUND", "household not found", 404)
        return {
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"ulsan-down:{building_no}:{unit_no}")),
            "complex_code": "ULSAN_DOWN",
            "building_no": building_no,
            "unit_no": unit_no,
            "floorplan_type": "84B-1" if unit_no.endswith("04") else "84A-1",
            "owner_display_name": "점검매니저",
        }

    def taxonomy_catalog(self, floorplan_type: str, room_code: str | None = None, surface_code: str | None = None) -> dict[str, Any]:
        rooms = [
            {"code": "entry", "label": "현관", "clockwise_order": 0},
            {"code": "living", "label": "거실", "clockwise_order": 1},
            {"code": "kitchen", "label": "주방", "clockwise_order": 2},
            {"code": "master", "label": "안방", "clockwise_order": 3},
            {"code": "bed1", "label": "침실1", "clockwise_order": 4},
            {"code": "bed2", "label": "침실2", "clockwise_order": 5},
            {"code": "bath", "label": "공용욕실", "clockwise_order": 6},
            {"code": "balcony", "label": "베란다", "clockwise_order": 7},
        ]
        catalog = {
            "CEILING": ["천장 마감재", "도배지", "도장면", "조명·점검구", "배관 흔적"],
            "CEILING_WALL": ["천장 몰딩", "천장-벽 접합부", "코너 도배", "실리콘", "균열부"],
            "WALL": ["벽지", "도장면", "타일", "문틀·창틀 주변", "콘센트·스위치"],
            "WALL_FLOOR": ["걸레받이", "벽-바닥 접합부", "바닥 타일", "마루 끝단", "실리콘"],
            "FLOOR": ["마루·바닥재", "바닥 타일", "문턱", "배수구 주변", "난방·들뜸 부위"],
        }
        details = catalog.get(surface_code or "", [])
        return {"floorplan_type": floorplan_type, "rooms": rooms, "surfaces": list(catalog),
                "details": [{"code": value, "label": value} for value in details],
                "causes": ["시공불량", "미시공", "파손(균열)", "누수", "결로"],
                "source": "fallback"}
    def create_session(
        self, payload: Mapping[str, Any], *, idempotency_key: str
    ) -> dict[str, Any]:
        cached = self._cached("session", idempotency_key)
        if cached:
            return cached
        _require(payload, "client_uuid", "household_id", "inspector_id")
        inspection_kind = str(payload.get("inspection_kind", "HOUSEHOLD"))
        if inspection_kind not in {"HOUSEHOLD", "COMMON_AREA"}:
            raise FieldServiceError("INVALID_INSPECTION_KIND", "invalid inspection kind", 400)
        common_area_label = str(payload.get("common_area_label", "")).strip() or None
        if inspection_kind == "COMMON_AREA" and common_area_label is None:
            raise FieldServiceError("COMMON_AREA_LABEL_REQUIRED", "common-area location is required", 400)
        identifier = str(uuid.uuid4())
        result = {
            "id": identifier,
            "client_uuid": _uuid(payload["client_uuid"], "client_uuid"),
            "household_id": _uuid(payload["household_id"], "household_id"),
            "inspector_id": str(payload["inspector_id"]),
            "building_no": str(payload.get("building_no", "")),
            "unit_no": str(payload.get("unit_no", "")),
            "inspection_kind": inspection_kind,
            "common_area_label": common_area_label,
            "state": "ANCHOR_REQUIRED",
            "revision": 1,
        }
        self.sessions[identifier] = copy.deepcopy(result)
        self.sessions[identifier]["created_on"] = date.today().isoformat()
        return self._remember("session", idempotency_key, result)

    def manager_statistics(self, inspector_id: str) -> dict[str, Any]:
        """In-memory equivalent of the PostgreSQL manager dashboard query."""
        eligible = [
            session for session in self.sessions.values()
            if session.get("inspector_id") == inspector_id
            and session.get("inspection_kind", "HOUSEHOLD") == "HOUSEHOLD" and any(
                defect.get("session_id") == session["id"] and defect.get("state") != "DELETED"
                for defect in self.defects.values()
            )
        ]
        unique_households = {str(session["household_id"]) for session in eligible}
        start_date = min((str(session.get("created_on", date.today().isoformat())) for session in eligible), default=None)
        today = date.today().isoformat()
        today_households = {
            str(session["household_id"]) for session in eligible
            if session.get("created_on", today) == today
        }
        return {
            "inspector_id": inspector_id,
            "start_date": start_date,
            "total_households": len(unique_households),
            "today_households": len(today_households),
            "timezone": "Asia/Seoul",
        }

    def gallery_households(self) -> list[dict[str, Any]]:
        values = []
        for session in self.sessions.values():
            if session.get("state") != "COMPLETED":
                continue
            defects = [d for d in self.defects.values() if d.get("session_id") == session["id"]]
            if not defects:
                continue
            values.append({
                "building_no": str(session.get("building_no", "")),
                "unit_no": str(session.get("unit_no", "")),
                "defect_count": len(defects),
                "last_inspected_at": session.get("created_on"),
            })
        return sorted(values, key=lambda item: (item["building_no"], item["unit_no"]), reverse=True)

    def gallery_for_household(self, building_no: str, unit_no: str) -> dict[str, Any]:
        sessions = [
            s for s in self.sessions.values()
            if s.get("building_no") == building_no
            and s.get("unit_no") == unit_no
            and s.get("state") == "COMPLETED"
        ]
        session_by_id = {str(session["id"]): session for session in sessions}
        defects = [d for d in self.defects.values() if d.get("session_id") in session_by_id]
        result = []
        for defect in defects:
            result.append({
                "id": defect["id"], "defect_index": defect.get("defect_index", 0),
                "room_label": defect.get("room_label", ""),
                "raw_resident_opinion": defect.get("raw_resident_opinion", ""),
                "standardized_opinion": defect.get("standardized_opinion", ""),
                "focus_distance_m": defect.get("focus_distance_m"),
                "measured_gap_mm": defect.get("measured_gap_mm"),
                "measurement_method": defect.get("measurement_method", ""),
                "measurement_status": defect.get("measurement_status", ""),
                "common_area_label": session_by_id[str(defect["session_id"])].get("common_area_label"),
                "media": [self._media_view(m) for m in self.media.values() if m.get("defect_id") == defect["id"]],
            })
        return {"building_no": building_no, "unit_no": unit_no, "defects": result}

    def gallery_media(self, media_id: str) -> dict[str, Any]:
        try:
            return self._media_view(self.media[media_id])
        except KeyError:
            raise FieldServiceError("MEDIA_NOT_FOUND", "media not found", 404) from None

    @staticmethod
    def _media_view(media: Mapping[str, Any]) -> dict[str, Any]:
        return {key: media.get(key) for key in ("id", "role", "mime_type", "object_key", "metadata")}

    def resolve_session(self, client_uuid: str) -> dict[str, Any]:
        """Find the server session created for one local inspection session."""
        value = _uuid(client_uuid, "client_uuid")
        session = next(
            (item for item in self.sessions.values() if item["client_uuid"] == value),
            None,
        )
        if session is None:
            raise FieldServiceError("SESSION_NOT_FOUND", "session not found", 404)
        return copy.deepcopy(session)

    def set_anchor(
        self, session_id: str, payload: Mapping[str, Any], *, expected_revision: int
    ) -> dict[str, Any]:
        session = self._session(session_id)
        self._revision(session, expected_revision)
        _require(payload, "room_label", "x_norm", "y_norm", "heading_deg")
        session.update(
            anchor_room_code=payload.get("room_code"),
            anchor_room_label=str(payload["room_label"]),
            anchor_x_norm=_norm(payload["x_norm"], "x_norm"),
            anchor_y_norm=_norm(payload["y_norm"], "y_norm"),
            anchor_heading_deg=float(payload["heading_deg"]),
            state="ACTIVE",
            revision=session["revision"] + 1,
        )
        return copy.deepcopy(session)

    def upsert_defect(
        self,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
        expected_revision: int,
    ) -> dict[str, Any]:
        cached = self._cached("defect", idempotency_key)
        if cached:
            return cached
        _require(
            payload, "client_uuid", "session_id", "defect_index", "room_label",
            "x_norm", "y_norm", "taxonomy_version",
        )
        self._session(str(payload["session_id"]))
        client_uuid = _uuid(payload["client_uuid"], "client_uuid")
        existing = next(
            (item for item in self.defects.values() if item["client_uuid"] == client_uuid),
            None,
        )
        if existing is not None:
            self._revision(existing, expected_revision)
            result = existing
            result["revision"] += 1
        else:
            if expected_revision not in (0, 1):
                raise FieldServiceError("REVISION_CONFLICT", "new defect revision must be 0 or 1", 409)
            identifier = str(uuid.uuid4())
            result = {
                "id": identifier,
                "client_uuid": client_uuid,
                "revision": 1,
                "state": "CAPTURED",
            }
            self.defects[identifier] = result
        result.update(
            session_id=str(payload["session_id"]),
            defect_index=int(payload["defect_index"]),
            room_label=str(payload["room_label"]),
            x_norm=_norm(payload["x_norm"], "x_norm"),
            y_norm=_norm(payload["y_norm"], "y_norm"),
            raw_resident_opinion=str(payload.get("raw_resident_opinion", "")),
            standardized_opinion=str(payload.get("standardized_opinion", "")),
            focus_distance_m=(float(payload["focus_distance_m"])
                              if payload.get("focus_distance_m") is not None else None),
            measured_gap_mm=(float(payload["measured_gap_mm"])
                             if payload.get("measured_gap_mm") is not None else None),
            measurement_method=str(payload.get("measurement_method", "")),
            measurement_status=str(payload.get("measurement_status", "")),
            final_classification=copy.deepcopy(dict(payload.get("final_classification", {}))),
            taxonomy_version=str(payload["taxonomy_version"]),
            review_required=bool(payload.get("review_required", False)),
        )
        return self._remember("defect", idempotency_key, result)

    def confirm_defect(
        self, defect_id: str, payload: Mapping[str, Any], *, expected_revision: int
    ) -> dict[str, Any]:
        defect = self._defect(defect_id)
        self._revision(defect, expected_revision)
        _require(payload, "final_classification")
        defect["final_classification"] = copy.deepcopy(dict(payload["final_classification"]))
        defect["standardized_opinion"] = str(payload.get("standardized_opinion", ""))
        defect["state"] = "USER_CORRECTED" if payload.get("corrected") else "USER_CONFIRMED"
        defect["review_required"] = bool(payload.get("review_required", False))
        defect["revision"] += 1
        return copy.deepcopy(defect)

    def register_media(
        self, defect_id: str, payload: Mapping[str, Any], *, idempotency_key: str
    ) -> dict[str, Any]:
        cached = self._cached("media", idempotency_key)
        if cached:
            return cached
        defect = self._defect(defect_id)
        _require(payload, "client_uuid", "role", "mime_type", "size_bytes", "sha256", "object_key")
        role = str(payload["role"]).upper()
        if role not in {"ANCHOR_NEAR", "ANCHOR_FAR", "WIDE", "CLOSE", "EXTRA"}:
            raise FieldServiceError("INVALID_MEDIA_ROLE", "unsupported media role")
        result = {
            "id": str(uuid.uuid4()),
            "client_uuid": _uuid(payload["client_uuid"], "client_uuid"),
            "session_id": defect["session_id"],
            "defect_id": defect_id,
            "role": role,
            "mime_type": str(payload["mime_type"]),
            "size_bytes": int(payload["size_bytes"]),
            "sha256": str(payload["sha256"]),
            "object_key": str(payload["object_key"]),
            "upload_state": str(payload.get("upload_state", "PENDING")),
            "metadata": copy.deepcopy(dict(payload.get("metadata", {}))),
        }
        self.media[result["id"]] = copy.deepcopy(result)
        return self._remember("media", idempotency_key, result)

    def session_summary(self, session_id: str) -> dict[str, Any]:
        session = self._session(session_id)
        defects = [
            copy.deepcopy(item) for item in self.defects.values()
            if item["session_id"] == session_id and item["state"] != "DELETED"
        ]
        return {
            "session": copy.deepcopy(session),
            "counts": {
                "total": len(defects),
                "pending": sum(item["state"] not in {"USER_CONFIRMED", "USER_CORRECTED"} for item in defects),
                "confirmed": sum(item["state"] in {"USER_CONFIRMED", "USER_CORRECTED"} for item in defects),
                "review_required": sum(bool(item["review_required"]) for item in defects),
            },
            "defects": sorted(defects, key=lambda item: item["defect_index"]),
        }

    def complete_session(
        self, session_id: str, *, expected_revision: int
    ) -> dict[str, Any]:
        session = self._session(session_id)
        self._revision(session, expected_revision)
        summary = self.session_summary(session_id)
        if not summary["counts"]["total"]:
            raise FieldServiceError("EMPTY_SESSION", "cannot complete an empty session", 409)
        session["state"] = "COMPLETED"
        session["revision"] += 1
        return {
            "session": copy.deepcopy(session),
            "counts": summary["counts"],
            "next_unit_no": _next_unit(str(session.get("unit_no", ""))),
        }

    def sync_batch(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        _require(payload, "device_id", "operations")
        results = []
        for operation in payload["operations"]:
            try:
                kind = operation["kind"]
                body = operation["payload"]
                if kind == "UPSERT_DEFECT":
                    value = self.upsert_defect(
                        body,
                        idempotency_key=str(operation["operation_id"]),
                        expected_revision=int(operation.get("base_revision", 0)),
                    )
                else:
                    raise FieldServiceError("UNSUPPORTED_OPERATION", f"unsupported operation {kind}")
                results.append({"operation_id": operation["operation_id"], "state": "APPLIED", "resource": value})
            except FieldServiceError as exc:
                results.append({"operation_id": operation.get("operation_id"), "state": "CONFLICT" if exc.status_code == 409 else "REJECTED", "error_code": exc.code})
        return {"device_id": str(payload["device_id"]), "results": results}

    def _session(self, identifier: str) -> dict[str, Any]:
        try:
            return self.sessions[identifier]
        except KeyError:
            raise FieldServiceError("SESSION_NOT_FOUND", "session not found", 404) from None

    def _defect(self, identifier: str) -> dict[str, Any]:
        try:
            return self.defects[identifier]
        except KeyError:
            raise FieldServiceError("DEFECT_NOT_FOUND", "defect not found", 404) from None

    @staticmethod
    def _revision(record: Mapping[str, Any], expected: int) -> None:
        if int(record["revision"]) != expected:
            raise FieldServiceError("REVISION_CONFLICT", "resource revision changed", 409)

    def _cached(self, scope: str, key: str) -> dict[str, Any] | None:
        if len(key.strip()) < 16:
            raise FieldServiceError("INVALID_IDEMPOTENCY_KEY", "idempotency key must contain at least 16 characters")
        value = self.idempotency.get((scope, key))
        return copy.deepcopy(value) if value else None

    def _remember(self, scope: str, key: str, value: Mapping[str, Any]) -> dict[str, Any]:
        result = copy.deepcopy(dict(value))
        self.idempotency[(scope, key)] = result
        return copy.deepcopy(result)


def _require(payload: Mapping[str, Any], *fields: str) -> None:
    missing = [name for name in fields if payload.get(name) is None]
    if missing:
        raise FieldServiceError("MISSING_FIELDS", "missing required fields: " + ", ".join(missing))


def _uuid(value: Any, name: str) -> str:
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, AttributeError):
        raise FieldServiceError("INVALID_UUID", f"{name} must be a UUID") from None


def _norm(value: Any, name: str) -> float:
    number = float(value)
    if not 0 <= number <= 1:
        raise FieldServiceError("INVALID_COORDINATE", f"{name} must be between 0 and 1")
    return number


def _next_unit(unit_no: str) -> str | None:
    if not unit_no.isdigit() or len(unit_no) < 3:
        return None
    number = int(unit_no)
    floor, line = divmod(number, 100)
    return str(floor * 100 + line + 1 if line in range(1, 4) else (floor + 1) * 100 + 1)
