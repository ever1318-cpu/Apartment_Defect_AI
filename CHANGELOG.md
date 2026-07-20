# Changelog

All notable changes to this project are documented in this file.

## [0.4.0] - 2026-07-20

### Added

- Callable backend adapter and dynamic `module:attribute` backend loading.
- Deterministic, file-backed reference backend requiring no external AI framework
  or model weights.
- JPEG, PNG, WebP, and TIFF existence, extension, and signature validation.
- Backend-neutral batch inference runner with deterministic summaries, duplicate
  protection, timing/backend metadata, quality-rejection accounting, fail-fast
  mode, and schema-compatible per-image error predictions.
- Manifest `vision-predict` and single-image `vision-predict-image` CLI workflows
  with atomic prediction and optional error JSONL output.
- Unit and integration coverage for backend loading, batch execution, and CLI
  inference.

## [0.3.0] - 2026-07-20

### Added

- Framework-neutral Vision AI contracts for quality, hierarchical classification,
  defect detection, polygon segmentation, and severity.
- Backend protocol and deterministic multi-stage inference orchestration.
- Confidence filtering, stable top-k selection, class-aware non-maximum
  suppression, and area-based severity assignment.
- Vision prediction validation against dataset image identifiers.
- JSON Schema and pipeline configuration for serialized Vision AI predictions.
- `vision-validate` CLI workflow.
- Unit and integration coverage for Vision AI models, post-processing, pipeline,
  serialization, and validation.

## [0.2.0] - 2026-07-20

### Added

- Canonical `ImageRecord` model and atomic JSONL input/output utilities.
- Legacy CSV import with deterministic identifiers and duplicate detection.
- Dataset manifest validation for IDs, files, extensions, and group leakage.
- Deterministic group-stratified train/validation/test splitting.
- SHA-256 content-addressed dataset version manifests.
- Entropy-based active-learning selection with group diversity limits.
- Serializable, deterministic image augmentation policies.
- Data pipeline configuration and JSON Schema for image records.
- Command-line workflows for import, validation, splitting, and versioning.
- Sprint 1–6 AI Data Engineering architecture documentation.
- Pytest coverage for splitter determinism, leakage, ratios, and invalid input.

### Fixed

- Global split scoring now evaluates label and size balance across every split,
  preventing non-zero target splits from being starved by local greedy scoring.
