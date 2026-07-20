# Serving Operations Runbook

## Readiness incident

1. Check `/health`; a healthy process with failing `/ready` indicates model
   readiness, not process failure.
2. Run `apartment-data vision-release-check` without `--strict` and inspect every
   failed check.
3. Confirm the configured model has exactly one production registry entry.
4. Confirm the package mount is readable by the non-root container user.

Never copy request payloads, base64 images, or internal filesystem paths into
incident logs.

## Registry corruption

`registry.json.bak` is atomically refreshed after each successful index write.
When the primary JSON cannot be decoded, reads restore the last valid backup.
If both files are corrupt, stop writers, preserve both files for offline
analysis, restore a known-good registry snapshot, and run a strict release check.

A `locks/registry.lock` older than the configured stale interval is recovered
automatically. Do not manually remove a fresh lock without confirming no writer
process is active.

## Model reload failure and rollback

If a newly promoted production model cannot load, the serving process retains
the previous healthy session for existing and subsequent default requests while
recording `model_reload_failure_count`. The registry still reflects the failed
promotion, so operators must explicitly roll back:

```powershell
apartment-data vision-promote-model `
  model-registry apartment-defect <previous-version> `
  --stage production --previous-production-stage archived
```

Verify `/ready`, active model metrics, and a release check after rollback.

## Checksum or dependency mismatch

- Checksum mismatch: quarantine the package; never repair checksums in place.
  Rebuild and register a new immutable version.
- Dependency warning: install the documented optional group and rerun the check.
- Compatibility failure: do not override it in production; select a matching
  deployment profile or rebuild the package.

## Resource pressure

Request timeout, concurrency, upload, batch byte/count, JSON depth, and image
pixel limits are configured through `ADA_*` variables. Rejections increment
metrics without retaining payloads. Increase limits only after memory and
latency testing.

Sessions retired during revision changes remain alive until graceful shutdown to
protect in-flight requests. Use SIGTERM and allow the process to finish its
shutdown hook; avoid SIGKILL except for unrecoverable process hangs.
