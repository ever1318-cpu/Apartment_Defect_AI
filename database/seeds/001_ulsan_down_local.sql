BEGIN;

INSERT INTO apartment_ai.complexes
  (id, code, name, site_map_object_key)
VALUES
  ('11111111-1111-4111-8111-111111111111', 'ULSAN_DOWN', '울산다운지구',
   'reference/ulsan-down/site-map.png')
ON CONFLICT (code) DO UPDATE
SET name=EXCLUDED.name, site_map_object_key=EXCLUDED.site_map_object_key;

INSERT INTO apartment_ai.floorplans
  (id, complex_id, type_code, title, image_object_key, room_anchors, version)
VALUES
  (
    '22222222-2222-4222-8222-222222222201',
    '11111111-1111-4111-8111-111111111111',
    '84A-1', '84A-1', 'reference/ulsan-down/floorplan-84a-1.png',
    '[{"id":"living","label":"거실","cx":0.50,"cy":0.62},{"id":"kitchen","label":"주방","cx":0.49,"cy":0.21},{"id":"master","label":"안방","cx":0.22,"cy":0.58},{"id":"bed1","label":"침실1","cx":0.84,"cy":0.62},{"id":"bed2","label":"침실2","cx":0.67,"cy":0.62},{"id":"bath","label":"공용욕실","cx":0.88,"cy":0.37},{"id":"entry","label":"현관","cx":0.76,"cy":0.27}]'::jsonb,
    1
  ),
  (
    '22222222-2222-4222-8222-222222222202',
    '11111111-1111-4111-8111-111111111111',
    '84B-1', '84B-1', 'reference/ulsan-down/floorplan-84b-1.png',
    '[{"id":"living","label":"거실","cx":0.48,"cy":0.64},{"id":"kitchen","label":"주방","cx":0.49,"cy":0.20},{"id":"master","label":"안방","cx":0.18,"cy":0.58},{"id":"bed1","label":"침실1","cx":0.84,"cy":0.66},{"id":"bed2","label":"침실2","cx":0.66,"cy":0.66},{"id":"bath","label":"공용욕실","cx":0.88,"cy":0.38},{"id":"entry","label":"현관","cx":0.76,"cy":0.27}]'::jsonb,
    1
  )
ON CONFLICT (complex_id, type_code, version) DO UPDATE
SET title=EXCLUDED.title, image_object_key=EXCLUDED.image_object_key,
    room_anchors=EXCLUDED.room_anchors, active=true;

INSERT INTO apartment_ai.buildings
  (id, complex_id, building_no)
SELECT
  md5('ulsan-down-building-' || building_no)::uuid,
  '11111111-1111-4111-8111-111111111111',
  building_no
FROM generate_series(101, 120) AS source(building_no)
ON CONFLICT (complex_id, building_no) DO NOTHING;

INSERT INTO apartment_ai.households
  (id, building_id, unit_no, floorplan_id, owner_display_name, external_ref)
SELECT
  md5('ulsan-down-household-' || building_no || '-' || unit_no)::uuid,
  md5('ulsan-down-building-' || building_no)::uuid,
  unit_no,
  CASE WHEN line_no = 4
    THEN '22222222-2222-4222-8222-222222222202'::uuid
    ELSE '22222222-2222-4222-8222-222222222201'::uuid
  END,
  '점검매니저',
  'ULSAN_DOWN-' || building_no || '-' || unit_no
FROM (
  SELECT
    building_no::text,
    floor_no,
    line_no,
    (floor_no * 100 + line_no)::text AS unit_no
  FROM generate_series(101, 120) AS building(building_no)
  CROSS JOIN generate_series(1, 25) AS floor(floor_no)
  CROSS JOIN generate_series(1, 4) AS line(line_no)
) AS units
ON CONFLICT (building_id, unit_no) DO UPDATE
SET floorplan_id=EXCLUDED.floorplan_id,
    owner_display_name=EXCLUDED.owner_display_name,
    external_ref=EXCLUDED.external_ref;

COMMIT;
