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

## Verification

- Full suite: 55 tests passed.
- Command: `.\.venv\Scripts\python.exe -m pytest -q -p no:cacheprovider
  --basetemp=output\pytest-sprint-2-4-release`
- Existing Sprint 2-1 interfaces and tests remain intact.

## Current Boundary

The repository defines stable domain, execution, backend adapter, and evaluation
contracts. Trained weights are intentionally not included.
