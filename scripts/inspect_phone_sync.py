"""Read-only diagnostic for the PinSet debug app's offline sync queue.

Run while a USB-debugging-authorized device is connected.  The database is
read directly into an in-memory SQLite connection; this script never writes
to the phone, server, or database.
"""

from __future__ import annotations

import os
import sqlite3
import subprocess
import sys
import tempfile
from pathlib import Path


PACKAGE = "com.axlife.pinset.debug"


def main() -> int:
    adb = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
    if not adb.is_file():
        print("ADB_NOT_FOUND")
        return 2
    with tempfile.TemporaryDirectory(prefix="pinset-sync-") as directory:
        local = Path(directory) / "pinset.db"
        try:
            for suffix in ("", "-wal", "-shm"):
                content = subprocess.check_output(
                    [str(adb), "exec-out", "run-as", PACKAGE, "cat", f"databases/pinset.db{suffix}"],
                    stderr=subprocess.STDOUT,
                )
                (Path(str(local) + suffix)).write_bytes(content)
        except subprocess.CalledProcessError as exc:
            print("PHONE_DB_UNAVAILABLE")
            print(exc.output.decode("utf-8", errors="replace").strip())
            return 2
        try:
            db = sqlite3.connect(f"file:{local.as_posix()}?mode=ro", uri=True)
            states = db.execute(
                "SELECT state, COUNT(*) FROM sync_queue GROUP BY state ORDER BY state"
            ).fetchall()
        except sqlite3.DatabaseError as exc:
            print("PHONE_DB_READ_ERROR=" + str(exc))
            return 2
        print("QUEUE_STATES=" + repr(states))
        rows = db.execute(
        """
        SELECT q.localDefectId, q.state, q.attemptCount, q.lastError,
               d.sessionId, d.defectIndex, d.roomLabel
        FROM sync_queue q JOIN defects d ON d.id=q.localDefectId
        WHERE q.state <> 'COMPLETED'
        ORDER BY q.updatedAt DESC
        LIMIT 20
        """
        ).fetchall()
        if not rows:
            print("INCOMPLETE=0")
            db.close()
            return 0
        print(f"INCOMPLETE={len(rows)}")
        for defect_id, state, attempts, error, session_id, index, room in rows:
            safe_error = (error or "").replace("\n", " ")[:240]
            print(
                f"DEFECT={defect_id} SESSION={session_id} INDEX={index} ROOM={room} "
                f"STATE={state} ATTEMPTS={attempts} ERROR={safe_error}"
            )
        db.close()
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
