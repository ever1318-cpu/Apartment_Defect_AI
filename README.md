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

## Vision AI boundary

`vision_ai.VisionPipeline` coordinates image-quality assessment, hierarchical
classification, defect detection, suppression, and severity assignment. Model
runtimes implement the `VisionBackend` protocol, keeping PyTorch, ONNX, or hosted
inference adapters outside the stable domain layer.
