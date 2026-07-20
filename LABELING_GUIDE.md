# Labeling Guide

## Work units

Every task is keyed by `image_id` and a task type: classification, detection,
segmentation, severity, or privacy-mask review. Workers move tasks through
`pending`, `in_progress`, and `submitted`; reviewers alone set `approved` or
`rejected`. Instructions and vocabulary versions are immutable task metadata.

## Annotation policy

- Use normalized XYXY boxes and normalized polygon coordinates.
- Apply hierarchical `space`, `trade`, and `component` labels from the approved
  vocabulary.
- Assign `low`, `medium`, or `high` severity to every defect detection.
- Mark faces, license plates, documents, and name tags as privacy regions.
- Record annotator, confidence, notes, revision, timestamps, and each audit action.
- Never edit an earlier revision; submit a new increasing revision.

## Review and QA

Approval requires a named reviewer. QA rejects unknown labels, invalid severity,
self-intersecting polygons, and duplicate revisions. Empty and duplicate
annotations are warnings requiring reviewer attention. Agreement compares
independent annotators on shared images; label distributions are reviewed
between dataset versions for drift.
