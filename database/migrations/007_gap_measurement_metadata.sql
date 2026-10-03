-- Physical gap/clearance verification recorded by the field app.
ALTER TABLE apartment_ai.defects
    ADD COLUMN IF NOT EXISTS measured_gap_mm numeric(7,3),
    ADD COLUMN IF NOT EXISTS measurement_method text NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS measurement_status text NOT NULL DEFAULT '';

CREATE INDEX IF NOT EXISTS idx_defects_measured_gap
    ON apartment_ai.defects(measured_gap_mm)
    WHERE measured_gap_mm IS NOT NULL;
