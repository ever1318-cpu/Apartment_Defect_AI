BEGIN;

CREATE SCHEMA IF NOT EXISTS apartment_ai;

CREATE TABLE IF NOT EXISTS apartment_ai.schema_migrations (
    version text PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS apartment_ai.complexes (
    id uuid PRIMARY KEY,
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    site_map_object_key text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS apartment_ai.buildings (
    id uuid PRIMARY KEY,
    complex_id uuid NOT NULL REFERENCES apartment_ai.complexes(id),
    building_no text NOT NULL,
    map_x_norm numeric(7,6),
    map_y_norm numeric(7,6),
    UNIQUE (complex_id, building_no),
    CHECK (map_x_norm IS NULL OR map_x_norm BETWEEN 0 AND 1),
    CHECK (map_y_norm IS NULL OR map_y_norm BETWEEN 0 AND 1)
);

CREATE TABLE IF NOT EXISTS apartment_ai.floorplans (
    id uuid PRIMARY KEY,
    complex_id uuid NOT NULL REFERENCES apartment_ai.complexes(id),
    type_code text NOT NULL,
    title text NOT NULL,
    image_object_key text NOT NULL,
    room_anchors jsonb NOT NULL DEFAULT '[]'::jsonb,
    version integer NOT NULL DEFAULT 1,
    active boolean NOT NULL DEFAULT true,
    UNIQUE (complex_id, type_code, version)
);

CREATE TABLE IF NOT EXISTS apartment_ai.households (
    id uuid PRIMARY KEY,
    building_id uuid NOT NULL REFERENCES apartment_ai.buildings(id),
    unit_no text NOT NULL,
    floorplan_id uuid NOT NULL REFERENCES apartment_ai.floorplans(id),
    owner_display_name text NOT NULL DEFAULT '점검매니저',
    external_ref text,
    UNIQUE (building_id, unit_no)
);

CREATE TABLE IF NOT EXISTS apartment_ai.inspection_sessions (
    id uuid PRIMARY KEY,
    client_uuid uuid NOT NULL UNIQUE,
    household_id uuid NOT NULL REFERENCES apartment_ai.households(id),
    inspector_id text NOT NULL,
    state text NOT NULL DEFAULT 'DRAFT',
    anchor_room_code text,
    anchor_room_label text,
    anchor_x_norm numeric(7,6),
    anchor_y_norm numeric(7,6),
    anchor_heading_deg numeric(7,2),
    revision integer NOT NULL DEFAULT 1,
    started_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (state IN ('DRAFT','ANCHOR_REQUIRED','ACTIVE','COMPLETED','CANCELLED')),
    CHECK (anchor_x_norm IS NULL OR anchor_x_norm BETWEEN 0 AND 1),
    CHECK (anchor_y_norm IS NULL OR anchor_y_norm BETWEEN 0 AND 1)
);

CREATE TABLE IF NOT EXISTS apartment_ai.defects (
    id uuid PRIMARY KEY,
    client_uuid uuid NOT NULL UNIQUE,
    session_id uuid NOT NULL REFERENCES apartment_ai.inspection_sessions(id) ON DELETE CASCADE,
    defect_index integer NOT NULL,
    state text NOT NULL DEFAULT 'CAPTURED',
    room_code text,
    room_label text NOT NULL,
    x_norm numeric(7,6) NOT NULL,
    y_norm numeric(7,6) NOT NULL,
    surface_code text,
    raw_resident_opinion text NOT NULL DEFAULT '',
    standardized_opinion text NOT NULL DEFAULT '',
    final_location_code text,
    final_part_code text,
    final_part_detail_code text,
    final_work_kind_code text,
    final_cause_code text,
    priority_code text,
    taxonomy_version text NOT NULL,
    revision integer NOT NULL DEFAULT 1,
    review_required boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (session_id, defect_index),
    CHECK (state IN ('CAPTURED','ANALYSIS_PENDING','NEEDS_CLARIFICATION','PROPOSED','USER_CONFIRMED','USER_CORRECTED','REVIEW_REQUIRED','DELETED')),
    CHECK (x_norm BETWEEN 0 AND 1),
    CHECK (y_norm BETWEEN 0 AND 1)
);

CREATE TABLE IF NOT EXISTS apartment_ai.media (
    id uuid PRIMARY KEY,
    client_uuid uuid NOT NULL UNIQUE,
    session_id uuid NOT NULL REFERENCES apartment_ai.inspection_sessions(id) ON DELETE CASCADE,
    defect_id uuid REFERENCES apartment_ai.defects(id) ON DELETE CASCADE,
    role text NOT NULL,
    mime_type text NOT NULL,
    size_bytes bigint NOT NULL,
    sha256 char(64) NOT NULL,
    object_key text NOT NULL,
    upload_state text NOT NULL DEFAULT 'PENDING',
    captured_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sha256, size_bytes),
    CHECK (role IN ('ANCHOR_NEAR','ANCHOR_FAR','WIDE','CLOSE','EXTRA')),
    CHECK (upload_state IN ('PENDING','UPLOADED','VERIFIED','FAILED')),
    CHECK (size_bytes > 0)
);

CREATE TABLE IF NOT EXISTS apartment_ai.ai_predictions (
    id uuid PRIMARY KEY,
    defect_id uuid NOT NULL REFERENCES apartment_ai.defects(id) ON DELETE CASCADE,
    source_type text NOT NULL,
    model_name text NOT NULL,
    model_version text NOT NULL,
    preprocessing_version text,
    taxonomy_version text NOT NULL,
    candidates jsonb NOT NULL,
    quality jsonb,
    contamination_flags jsonb NOT NULL DEFAULT '[]'::jsonb,
    confidence numeric(6,5),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (source_type IN ('IMAGE','TEXT_LLM','FUSION'))
);

CREATE TABLE IF NOT EXISTS apartment_ai.opinion_events (
    id uuid PRIMARY KEY,
    defect_id uuid NOT NULL REFERENCES apartment_ai.defects(id) ON DELETE CASCADE,
    sequence_no integer NOT NULL,
    actor_type text NOT NULL,
    raw_text text NOT NULL,
    normalized_text text,
    llm_metadata jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (defect_id, sequence_no),
    CHECK (actor_type IN ('RESIDENT','INSPECTOR','AI_ASSISTANT'))
);

CREATE TABLE IF NOT EXISTS apartment_ai.idempotency_records (
    scope text NOT NULL,
    idempotency_key text NOT NULL,
    request_hash char(64) NOT NULL,
    response_status integer NOT NULL,
    response_body jsonb NOT NULL,
    resource_id uuid,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, idempotency_key)
);

