"""Run a complete HTTP field flow against the local PostgreSQL database."""

from __future__ import annotations

import json
import socket
import threading
import time
import urllib.request
import urllib.error
import uuid

import uvicorn

from data_engineering.database.config import DatabaseConfig
from data_engineering.database.connection import connect_database
from vision_ai.field_postgres_entrypoint import create_field_postgres_app


def request(base: str, method: str, path: str, payload=None, headers=None):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    request_headers = {"Content-Type": "application/json", **(headers or {})}
    req = urllib.request.Request(base + path, data=body, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"HTTP {exc.code} {method} {path}: {detail}"
        ) from exc


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def main() -> None:
    config = DatabaseConfig.from_prefixed_environment("LOCAL_APARTMENT_DB_")
    household_id = lookup_household(config)
    port = free_port()
    app = create_field_postgres_app()
    server = uvicorn.Server(
        uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error")
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"
    session_id = None
    try:
        wait_until_healthy(base)
        _, health = request(base, "GET", "/health")
        _, session = request(
            base, "POST", "/v2/field/sessions",
            {
                "client_uuid": str(uuid.uuid4()),
                "household_id": household_id,
                "inspector_id": "점검매니저",
            },
            {"Idempotency-Key": "local-http-session-" + uuid.uuid4().hex},
        )
        session_id = session["id"]
        _, anchored = request(
            base, "PUT", f"/v2/field/sessions/{session_id}/anchor",
            {
                "room_code": "living", "room_label": "거실",
                "x_norm": 0.5, "y_norm": 0.62, "heading_deg": 90,
            },
            {"If-Match": str(session["revision"])},
        )
        _, defect = request(
            base, "PUT", "/v2/field/defects",
            {
                "client_uuid": str(uuid.uuid4()), "session_id": session_id,
                "defect_index": 1, "room_code": "living", "room_label": "거실",
                "x_norm": 0.51, "y_norm": 0.61, "surface_code": "WALL",
                "raw_resident_opinion": "STT 원문: 벽에 균열이 보여요",
                "standardized_opinion": "거실 벽면 균열",
                "taxonomy_version": "2.0.0",
            },
            {
                "If-Match": "0",
                "Idempotency-Key": "local-http-defect-" + uuid.uuid4().hex,
            },
        )
        request(
            base, "POST", f"/v2/field/defects/{defect['id']}/confirmation",
            {
                "standardized_opinion": "거실 벽면 균열 확인",
                "final_classification": {
                    "location_code": "LIVING", "part_code": "WALL",
                    "part_detail_code": "WALL_CENTER",
                    "work_kind_code": "FINISH", "cause_code": "CRACK",
                    "priority_code": "P3",
                },
            },
            {"If-Match": str(defect["revision"])},
        )
        _, summary = request(base, "GET", f"/v2/field/sessions/{session_id}/summary")
        _, completed = request(
            base, "POST", f"/v2/field/sessions/{session_id}/complete",
            {}, {"If-Match": str(anchored["revision"])},
        )
        assert health["persistence"] == "postgresql"
        assert summary["counts"]["total"] == 1
        assert summary["counts"]["confirmed"] == 1
        assert completed["session"]["state"] == "COMPLETED"
        print("HTTP_FIELD_FLOW=PASS")
        print("PERSISTENCE=postgresql")
        print("SUMMARY_TOTAL=1")
        print("SUMMARY_CONFIRMED=1")
    finally:
        server.should_exit = True
        thread.join(timeout=10)
        if session_id:
            cleanup_session(config, session_id)


def lookup_household(config: DatabaseConfig) -> str:
    connection = connect_database(config)
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT h.id
                FROM apartment_ai.households h
                JOIN apartment_ai.buildings b ON b.id=h.building_id
                WHERE b.building_no='101' AND h.unit_no='1501'
                """
            )
            row = cursor.fetchone()
            if row is None:
                raise RuntimeError("seed household 101-1501 not found")
            return str(row[0])


def cleanup_session(config: DatabaseConfig, session_id: str) -> None:
    connection = connect_database(config)
    with connection:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM apartment_ai.inspection_sessions WHERE id=%s",
                (session_id,),
            )


def wait_until_healthy(base: str) -> None:
    for _ in range(80):
        try:
            if request(base, "GET", "/health")[0] == 200:
                return
        except OSError:
            time.sleep(0.05)
    raise RuntimeError("local field server did not become healthy")


if __name__ == "__main__":
    main()
