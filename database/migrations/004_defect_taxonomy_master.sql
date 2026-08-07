BEGIN;

CREATE TABLE IF NOT EXISTS apartment_ai.floorplan_room_master (
    id bigserial PRIMARY KEY,
    floorplan_id uuid NOT NULL REFERENCES apartment_ai.floorplans(id) ON DELETE CASCADE,
    room_code text NOT NULL,
    room_label text NOT NULL,
    x_norm numeric(7,6) NOT NULL,
    y_norm numeric(7,6) NOT NULL,
    bbox jsonb,
    clockwise_order integer NOT NULL DEFAULT 999,
    active boolean NOT NULL DEFAULT true,
    UNIQUE (floorplan_id, room_code)
);

CREATE TABLE IF NOT EXISTS apartment_ai.defect_taxonomy_master (
    id bigserial PRIMARY KEY,
    floorplan_type_code text NOT NULL,
    room_code text NOT NULL DEFAULT '*',
    surface_code text NOT NULL,
    detail_code text NOT NULL,
    detail_label text NOT NULL,
    cause_code text NOT NULL,
    cause_label text NOT NULL,
    trade_code text NOT NULL,
    trade_label text NOT NULL,
    sort_order integer NOT NULL DEFAULT 100,
    active boolean NOT NULL DEFAULT true,
    UNIQUE (floorplan_type_code, room_code, surface_code, detail_code, cause_code)
);

CREATE INDEX IF NOT EXISTS ix_room_master_floorplan_order
    ON apartment_ai.floorplan_room_master(floorplan_id, clockwise_order);
CREATE INDEX IF NOT EXISTS ix_taxonomy_master_lookup
    ON apartment_ai.defect_taxonomy_master(floorplan_type_code, room_code, surface_code, active, sort_order);

INSERT INTO apartment_ai.floorplan_room_master
    (floorplan_id, room_code, room_label, x_norm, y_norm, clockwise_order)
SELECT f.id,
       element->>'id',
       element->>'label',
       COALESCE((element->>'cx')::numeric, 0.5),
       COALESCE((element->>'cy')::numeric, 0.5),
       row_number() OVER (PARTITION BY f.id ORDER BY element->>'id')
FROM apartment_ai.floorplans f
CROSS JOIN LATERAL jsonb_array_elements(f.room_anchors) AS element
ON CONFLICT (floorplan_id, room_code) DO UPDATE SET
  room_label=EXCLUDED.room_label, x_norm=EXCLUDED.x_norm, y_norm=EXCLUDED.y_norm;

WITH details(surface_code, detail_code, detail_label, trade_code, trade_label, sort_order) AS (
 VALUES
 ('CEILING','CEILING_FINISH','천장 마감재','FINISH','마감공사',10),
 ('CEILING','WALLPAPER','도배지','WALLPAPER','도배공사',20),
 ('CEILING','PAINT','도장면','PAINT','도장공사',30),
 ('CEILING','LIGHT_ACCESS','조명·점검구','ELECTRIC','전기공사',40),
 ('CEILING','PIPE_TRACE','배관 흔적','PLUMBING','설비공사',50),
 ('CEILING_WALL','CEILING_MOLDING','천장 몰딩','FINISH','마감공사',10),
 ('CEILING_WALL','JOINT','천장-벽 접합부','FINISH','마감공사',20),
 ('CEILING_WALL','CORNER_WALLPAPER','코너 도배','WALLPAPER','도배공사',30),
 ('CEILING_WALL','SILICONE','실리콘','FINISH','마감공사',40),
 ('CEILING_WALL','CRACK','균열부','STRUCTURE','미장·구조공사',50),
 ('WALL','WALLPAPER','벽지','WALLPAPER','도배공사',10),
 ('WALL','PAINT','도장면','PAINT','도장공사',20),
 ('WALL','TILE','타일','TILE','타일공사',30),
 ('WALL','FRAME','문틀·창틀 주변','WINDOW','창호·목공사',40),
 ('WALL','OUTLET','콘센트·스위치','ELECTRIC','전기공사',50),
 ('WALL_FLOOR','SKIRTING','걸레받이','FINISH','마감공사',10),
 ('WALL_FLOOR','JOINT','벽-바닥 접합부','FINISH','마감공사',20),
 ('WALL_FLOOR','FLOOR_TILE','바닥 타일','TILE','타일공사',30),
 ('WALL_FLOOR','FLOOR_EDGE','마루 끝단','FLOOR','바닥공사',40),
 ('WALL_FLOOR','SILICONE','실리콘','FINISH','마감공사',50),
 ('FLOOR','FLOORING','마루·바닥재','FLOOR','바닥공사',10),
 ('FLOOR','TILE','바닥 타일','TILE','타일공사',20),
 ('FLOOR','THRESHOLD','문턱','FINISH','마감공사',30),
 ('FLOOR','DRAIN','배수구 주변','PLUMBING','설비공사',40),
 ('FLOOR','HEATING','난방·들뜸 부위','FLOOR','바닥공사',50)
), causes(cause_code, cause_label) AS (
 VALUES ('CONSTRUCTION','시공불량'),('MISSING','미시공'),('DAMAGE','파손(균열)'),('LEAK','누수'),('CONDENSATION','결로')
), plans AS (
 SELECT DISTINCT type_code FROM apartment_ai.floorplans WHERE active
)
INSERT INTO apartment_ai.defect_taxonomy_master
 (floorplan_type_code, room_code, surface_code, detail_code, detail_label, cause_code, cause_label, trade_code, trade_label, sort_order)
SELECT p.type_code, '*', d.surface_code, d.detail_code, d.detail_label, c.cause_code, c.cause_label, d.trade_code, d.trade_label, d.sort_order
FROM plans p CROSS JOIN details d CROSS JOIN causes c
ON CONFLICT (floorplan_type_code, room_code, surface_code, detail_code, cause_code) DO NOTHING;

INSERT INTO apartment_ai.schema_migrations(version) VALUES ('004_defect_taxonomy_master') ON CONFLICT DO NOTHING;
COMMIT;