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

## Verification

- Full suite: 33 tests passed.
- Command: `.\.venv\Scripts\python.exe -m pytest -q -p no:cacheprovider
  --basetemp=output\pytest-sprint-2-2-full`
- Existing Sprint 2-1 interfaces and tests remain intact.

## Current Boundary

The repository defines stable domain and execution contracts. Framework-specific
PyTorch, ONNX, or hosted model adapters and trained weights are intentionally not
included.
