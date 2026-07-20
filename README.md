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

## Training workflow

Build framework-neutral training inputs from split `ImageRecord` JSONL and the
ground-truth JSONL used by evaluation:

```powershell
apartment-data vision-build-training-dataset `
  records.jsonl annotations.jsonl training-dataset `
  --dataset-version dataset-7 --root dataset/raw `
  --tasks classification detection severity
```

Every record must have a train, validation, or test split. The builder validates
real image files, joins annotations by `image_id`, rejects duplicate/missing IDs
and group leakage, then learns vocabularies from the train split. Labels are
sorted lexically for stable zero-based indices. Labels formatted as `__name__`
are reserved, and validation/test labels absent from train fail under the explicit
`unknown_policy: error`.

The generated `training_spec.json` is runtime-neutral and contains dataset
version, enabled tasks, relative split and mapping locations, preprocessing,
augmentation, batch size, epochs, learning rate, random seed, output directory,
and model artifact name. Execute it without a GPU or ML framework:

```powershell
apartment-data vision-train `
  training-dataset/training_spec.json training-runs/run-001 `
  --backend reference
```

`TrainingBackend` separates `prepare`, `train`, `validate`, and `export`.
`ReferenceTrainingBackend` produces deterministic synthetic metric history for
workflow verification. A run directory is never overwritten and contains:

```text
training_spec.json
label_mapping.json
metric_history.json
final_metrics.json
model-artifact.json
model_metadata.json
run_manifest.json
```

Install the production training stack separately; importing `vision_ai` does not
import PyTorch, torchvision, Pillow, NumPy, ONNX, or download pretrained weights:

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[pytorch]"
```

The PyTorch backend uses a small three-layer CNN with task classification heads,
a single-box detection head, and a severity head. It supports CPU by default and
selects CUDA only when available:

```powershell
apartment-data vision-train `
  training-dataset/training_spec.json training-runs/run-002 `
  --backend pytorch --device cpu
```

`model.pt` is the latest epoch checkpoint. `best-model.pt` is selected by highest
validation accuracy, with lowest validation loss as the tie breaker. Runs also
record checkpoint, environment, duration, dependency/device, and export metadata.

ONNX export uses input `images` and the seven named outputs consumed by
`OnnxVisionBackend`: `quality`, `space_scores`, `trade_scores`,
`component_scores`, `boxes`, `detection_scores`, and `detection_labels`.
The default is opset 17 with a dynamic batch axis.

```powershell
apartment-data vision-export-onnx `
  training-runs/run-002 training-runs/run-002/model.onnx `
  --opset 17
```

The reference detection architecture predicts one normalized XYXY box per image
and uses the first ground-truth detection during training. It is a minimal
offline baseline, not a production detector.

The run manifest records the run ID, UTC creation time, backend, stage states,
artifact list, final metrics, and either `completed` or a structured `failed`
status.
