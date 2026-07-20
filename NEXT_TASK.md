# Next Task

## Sprint 2-4 — Evaluation and Calibration

Build backend-neutral evaluation around the stable `VisionPrediction` contract:

1. Define ground-truth/evaluation contracts without coupling to a model framework.
2. Add classification precision/recall/F1 and detection IoU-based metrics.
3. Report quality rejection, inference failure, and per-label coverage separately.
4. Add deterministic threshold-sweep utilities for confidence and NMS calibration.
5. Expose JSON evaluation reports through a CLI command.
6. Add unit and end-to-end tests using small synthetic fixtures.

Keep evaluation independent from trained weights and do not change existing
Sprint 2-1 through Sprint 2-3 interfaces or serialized prediction fields.
