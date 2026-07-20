# Apartment Defect AI

Reproducible data-engineering and framework-neutral Vision AI foundations for
apartment-defect image analysis.

## Development

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\.venv\Scripts\python.exe -m pytest
```

The `apartment-data` CLI supports legacy import, manifest validation, leakage-safe
splitting, version manifests, and serialized Vision AI prediction validation.

Batch inference accepts a manifest of `ImageRecord` JSON objects. Relative image
paths resolve from the manifest directory unless `--root` is supplied. The
dependency-free `reference` backend is the default:

```powershell
apartment-data vision-predict records.jsonl predictions.jsonl `
  --backend reference --root dataset/raw --errors inference-errors.jsonl
```

Run one image without creating a manifest:

```powershell
apartment-data vision-predict-image images/example.png prediction.jsonl `
  --backend reference --image-id example-001
```

Custom backend instances or zero-argument factories use
`--backend module:attribute`. Both commands verify that each file exists and that
its extension matches a JPEG, PNG, WebP, or TIFF signature. They write prediction
JSONL atomically, print a machine-readable summary, and return a non-zero exit code
when one or more images fail. Add `--fail-fast` to stop on the first error.

Batch failures do not prevent later images from running. Each failed image appears
in the prediction JSONL with `metadata.status` set to `error`, so the complete
output remains readable by `vision-validate`; detailed failures can also be
written with `--errors`.

## Vision AI boundary

`vision_ai.VisionPipeline` coordinates image-quality assessment, hierarchical
classification, defect detection, suppression, and severity assignment. Model
runtimes implement the `VisionBackend` protocol, keeping PyTorch, ONNX, or hosted
inference adapters outside the stable domain layer.

`CallableVisionBackend` adapts plain Python functions for tests and lightweight
integrations. `InferenceRunner` adds deterministic ordering, duplicate protection,
quality-rejection accounting, and optional record-level failure isolation without
requiring an installed model framework.

`ReferenceVisionBackend` derives deterministic classifications, detections, and
polygon masks from image bytes. It is intended for workflow verification rather
than defect accuracy. Every execution result records `backend_name`,
`model_version`, `duration_ms`, and `status` in prediction metadata.

## Production backends

Backends are created through `BackendRegistry`. The default registry contains
`reference` and `onnx`; `module:attribute` factories remain available for custom
integrations.

Install ONNX support separately so the base package stays framework-neutral:

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[onnx]"
```

Then select the registered backend and model from either inference command:

```powershell
apartment-data vision-predict records.jsonl predictions.jsonl `
  --backend onnx --model models/defect.onnx `
  --model-version defect-2026-07 --provider CPUExecutionProvider
```

The ONNX adapter expects one model input and these named outputs:

- `quality`: `[1, 1]`
- `space_scores`, `trade_scores`, `component_scores`: `[1, class_count]`
- `boxes`: `[1, detection_count, 4]`, normalized XYXY
- `detection_scores`, `detection_labels`: `[1, detection_count]`

Session creation is separate from inference and can be injected for testing or
runtime customization. `onnxruntime`, NumPy, and Pillow are imported only when
the default ONNX session or image loader is actually used.

## Vision evaluation

Ground truth is JSONL keyed by `image_id`. Each line contains task labels and
zero or more normalized XYXY detections:

```json
{"image_id":"image-1","dataset_version":"dataset-7","classifications":{"space":"bathroom"},"detections":[{"label":"crack","box":{"x_min":0.1,"y_min":0.1,"x_max":0.5,"y_max":0.5},"severity":"low"}]}
```

Generate an atomic JSON evaluation report:

```powershell
apartment-data vision-evaluate `
  ground-truth.jsonl predictions.jsonl evaluation-report.json `
  --iou-threshold 0.5 --confidence-threshold 0.25 `
  --dataset-version dataset-7
```

Classification evaluation selects the highest-confidence prediction at or above
the threshold for each task. If none remains, the prediction is recorded as
`__none__`. Reports include accuracy, macro precision/recall/F1, confusion
matrices, and label support with TP/FP/FN.

Detection evaluation filters by confidence and processes predictions from highest
confidence to lowest. A prediction matches the unmatched ground-truth box of the
same class with the highest IoU when that IoU is at least the configured
threshold. Each ground-truth box can match once. Remaining predictions are false
positives and remaining annotations are false negatives.

Severity is evaluated only for matched detection pairs where both sides provide
`low`, `medium`, or `high`. A pair missing either severity is excluded and counted
in `ignored_missing`. The report stores this policy, severity accuracy, macro
metrics, confusion matrix, and per-label metrics. Zero-denominator precision,
recall, and F1 values are reported as `0.0`.

Duplicate IDs are fatal report errors. Missing predictions, unknown prediction
IDs, mixed versions, and inference-error predictions are warnings; valid
`image_id` intersections are still evaluated.
