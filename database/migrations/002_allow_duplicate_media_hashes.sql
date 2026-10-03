BEGIN;

-- The same physical image may legitimately be attached as evidence to more
-- than one defect or role (for example, a wide photo plus a memo reference).
-- Keep it searchable, but do not reject the second attachment.
ALTER TABLE apartment_ai.media
  DROP CONSTRAINT IF EXISTS media_sha256_size_bytes_key;

CREATE INDEX IF NOT EXISTS ix_media_sha256_size_bytes
  ON apartment_ai.media(sha256, size_bytes);

INSERT INTO apartment_ai.schema_migrations(version)
VALUES ('002_allow_duplicate_media_hashes')
ON CONFLICT (version) DO NOTHING;

COMMIT;
