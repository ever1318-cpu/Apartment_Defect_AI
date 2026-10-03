"""PostgreSQL persistence boundary for field inspection sessions and defects."""

from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from typing import Any, Callable, Mapping

from data_engineering.database.config import DatabaseConfig
from data_engineering.database.connection import connect_database


class PersistenceConflict(RuntimeError):
    pass


def _json(value: object) -> str:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def _request_hash(payload: Mapping[str, Any]) -> str:
    return hashlib.sha256(_json(payload).encode("utf-8")).hexdigest()


@dataclass(slots=True)
class PostgresInspectionStore:
    config: DatabaseConfig
    connector: Callable[..., Any] | None = None

    def healthcheck(self) -> bool:
        """Execute a read-only connectivity probe used by the development health API."""
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute("SELECT 1")
                    return cursor.fetchone()[0] == 1

    def resolve_household(self, building_no: str, unit_no: str) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT h.id, c.code, b.building_no, h.unit_no,
                               f.type_code, h.owner_display_name
                        FROM apartment_ai.households h
                        JOIN apartment_ai.buildings b ON b.id=h.building_id
                        JOIN apartment_ai.complexes c ON c.id=b.complex_id
                        JOIN apartment_ai.floorplans f ON f.id=h.floorplan_id
                        WHERE b.building_no=%s AND h.unit_no=%s
                        """,
                        (building_no, unit_no),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise ValueError("household not found")
                    return {
                        "id": str(row[0]), "complex_code": str(row[1]),
                        "building_no": str(row[2]), "unit_no": str(row[3]),
                        "floorplan_type": str(row[4]),
                        "owner_display_name": str(row[5]),
                    }

    def taxonomy_catalog(self, floorplan_type: str, room_code: str | None = None, surface_code: str | None = None) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT r.room_code, r.room_label, r.x_norm, r.y_norm, r.bbox, r.clockwise_order
                        FROM apartment_ai.floorplan_room_master r
                        JOIN apartment_ai.floorplans f ON f.id=r.floorplan_id
                        WHERE f.type_code=%s AND f.active AND r.active
                        ORDER BY r.clockwise_order, r.room_code
                        """, (floorplan_type,))
                    rooms = [{"code": str(row[0]), "label": str(row[1]), "x_norm": float(row[2]), "y_norm": float(row[3]), "bbox": row[4], "clockwise_order": int(row[5])} for row in cursor.fetchall()]
                    cursor.execute(
                        """
                        SELECT DISTINCT surface_code FROM apartment_ai.defect_taxonomy_master
                        WHERE floorplan_type_code=%s AND active ORDER BY surface_code
                        """, (floorplan_type,))
                    surfaces = [str(row[0]) for row in cursor.fetchall()]
                    details: list[dict[str, str]] = []
                    causes: list[dict[str, str]] = []
                    if surface_code:
                        cursor.execute(
                            """
                            SELECT DISTINCT detail_code, detail_label, trade_code, trade_label
                            FROM apartment_ai.defect_taxonomy_master
                            WHERE floorplan_type_code=%s AND surface_code=%s
                              AND room_code IN ('*', %s) AND active
                            ORDER BY detail_label LIMIT 5
                            """, (floorplan_type, surface_code, room_code or "*"))
                        details = [{"code": str(row[0]), "label": str(row[1]), "trade_code": str(row[2]), "trade_label": str(row[3])} for row in cursor.fetchall()]
                        cursor.execute(
                            """
                            SELECT DISTINCT cause_code, cause_label FROM apartment_ai.defect_taxonomy_master
                            WHERE floorplan_type_code=%s AND surface_code=%s
                              AND room_code IN ('*', %s) AND active
                            ORDER BY cause_label
                            """, (floorplan_type, surface_code, room_code or "*"))
                        causes = [{"code": str(row[0]), "label": str(row[1])} for row in cursor.fetchall()]
        if not rooms:
            raise ValueError("floorplan taxonomy not found")
        return {"floorplan_type": floorplan_type, "rooms": rooms, "surfaces": surfaces,
                "details": details, "causes": causes, "source": "postgres"}
    def manager_statistics(self, inspector_id: str) -> dict[str, Any]:
        """Return manager counts for unique households with synced defects."""
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT
                            MIN((s.created_at AT TIME ZONE 'Asia/Seoul')::date)::text,
                            COUNT(DISTINCT s.household_id),
                            COUNT(DISTINCT s.household_id) FILTER (
                                WHERE (s.created_at AT TIME ZONE 'Asia/Seoul')::date =
                                      (now() AT TIME ZONE 'Asia/Seoul')::date
                            )
                        FROM apartment_ai.inspection_sessions s
                        WHERE s.inspector_id=%s
                          AND s.inspection_kind = 'HOUSEHOLD'
                          AND EXISTS (
                              SELECT 1 FROM apartment_ai.defects d
                              WHERE d.session_id=s.id AND d.state <> 'DELETED'
                          )
                        """,
                        (inspector_id,),
                    )
                    row = cursor.fetchone()
        return {
            "inspector_id": inspector_id,
            "start_date": str(row[0]) if row and row[0] else None,
            "total_households": int(row[1] or 0) if row else 0,
            "today_households": int(row[2] or 0) if row else 0,
            "timezone": "Asia/Seoul",
        }

    def gallery_households(self) -> list[dict[str, Any]]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT b.building_no, h.unit_no, COUNT(DISTINCT d.id),
                               MAX(s.updated_at AT TIME ZONE 'Asia/Seoul')::text
                        FROM apartment_ai.inspection_sessions s
                        JOIN apartment_ai.households h ON h.id=s.household_id
                        JOIN apartment_ai.buildings b ON b.id=h.building_id
                        JOIN apartment_ai.defects d ON d.session_id=s.id AND d.state <> 'DELETED'
                        WHERE s.state = 'COMPLETED'
                        GROUP BY b.building_no, h.unit_no
                        ORDER BY MAX(s.updated_at) DESC, b.building_no, h.unit_no
                        """
                    )
                    return [
                        {"building_no": str(row[0]), "unit_no": str(row[1]),
                         "defect_count": int(row[2]), "last_inspected_at": str(row[3])}
                        for row in cursor.fetchall()
                    ]

    def gallery_for_household(self, building_no: str, unit_no: str) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT d.id, d.defect_index, d.room_label, d.surface_code,
                               d.raw_resident_opinion, d.standardized_opinion,
                               d.final_location_code, d.final_part_code,
                               d.final_part_detail_code, d.final_work_kind_code,
                               d.final_cause_code, d.priority_code,
                               d.created_at AT TIME ZONE 'Asia/Seoul',
                               d.focus_distance_m, d.measured_gap_mm,
                               d.measurement_method, d.measurement_status, s.common_area_label
                        FROM apartment_ai.defects d
                        JOIN apartment_ai.inspection_sessions s ON s.id=d.session_id
                        JOIN apartment_ai.households h ON h.id=s.household_id
                        JOIN apartment_ai.buildings b ON b.id=h.building_id
                        WHERE b.building_no=%s AND h.unit_no=%s
                          AND s.state = 'COMPLETED'
                          AND d.state <> 'DELETED'
                        ORDER BY d.created_at DESC, d.defect_index DESC
                        """, (building_no, unit_no)
                    )
                    defects = cursor.fetchall()
                    views = []
                    for row in defects:
                        cursor.execute(
                            """SELECT id, role, mime_type, object_key, metadata, created_at AT TIME ZONE 'Asia/Seoul'
                               FROM apartment_ai.media WHERE defect_id=%s AND upload_state IN ('UPLOADED','VERIFIED')
                               ORDER BY created_at""", (row[0],)
                        )
                        media = [
                            {"id": str(item[0]), "role": str(item[1]), "mime_type": str(item[2]),
                             "object_key": str(item[3]), "metadata": item[4] or {}, "created_at": str(item[5])}
                            for item in cursor.fetchall()
                        ]
                        views.append({
                            "id": str(row[0]), "defect_index": int(row[1]), "room_label": str(row[2]),
                            "surface_code": row[3], "raw_resident_opinion": str(row[4]),
                            "standardized_opinion": str(row[5]),
                            "classification": {"location": row[6], "part": row[7], "detail": row[8],
                                               "trade": row[9], "cause": row[10], "priority": row[11]},
                            "created_at": str(row[12]),
                            "focus_distance_m": float(row[13]) if row[13] is not None else None,
                            "measured_gap_mm": float(row[14]) if row[14] is not None else None,
                            "measurement_method": str(row[15] or ""),
                            "measurement_status": str(row[16] or ""),
                            "common_area_label": str(row[17]) if row[17] else None,
                            "media": media,
                        })
        return {"building_no": building_no, "unit_no": unit_no, "defects": views}

    def gallery_media(self, media_id: str) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """SELECT id, mime_type, object_key, metadata FROM apartment_ai.media
                           WHERE id=%s AND upload_state IN ('UPLOADED','VERIFIED')""",
                        (media_id,),
                    )
                    row = cursor.fetchone()
        if row is None:
            raise ValueError("media not found")
        return {
            "id": str(row[0]),
            "mime_type": str(row[1]),
            "object_key": str(row[2]),
            "metadata": row[3] or {},
        }

    def create_session(
        self,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        required = ("client_uuid", "household_id", "inspector_id")
        missing = [name for name in required if not payload.get(name)]
        if missing:
            raise ValueError("missing required fields: " + ", ".join(missing))
        client_uuid = str(uuid.UUID(str(payload["client_uuid"])))
        household_id = str(uuid.UUID(str(payload["household_id"])))
        inspection_kind = str(payload.get("inspection_kind", "HOUSEHOLD"))
        if inspection_kind not in {"HOUSEHOLD", "COMMON_AREA"}:
            raise ValueError("invalid inspection_kind")
        common_area_label = str(payload.get("common_area_label", "")).strip() or None
        revision_no = max(1, int(payload.get("revision_no", 1)))
        session_mode = str(payload.get("session_mode", "INITIAL")).upper()
        if session_mode not in {"INITIAL", "AMENDMENT"}:
            raise ValueError("invalid session_mode")
        amended_from_local_session_id = payload.get("amended_from_local_session_id")
        if amended_from_local_session_id is not None:
            amended_from_local_session_id = int(amended_from_local_session_id)
        if inspection_kind == "COMMON_AREA" and common_area_label is None:
            raise ValueError("common_area_label is required for common-area inspection")
        identifier = str(uuid.uuid4())
        response = {
            "id": identifier,
            "client_uuid": client_uuid,
            "state": "ANCHOR_REQUIRED",
            "revision": 1,
        }
        request_digest = _request_hash(payload)
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cached = self._cached(
                        cursor, "create_session", idempotency_key, request_digest
                    )
                    if cached is not None:
                        return cached
                    cursor.execute(
                        """
                        INSERT INTO apartment_ai.inspection_sessions
                          (id, client_uuid, household_id, inspector_id, inspection_kind, common_area_label,
                           revision_no, session_mode, amended_from_local_session_id, state)
                        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, 'ANCHOR_REQUIRED')
                        ON CONFLICT (client_uuid) DO UPDATE
                        SET updated_at = now()
                        RETURNING id, client_uuid, state, revision
                        """,
                        (identifier, client_uuid, household_id, str(payload["inspector_id"]), inspection_kind, common_area_label,
                         revision_no, session_mode, amended_from_local_session_id),
                    )
                    row = cursor.fetchone()
                    response = {
                        "id": str(row[0]),
                        "client_uuid": str(row[1]),
                        "state": str(row[2]),
                        "revision": int(row[3]),
                    }
                    self._remember(
                        cursor,
                        "create_session",
                        idempotency_key,
                        request_digest,
                        response,
                        response["id"],
                    )
        return response

    def resolve_session(self, client_uuid: str) -> dict[str, Any]:
        identifier = str(uuid.UUID(str(client_uuid)))
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT id, client_uuid, household_id, inspector_id, state, revision
                        FROM apartment_ai.inspection_sessions
                        WHERE client_uuid=%s
                        """,
                        (identifier,),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise ValueError("session not found")
                    return {
                        "id": str(row[0]), "client_uuid": str(row[1]),
                        "household_id": str(row[2]), "inspector_id": str(row[3]),
                        "state": str(row[4]), "revision": int(row[5]),
                    }

    def upsert_defect(
        self,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
        expected_revision: int,
    ) -> dict[str, Any]:
        required = (
            "client_uuid", "session_id", "defect_index", "room_label",
            "x_norm", "y_norm", "taxonomy_version",
        )
        missing = [name for name in required if payload.get(name) is None]
        if missing:
            raise ValueError("missing required fields: " + ", ".join(missing))
        request_digest = _request_hash(payload)
        identifier = str(uuid.uuid4())
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cached = self._cached(
                        cursor, "upsert_defect", idempotency_key, request_digest
                    )
                    if cached is not None:
                        return cached
                    cursor.execute(
                        """
                        INSERT INTO apartment_ai.defects (
                          id, client_uuid, session_id, defect_index, state,
                          room_code, room_label, x_norm, y_norm, surface_code, focus_distance_m,
                          measured_gap_mm, measurement_method, measurement_status,
                          raw_resident_opinion, standardized_opinion,
                          final_location_code, final_part_code,
                          final_part_detail_code, final_work_kind_code,
                          final_cause_code, priority_code, taxonomy_version
                        ) VALUES (
                          %s,%s,%s,%s,'CAPTURED',%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s
                        )
                        ON CONFLICT (client_uuid) DO UPDATE SET
                          room_code = EXCLUDED.room_code,
                          room_label = EXCLUDED.room_label,
                          x_norm = EXCLUDED.x_norm,
                          y_norm = EXCLUDED.y_norm,
                          surface_code = EXCLUDED.surface_code,
                          focus_distance_m = EXCLUDED.focus_distance_m,
                          measured_gap_mm = EXCLUDED.measured_gap_mm,
                          measurement_method = EXCLUDED.measurement_method,
                          measurement_status = EXCLUDED.measurement_status,
                          raw_resident_opinion = EXCLUDED.raw_resident_opinion,
                          standardized_opinion = EXCLUDED.standardized_opinion,
                          final_location_code = EXCLUDED.final_location_code,
                          final_part_code = EXCLUDED.final_part_code,
                          final_part_detail_code = EXCLUDED.final_part_detail_code,
                          final_work_kind_code = EXCLUDED.final_work_kind_code,
                          final_cause_code = EXCLUDED.final_cause_code,
                          priority_code = EXCLUDED.priority_code,
                          revision = apartment_ai.defects.revision + 1,
                          updated_at = now()
                        WHERE apartment_ai.defects.revision = %s
                        RETURNING id, client_uuid, state, revision
                        """,
                        (
                            identifier, str(payload["client_uuid"]),
                            str(payload["session_id"]), int(payload["defect_index"]),
                            payload.get("room_code"), str(payload["room_label"]),
                            float(payload["x_norm"]), float(payload["y_norm"]),
                            payload.get("surface_code"),
                            float(payload["focus_distance_m"])
                                if payload.get("focus_distance_m") is not None else None,
                            float(payload["measured_gap_mm"])
                                if payload.get("measured_gap_mm") is not None else None,
                            str(payload.get("measurement_method", "")),
                            str(payload.get("measurement_status", "")),
                            str(payload.get("raw_resident_opinion", "")),
                            str(payload.get("standardized_opinion", "")),
                            payload.get("final_location_code"),
                            payload.get("final_part_code"),
                            payload.get("final_part_detail_code"),
                            payload.get("final_work_kind_code"),
                            payload.get("final_cause_code"),
                            payload.get("priority_code"),
                            str(payload["taxonomy_version"]),
                            expected_revision,
                        ),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise PersistenceConflict("defect revision conflict")
                    response = {
                        "id": str(row[0]), "client_uuid": str(row[1]),
                        "state": str(row[2]), "revision": int(row[3]),
                    }
                    self._remember(
                        cursor, "upsert_defect", idempotency_key,
                        request_digest, response, response["id"],
                    )
        return response

    def set_anchor(
        self,
        session_id: str,
        payload: Mapping[str, Any],
        *,
        expected_revision: int,
    ) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        UPDATE apartment_ai.inspection_sessions
                        SET anchor_room_code=%s, anchor_room_label=%s,
                            anchor_x_norm=%s, anchor_y_norm=%s,
                            anchor_heading_deg=%s, state='ACTIVE',
                            revision=revision+1, updated_at=now()
                        WHERE id=%s AND revision=%s
                        RETURNING id, state, revision, anchor_room_label,
                                  anchor_x_norm, anchor_y_norm, anchor_heading_deg
                        """,
                        (
                            payload.get("room_code"), str(payload["room_label"]),
                            float(payload["x_norm"]), float(payload["y_norm"]),
                            float(payload["heading_deg"]), session_id, expected_revision,
                        ),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise PersistenceConflict("session revision conflict")
                    return {
                        "id": str(row[0]), "state": str(row[1]), "revision": int(row[2]),
                        "anchor_room_label": str(row[3]),
                        "anchor_x_norm": float(row[4]), "anchor_y_norm": float(row[5]),
                        "anchor_heading_deg": float(row[6]),
                    }

    def confirm_defect(
        self,
        defect_id: str,
        payload: Mapping[str, Any],
        *,
        expected_revision: int,
    ) -> dict[str, Any]:
        classification = dict(payload["final_classification"])
        corrected = bool(payload.get("corrected", False))
        state = "USER_CORRECTED" if corrected else "USER_CONFIRMED"
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        UPDATE apartment_ai.defects
                        SET state=%s, standardized_opinion=%s,
                            final_location_code=%s, final_part_code=%s,
                            final_part_detail_code=%s, final_work_kind_code=%s,
                            final_cause_code=%s, priority_code=%s,
                            review_required=%s, revision=revision+1, updated_at=now()
                        WHERE id=%s AND revision=%s
                        RETURNING id, state, revision, review_required
                        """,
                        (
                            state, str(payload.get("standardized_opinion", "")),
                            classification.get("location_code"),
                            classification.get("part_code"),
                            classification.get("part_detail_code"),
                            classification.get("work_kind_code"),
                            classification.get("cause_code"),
                            classification.get("priority_code"),
                            bool(payload.get("review_required", False)),
                            defect_id, expected_revision,
                        ),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise PersistenceConflict("defect revision conflict")
                    return {
                        "id": str(row[0]), "state": str(row[1]),
                        "revision": int(row[2]), "review_required": bool(row[3]),
                    }

    def register_media(
        self,
        defect_id: str,
        payload: Mapping[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        request_digest = _request_hash(payload)
        identifier = str(uuid.uuid4())
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cached = self._cached(cursor, "register_media", idempotency_key, request_digest)
                    if cached is not None:
                        return cached
                    cursor.execute(
                        """
                        INSERT INTO apartment_ai.media
                          (id, client_uuid, session_id, defect_id, role, mime_type,
                           size_bytes, sha256, object_key, upload_state, metadata)
                        SELECT %s,%s,d.session_id,d.id,%s,%s,%s,%s,%s,%s,%s::jsonb
                        FROM apartment_ai.defects d WHERE d.id=%s
                        RETURNING id, client_uuid, session_id, defect_id, role, upload_state
                        """,
                        (
                            identifier, str(payload["client_uuid"]), str(payload["role"]).upper(),
                            str(payload["mime_type"]), int(payload["size_bytes"]),
                            str(payload["sha256"]), str(payload["object_key"]),
                            str(payload.get("upload_state", "PENDING")),
                            _json(payload.get("metadata", {})), defect_id,
                        ),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise ValueError("defect not found")
                    response = {
                        "id": str(row[0]), "client_uuid": str(row[1]),
                        "session_id": str(row[2]), "defect_id": str(row[3]),
                        "role": str(row[4]), "upload_state": str(row[5]),
                    }
                    self._remember(cursor, "register_media", idempotency_key, request_digest, response, response["id"])
                    return response

    def session_summary(self, session_id: str) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        SELECT id, state, revision, household_id, inspector_id
                        FROM apartment_ai.inspection_sessions WHERE id=%s
                        """,
                        (session_id,),
                    )
                    session = cursor.fetchone()
                    if session is None:
                        raise ValueError("session not found")
                    cursor.execute(
                        """
                        SELECT id, client_uuid, defect_index, state, room_label,
                               x_norm, y_norm, raw_resident_opinion,
                               standardized_opinion, revision, review_required
                        FROM apartment_ai.defects
                        WHERE session_id=%s AND state <> 'DELETED'
                        ORDER BY defect_index
                        """,
                        (session_id,),
                    )
                    defects = [
                        {
                            "id": str(row[0]), "client_uuid": str(row[1]),
                            "defect_index": int(row[2]), "state": str(row[3]),
                            "room_label": str(row[4]), "x_norm": float(row[5]),
                            "y_norm": float(row[6]), "raw_resident_opinion": str(row[7]),
                            "standardized_opinion": str(row[8]), "revision": int(row[9]),
                            "review_required": bool(row[10]),
                        }
                        for row in cursor.fetchall()
                    ]
        confirmed = {"USER_CONFIRMED", "USER_CORRECTED"}
        return {
            "session": {
                "id": str(session[0]), "state": str(session[1]), "revision": int(session[2]),
                "household_id": str(session[3]), "inspector_id": str(session[4]),
            },
            "counts": {
                "total": len(defects),
                "pending": sum(item["state"] not in confirmed for item in defects),
                "confirmed": sum(item["state"] in confirmed for item in defects),
                "review_required": sum(item["review_required"] for item in defects),
            },
            "defects": defects,
        }

    def complete_session(
        self, session_id: str, *, expected_revision: int
    ) -> dict[str, Any]:
        connection = connect_database(self.config, connector=self.connector)
        with connection:
            with connection.transaction():
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        UPDATE apartment_ai.inspection_sessions s
                        SET state='COMPLETED', completed_at=now(),
                            revision=revision+1, updated_at=now()
                        WHERE s.id=%s AND s.revision=%s
                          AND EXISTS (
                            SELECT 1 FROM apartment_ai.defects d
                            WHERE d.session_id=s.id AND d.state <> 'DELETED'
                          )
                        RETURNING id, state, revision
                        """,
                        (session_id, expected_revision),
                    )
                    row = cursor.fetchone()
                    if row is None:
                        raise PersistenceConflict("session revision conflict or empty session")
                    return {"session": {"id": str(row[0]), "state": str(row[1]), "revision": int(row[2])}}

    def sync_batch(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        results = []
        for operation in payload.get("operations", []):
            operation_id = str(operation.get("operation_id", ""))
            try:
                if operation.get("kind") != "UPSERT_DEFECT":
                    raise ValueError("unsupported operation")
                resource = self.upsert_defect(
                    operation["payload"],
                    idempotency_key=operation_id,
                    expected_revision=int(operation.get("base_revision", 0)),
                )
                results.append(
                    {"operation_id": operation_id, "state": "APPLIED", "resource": resource}
                )
            except PersistenceConflict:
                results.append(
                    {"operation_id": operation_id, "state": "CONFLICT", "error_code": "REVISION_CONFLICT"}
                )
            except (KeyError, TypeError, ValueError):
                results.append(
                    {"operation_id": operation_id, "state": "REJECTED", "error_code": "INVALID_OPERATION"}
                )
        return {"device_id": str(payload.get("device_id", "")), "results": results}

    @staticmethod
    def _cached(
        cursor: Any, scope: str, key: str, request_hash: str
    ) -> dict[str, Any] | None:
        if len(key.strip()) < 16:
            raise ValueError("idempotency key must contain at least 16 characters")
        cursor.execute(
            """
            SELECT request_hash, response_body
            FROM apartment_ai.idempotency_records
            WHERE scope = %s AND idempotency_key = %s AND expires_at > now()
            """,
            (scope, key),
        )
        row = cursor.fetchone()
        if row is None:
            return None
        if str(row[0]) != request_hash:
            raise PersistenceConflict("idempotency key reused with another payload")
        return dict(row[1])

    @staticmethod
    def _remember(
        cursor: Any,
        scope: str,
        key: str,
        request_hash: str,
        response: Mapping[str, Any],
        resource_id: str,
    ) -> None:
        cursor.execute(
            """
            INSERT INTO apartment_ai.idempotency_records
              (scope, idempotency_key, request_hash, response_status,
               response_body, resource_id, expires_at)
            VALUES (%s, %s, %s, 200, %s::jsonb, %s, now() + interval '24 hours')
            ON CONFLICT (scope, idempotency_key) DO NOTHING
            """,
            (scope, key, request_hash, _json(response), resource_id),
        )
