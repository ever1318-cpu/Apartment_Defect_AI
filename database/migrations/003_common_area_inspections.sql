BEGIN;

-- A common-area record remains outside the resident building/unit domain while
-- preserving the existing session/defect/media pipeline.  The dedicated
-- COMMON/0000 household is an internal technical owner only; its human-facing
-- inspection kind and location are stored on the session itself.
ALTER TABLE apartment_ai.inspection_sessions
    ADD COLUMN IF NOT EXISTS inspection_kind text NOT NULL DEFAULT 'HOUSEHOLD',
    ADD COLUMN IF NOT EXISTS common_area_label text;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_inspection_sessions_kind'
          AND conrelid = 'apartment_ai.inspection_sessions'::regclass
    ) THEN
        ALTER TABLE apartment_ai.inspection_sessions
            ADD CONSTRAINT ck_inspection_sessions_kind
            CHECK (inspection_kind IN ('HOUSEHOLD', 'COMMON_AREA'));
    END IF;
END $$;

INSERT INTO apartment_ai.buildings (id, complex_id, building_no)
SELECT
    '33333333-3333-4333-8333-333333333333'::uuid,
    c.id,
    'COMMON'
FROM apartment_ai.complexes c
WHERE c.code = 'ULSAN_DOWN'
ON CONFLICT (complex_id, building_no) DO NOTHING;

INSERT INTO apartment_ai.households
  (id, building_id, unit_no, floorplan_id, owner_display_name, external_ref)
SELECT
    '44444444-4444-4444-8444-444444444444'::uuid,
    b.id,
    '0000',
    f.id,
    '공용부·기타',
    'ULSAN_DOWN-COMMON-0000'
FROM apartment_ai.buildings b
JOIN apartment_ai.complexes c ON c.id = b.complex_id AND c.code = 'ULSAN_DOWN'
JOIN apartment_ai.floorplans f ON f.complex_id = c.id AND f.type_code = '84A-1' AND f.active
WHERE b.building_no = 'COMMON'
ON CONFLICT (building_id, unit_no) DO UPDATE
SET floorplan_id = EXCLUDED.floorplan_id,
    owner_display_name = EXCLUDED.owner_display_name,
    external_ref = EXCLUDED.external_ref;

CREATE INDEX IF NOT EXISTS ix_sessions_kind_state
    ON apartment_ai.inspection_sessions(inspection_kind, state);

COMMIT;
