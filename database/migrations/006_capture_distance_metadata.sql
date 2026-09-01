-- Persist camera subject distance and expose it through the field gallery.
ALTER TABLE apartment_ai.defects
    ADD COLUMN IF NOT EXISTS focus_distance_m numeric(8,3);

CREATE INDEX IF NOT EXISTS idx_defects_focus_distance
    ON apartment_ai.defects(focus_distance_m)
    WHERE focus_distance_m IS NOT NULL;