CREATE TABLE IF NOT EXISTS apartment_ai.sync_operations (
    operation_id uuid PRIMARY KEY,
    device_id text NOT NULL,
    entity_type text NOT NULL,
    entity_id uuid NOT NULL,
    base_revision integer NOT NULL,
    state text NOT NULL,
    payload jsonb NOT NULL,
    server_revision integer,
    error_code text,
    received_at timestamptz NOT NULL DEFAULT now(),
    applied_at timestamptz,
    CHECK (state IN ('RECEIVED','APPLIED','CONFLICT','REJECTED'))
);

CREATE INDEX IF NOT EXISTS ix_sessions_household_state
    ON apartment_ai.inspection_sessions(household_id, state);
CREATE INDEX IF NOT EXISTS ix_defects_session_created
    ON apartment_ai.defects(session_id, created_at);
CREATE INDEX IF NOT EXISTS ix_media_defect
    ON apartment_ai.media(defect_id);
CREATE INDEX IF NOT EXISTS ix_predictions_defect_created
    ON apartment_ai.ai_predictions(defect_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_sync_device_received
    ON apartment_ai.sync_operations(device_id, received_at DESC);

INSERT INTO apartment_ai.schema_migrations(version)
VALUES ('001_apartment_ai_core')
ON CONFLICT (version) DO NOTHING;

COMMIT;
