# Current Status

Last updated: 2026-07-20

## Completed

- Sprint 1-6: reproducible AI data-engineering pipeline.
- Sprint 2-1: framework-neutral Vision AI contracts, orchestration, validation,
  post-processing, schema, and tests.
- Sprint 2-2: backend-neutral batch inference execution.
  - Callable backend adapter and dynamic backend loading.
  - Deterministic reference backend for real image files.
  - Basic JPEG, PNG, WebP, and TIFF file validation.
  - Manifest batch and single-image inference.
  - Timing/backend/status metadata, schema-compatible error predictions, and
    optional fail-fast execution.
  - Atomic prediction/error JSONL output through inference CLI commands.
- Sprint 2-3: production backend adapters.
  - Registry/factory selection with reference backend compatibility.
  - Optional ONNX Runtime backend with lazy imports.
  - Model path validation and separately injectable session creation.
  - CLI model/version/provider configuration and strict output contract checks.
- Sprint 2-4: Vision evaluation and reporting.
  - Ground-truth/prediction joining with fatal errors and partial-data warnings.
  - Classification, class-aware IoU detection, and severity metrics.
  - JSON evaluation reports with versions, thresholds, confusion matrices, and
    per-label counts.
  - Atomic `vision-evaluate` CLI workflow.
- Sprint 2-5: training workflow foundation.
  - Split-aware training dataset builder and label distribution statistics.
  - Stable classification/detection/severity vocabularies.
  - Framework-neutral training specification and backend protocol.
  - Deterministic reference training with collision-safe run artifacts.
  - Dataset build and training CLI workflows.
- Sprint 2-6: optional PyTorch training and ONNX export.
  - Lazy optional dependencies and explicit CPU/CUDA device selection.
  - Validated split loader and independently testable DataLoader boundary.
  - Offline small CNN with classification, one-box detection, and severity heads.
  - Reproducible training, latest/best checkpoints, and runtime metadata.
  - Seven-output ONNX contract compatible with `OnnxVisionBackend`.
- Sprint 2-7: model packaging and deployment profiles.
  - Atomic portable package construction from completed training runs.
  - Model/compatibility manifests and deterministic SHA-256 verification.
  - Path traversal, symbolic-link, missing, mismatch, and strict-extra checks.
  - CPU/GPU profiles with pass, warning, and fail compatibility results.
  - Validated package-directory loading by the ONNX inference adapter.
- Sprint 2-8: model registry and serving operations.
  - Revisioned local registry with immutable package copies and stage policy.
  - Optional FastAPI factory with health/readiness/model/prediction/metrics APIs.
  - Revision-aware session lifecycle and optional inference result cache.
  - Thread-safe operational metrics and sanitized request-scoped errors/logging.
  - Non-root CPU Docker foundation and Python matrix CI workflow.
- Sprint 2-9: integration and release readiness hardening.
  - Optional dependency matrix and marker-separated integration smoke paths.
  - Registry backup recovery, stale-lock timeout, and rollback-safe mutations.
  - Serving concurrency/resource defenses and safe reload fallback.
  - Expanded operational metrics and release-check/manifest workflow.
  - Hardened Docker/CI policies and incident/rollback runbook.
- Sprint 3-1: field data ingestion and labeling operations.
  - Content-addressed, relative-path-only image batch ingestion and error ledger.
  - Quality, duplicate, privacy-mask, labeling task, and annotation QA workflows.
  - Approved-only deterministic dataset versions with lineage and training inputs.

## Verification

- Full suite: 107 passed, 7 skipped.
- Core marker: 106 passed, 3 skipped, 5 deselected.
- Docker marker: 1 passed; serving/ONNX/training markers skip locally because
  their optional dependency groups are not installed.
- Command: `.\.venv\Scripts\python.exe -m pytest -q
  --basetemp=output\pytest-s31-final`
- Existing Sprint 2-1 interfaces and tests remain intact.

## Current Boundary

The repository now connects governed field-data intake to the existing training,
packaging, registry, and serving lifecycle. Automatic privacy detection, remote
labeling-platform synchronization, authentication, and durable telemetry remain
out of scope.
