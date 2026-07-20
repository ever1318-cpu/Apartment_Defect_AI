# Changelog

All notable changes to this project are documented in this file.

## [0.8.0] - 2026-07-20

### Added

- Optional, lazily imported PyTorch training backend with CPU/CUDA selection and
  no pretrained downloads.
- Independently testable split loader, DataLoader construction, and validation.
- Small multi-task CNN, reproducible loop, finite-loss checks, latest/best
  checkpoints, and environment metadata.
- Configurable ONNX export with the named `OnnxVisionBackend` output contract.
- PyTorch training/device and standalone ONNX export CLI workflows.

## [0.7.0] - 2026-07-20

### Added

- Leakage-safe training dataset builder joining split records and annotations.
- Stable task vocabularies with JSON round trips and strict unknown/reserved label
  policies.
- Framework-neutral `TrainingSpec` covering tasks, data locations, preprocessing,
  augmentation, hyperparameters, seed, output, and artifact naming.
- `TrainingBackend` prepare/train/validate/export boundary and deterministic
  reference implementation.
- Collision-safe training runner with success/failure manifests, metric history,
  final metrics, model metadata, and exported artifact.
- Training dataset/run schemas and `vision-build-training-dataset` plus
  `vision-train` CLI workflows.
- Unit and end-to-end tests for validation, determinism, artifacts, failures, and
  CLI execution.

## [0.6.0] - 2026-07-20

### Added

- Ground-truth annotation and JSON evaluation report contracts.
- Task-level classification accuracy, macro precision/recall/F1, confusion
  matrices, thresholds, and per-label support/count metrics.
- Class-aware greedy IoU detection matching with aggregate and per-label metrics.
- Matched-detection severity evaluation with an explicit missing-severity policy.
- Fatal input errors and partial-evaluation warnings in serialized reports.
- Atomic `vision-evaluate` CLI workflow and ground-truth/report JSON schemas.
- Unit and end-to-end coverage for exact, incorrect, missing, duplicate,
  thresholded, IoU, class mismatch, severity, round-trip, and zero-denominator
  cases.

## [0.5.0] - 2026-07-20

### Added

- Named backend registry/factory with built-in `reference` and `onnx` adapters.
- Optional ONNX Runtime adapter with model validation, lazy dependencies, separate
  session creation, injectable preprocessing, and cached per-image inference.
- Strict ONNX output shape and label validation.
- CLI model, model-version, and execution-provider selection.
- Fake Session tests covering registry errors, missing models, session separation,
  successful parsing, caching, and invalid output shapes.

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
