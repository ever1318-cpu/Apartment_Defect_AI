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
