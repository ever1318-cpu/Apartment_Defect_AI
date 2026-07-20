# Changelog

All notable changes to this project are documented in this file.

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
