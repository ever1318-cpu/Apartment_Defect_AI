# Roadmap

## Phase 1 — Data Engineering

- Sprint 1-6: canonical records, import, validation, leakage-safe splitting,
  dataset versioning, augmentation policy, and active-learning selection.

## Phase 2 — Vision AI

- Sprint 2-1 (complete): backend-neutral prediction contracts and pipeline.
- Sprint 2-2 (complete): reproducible batch inference runner and CLI integration.
- Sprint 2-3 (recommended): evaluation metrics and threshold calibration.
- Sprint 2-4 (planned): optional production runtime adapters and model packaging.

## Architectural Constraints

- Domain models and pipeline contracts remain independent of model frameworks.
- Serialized outputs stay deterministic and schema-compatible.
- Runtime adapters are optional integrations, not dependencies of the core package.
- New stages preserve compatibility with prior prediction payloads and tests.
