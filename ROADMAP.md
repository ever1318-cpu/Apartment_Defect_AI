# Roadmap

## Phase 1 — Data Engineering

- Sprint 1-6: canonical records, import, validation, leakage-safe splitting,
  dataset versioning, augmentation policy, and active-learning selection.

## Phase 2 — Vision AI

- Sprint 2-1 (complete): backend-neutral prediction contracts and pipeline.
- Sprint 2-2 (complete): validated real-image inference, deterministic reference
  backend, resilient batch runner, and CLI integration.
- Sprint 2-3 (complete): production backend registry and optional ONNX adapter.
- Sprint 2-4 (complete): classification, detection, and severity evaluation with
  machine-readable reports.
- Sprint 2-5 (complete): training dataset preparation, framework-neutral training
  specification, backend boundary, and deterministic run artifacts.
- Sprint 2-6 (complete): optional PyTorch training, reproducible checkpoints, and
  ONNX export compatible with the production inference adapter.
- Sprint 2-7 (planned): model packaging, compatibility manifests, and deployment
  profiles.

## Architectural Constraints

- Domain models and pipeline contracts remain independent of model frameworks.
- Serialized outputs stay deterministic and schema-compatible.
- Runtime adapters are optional integrations, not dependencies of the core package.
- New stages preserve compatibility with prior prediction payloads and tests.
