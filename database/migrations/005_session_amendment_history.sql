-- Preserve completed inspections and mark later corrections as separate amendments.
ALTER TABLE apartment_ai.inspection_sessions
    ADD COLUMN IF NOT EXISTS revision_no integer NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS session_mode text NOT NULL DEFAULT 'INITIAL',
    ADD COLUMN IF NOT EXISTS amended_from_local_session_id bigint;

ALTER TABLE apartment_ai.inspection_sessions
    DROP CONSTRAINT IF EXISTS ck_inspection_sessions_mode;
ALTER TABLE apartment_ai.inspection_sessions
    ADD CONSTRAINT ck_inspection_sessions_mode
    CHECK (session_mode IN ('INITIAL', 'AMENDMENT'));

CREATE INDEX IF NOT EXISTS idx_inspection_sessions_household_revision
    ON apartment_ai.inspection_sessions(household_id, revision_no DESC, created_at DESC);
