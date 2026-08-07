import hashlib
import json
import socket
import threading
import time
import urllib.request
import uuid

import pytest

from vision_ai.inspection_dev_app import create_inspection_dev_app


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _request(base, method, path, payload=None, headers=None):
    body = None
    request_headers = dict(headers or {})
    if isinstance(payload, dict):
        body = json.dumps(payload).encode()
        request_headers["Content-Type"] = "application/json"
    elif isinstance(payload, bytes):
        body = payload
    request = urllib.request.Request(
        f"{base}{path}",
        data=body,
        headers=request_headers,
        method=method,
    )
    with urllib.request.urlopen(request, timeout=5) as response:
        return response.status, json.loads(response.read())


@pytest.mark.integration
def test_live_http_media_to_confirmation_flow():
    uvicorn = pytest.importorskip("uvicorn")
    port = _free_port()
    server = uvicorn.Server(
        uvicorn.Config(
            create_inspection_dev_app(),
            host="127.0.0.1",
            port=port,
            log_level="error",
        )
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    base = f"http://127.0.0.1:{port}"
    for _ in range(50):
        try:
            if _request(base, "GET", "/health")[0] == 200:
                break
        except OSError:
            time.sleep(0.05)
    else:
        pytest.fail("development server did not start")

    try:
        wide = b"wide-http-image"
        close = b"close-http-image"
        status, upload_session = _request(
            base,
            "POST",
            "/v2/media/upload-sessions",
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
            {"Idempotency-Key": "http-upload-session-0001"},
        )
        assert status == 201
        uploads = upload_session["uploads"]
        for instruction, content in zip(uploads, (wide, close), strict=True):
            assert _request(
                base,
                "PUT",
                instruction["upload_url"],
                content,
                instruction["headers"],
            )[0] == 200

        _, inspection = _request(
            base,
            "POST",
            "/v2/inspections",
            {
                "client_uuid": str(uuid.uuid4()),
                "taxonomy_version": "2.0.0",
                "site": {"source": "http-e2e"},
                "location": {"building": "101", "unit": "1001", "area": "living"},
                "capture_pair": {
                    "wide_media_id": uploads[0]["media_id"],
                    "close_media_id": uploads[1]["media_id"],
                },
                "raw_opinion": "water on wall",
            },
            {"Idempotency-Key": "http-create-inspection-0001"},
        )
        _, analysis = _request(
            base,
            "POST",
            f"/v2/inspections/{inspection['id']}/analysis",
            {
                "model_name": "apartment-defect-convnext",
                "model_version": "2.0.0",
                "top_k": 3,
            },
            {"Idempotency-Key": "http-start-analysis-0001"},
        )
        _, assistant = _request(
            base,
            "POST",
            "/v2/assistant/sessions",
            {"inspection_id": inspection["id"], "analysis_id": analysis["id"]},
            {"Idempotency-Key": "http-assistant-session-0001"},
        )
        while assistant["state"] == "NEEDS_CLARIFICATION":
            question = assistant["question"]
            _, assistant = _request(
                base,
                "POST",
                f"/v2/assistant/sessions/{assistant['id']}/messages",
                {
                    "question_id": question["id"],
                    "option_id": question["options"][0]["id"],
                },
                {"Idempotency-Key": f"http-answer-{assistant['turn_count']}-0001"},
            )
        _, current = _request(
            base, "GET", f"/v2/inspections/{inspection['id']}"
        )
        status, confirmed = _request(
            base,
            "POST",
            f"/v2/inspections/{inspection['id']}/confirmation",
            {
                "classification": assistant["proposal"],
                "confirmation_source": "accepted",
            },
            {
                "Idempotency-Key": "http-confirm-inspection-0001",
                "If-Match": str(current["revision"]),
            },
        )
        assert status == 200
        assert confirmed["state"] in {"USER_CONFIRMED", "REVIEW_REQUIRED"}
    finally:
        server.should_exit = True
        thread.join(timeout=5)
