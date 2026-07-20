# Apartment Defect AI

Reproducible data-engineering and framework-neutral Vision AI foundations for
apartment-defect image analysis.

Release documentation:

- [Architecture](docs/Architecture.md)
- [Developer Guide](docs/DeveloperGuide.md)
- [User Guide](docs/UserGuide.md)
- [CLI Reference](docs/CLI_REFERENCE.md)
- [API Reference](docs/API_REFERENCE.md)
- [JSON Schema Reference](docs/JSON_SCHEMA_REFERENCE.md)
- [Model Package Specification](docs/MODEL_PACKAGE_SPEC.md)
- [Registry Specification](docs/REGISTRY_SPEC.md)
- [Serving Guide](docs/SERVING_GUIDE.md)
- [Testing Guide](docs/TESTING_GUIDE.md)
- [Release Notes](docs/RELEASE_NOTES.md)
- [Standalone OpenAPI](docs/OpenAPI.html)
- [Schema Index](docs/SCHEMA_INDEX.md)
- [Architecture Diagram](docs/ARCHITECTURE_DIAGRAM.md)
- [Dependency Graph](docs/DEPENDENCY_GRAPH.md)
- [Coverage Report](docs/COVERAGE_REPORT.md)

## Development

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
.\.venv\Scripts\python.exe -m pytest
```

Install only the environment being validated:

```powershell
# Core development and tests
.\.venv\Scripts\python.exe -m pip install -e ".[test]"

# FastAPI serving
.\.venv\Scripts\python.exe -m pip install -e ".[test,serving]"

# ONNX model generation and CPU runtime
.\.venv\Scripts\python.exe -m pip install -e ".[test,onnx]"

# PyTorch training and ONNX export
.\.venv\Scripts\python.exe -m pip install -e ".[test,pytorch]"

# Complete development matrix
.\.venv\Scripts\python.exe -m pip install -e ".[full]"
```

The core package imports without any ML or web framework. Optional modules are
loaded only when their backend, exporter, or application factory is used.

The `apartment-data` CLI supports legacy import, manifest validation, leakage-safe
splitting, version manifests, and serialized Vision AI prediction validation.

## Field data workflow

Field images enter a content-addressed batch without modifying the source folder:

```powershell
apartment-data vision-ingest-images field-images dataset/ingestion/batch-001 `
  --source-batch batch-001 --operator field-team
apartment-data vision-check-image-quality dataset/ingestion/batch-001 `
  dataset/ingestion/batch-001/quality.jsonl
apartment-data vision-find-duplicates dataset/ingestion/batch-001 `
  dataset/ingestion/batch-001/duplicate_groups.json
```

The ingestion manifest stores only safe relative paths, SHA-256 content IDs,
source batch, operator, device metadata, and UTC time. Invalid, corrupt,
duplicate, traversal, and symbolic-link inputs are isolated in `errors.jsonl`.
Quality results are `pass`, `warning`, or `fail` and cover dimensions, bytes,
aspect ratio, blur, brightness, contrast, exposure, corruption, and encoding.
Exact duplicates use SHA-256; near duplicates use perceptual hashing when Pillow
is available and a deterministic dependency-free fingerprint otherwise.

Create labeling work and validate reviewed revisions:

```powershell
apartment-data vision-create-labeling-tasks dataset/ingestion/batch-001 `
  dataset/labeling/tasks.jsonl --task-type classification `
  --task-type detection --task-type privacy_mask_review `
  --instructions-version 1.0 --label-vocabulary-version 1.0
apartment-data vision-validate-annotations dataset/annotations/revisions.jsonl `
  dataset/annotations/qa-report.json
```

Privacy masks record normalized polygons, category, author, provenance, review
status, and derivative path. Sources are never overwritten. Actual raster
redaction uses lazy Pillow or an injected offline transformer; automatic
detection remains an optional backend.

Build an approved, duplicate-free and group-leakage-safe dataset:

```powershell
apartment-data vision-build-dataset-version dataset/ingestion/batch-001 `
  dataset/annotations dataset/versions/dataset-001 `
  --version dataset-001 --seed 42 --privacy-mode raw
```

The export contains copied training images, `records.jsonl`,
`annotations.jsonl`, and `dataset_version_manifest.json`. The manifest records
source-batch lineage, included/excluded reasons, deterministic splits, label and
quality distributions, and privacy mode. See [LABELING_GUIDE.md](LABELING_GUIDE.md)
and [DATA_GOVERNANCE.md](DATA_GOVERNANCE.md).

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

## Model packaging and deployment profiles

Create a portable deployment package from a completed PyTorch training run:

```powershell
apartment-data vision-package-model `
  training-runs/run-002 model-packages/defect-model-1.0.0 `
  --model-name apartment-defect --model-version 1.0.0
```

The package contains only relative references:

```text
model.onnx
model_manifest.json
compatibility_manifest.json
checksums.json
label_mapping.json
preprocessing.json
deployment_profiles.json
README.txt
```

All eight files are required. `checksums.json` records stable, lexically sorted
SHA-256 values for every other file and excludes itself. Missing files and
checksum mismatches always fail validation. Untracked files warn normally and
fail with `--strict`; symbolic links, nested/path-traversal references, and
non-regular entries are rejected.

```powershell
apartment-data vision-validate-model-package `
  model-packages/defect-model-1.0.0 --strict

apartment-data vision-inspect-model-package `
  model-packages/defect-model-1.0.0
```

Compatibility checks report `pass`, `warning`, or `fail`. Unsupported Python,
CPU architecture, execution providers, required CUDA, and an old ONNX Runtime
are failures. An untested operating system or unavailable ONNX Runtime version
is a warning. Warnings preserve a successful exit code.

The default `cpu` profile selects `CPUExecutionProvider` and records thread,
optimization, and memory-arena settings. The `gpu` profile prefers
`CUDAExecutionProvider`, permits configurable CPU fallback, and records generic
device/memory/arena/convolution settings without binding the package to a
specific machine.

Package directories can be passed anywhere a raw ONNX path was accepted:

```powershell
apartment-data vision-predict records.jsonl predictions.jsonl `
  --backend onnx --model model-packages/defect-model-1.0.0 `
  --deployment-profile cpu
```

Package validation runs before session creation. Model version, labels,
preprocessing resize, and providers are loaded from the package. Raw `.onnx`
paths remain supported with the previous CLI options.

## Model registry and serving

The local registry owns immutable copies of validated packages under
`models/<model-name>/<version>`. Package references are never external absolute
paths. Register, promote, and inspect models with JSON-emitting commands:

```powershell
apartment-data vision-register-model `
  model-registry model-packages/defect-model-1.0.0 `
  --model-name apartment-defect --model-version 1.0.0 `
  --stage development

apartment-data vision-promote-model `
  model-registry apartment-defect 1.0.0 --stage production

apartment-data vision-list-models model-registry
```

`registry.json` uses an atomic revision counter plus an exclusive writer lock.
Callers may provide an expected revision through the Python API to detect stale
writes. Promoting a version to production moves the previous production version
to staging by default; `--previous-production-stage archived` changes that
policy.

Install serving dependencies separately:

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[serving,onnx]"

apartment-data vision-serve `
  --registry model-registry --model apartment-defect `
  --host 0.0.0.0 --port 8000
```

Endpoints:

- `GET /health`, `GET /ready`
- `GET /v1/models`, `/v1/models/{name}`, `/v1/models/{name}/{version}`
- `POST /v1/predict`, `POST /v1/predict/batch`
- `GET /v1/metrics`

The JSON prediction API accepts base64 without persisting request payloads:

```json
{
  "image_base64": "iVBORw0KGgo...",
  "mime_type": "image/png",
  "image_id": "inspection-001",
  "model_name": "apartment-defect",
  "confidence_threshold": 0.25
}
```

Responses use the existing `VisionPrediction` schema. API failures use
`{"error":{"code":"...","message":"...","details":{},"request_id":"..."}}`;
backend exceptions and stack traces are not returned to clients.

Production package sessions are loaded lazily and held in an LRU active cache.
Registry revision changes atomically replace the active cache; retired sessions
remain alive until shutdown so in-flight requests are not interrupted. The
optional SHA-256 inference cache is disabled by default, stores only copied
prediction JSON, never caches errors, and substitutes the current `image_id`.

In-memory thread-safe metrics include request/image/success/error/batch/model-load
and cache counters, duration count/sum/min/max, per-model and per-error counts,
start time, and uptime. Image bytes and base64 payloads are never logged or
included in metrics.

Run the CPU container with a pre-populated registry mount:

```powershell
docker build -t apartment-defect-serving .
docker run --rm -p 8000:8000 `
  -e ADA_MODEL=apartment-defect `
  -v ${PWD}/model-registry:/var/lib/apartment-defect-ai/registry `
  apartment-defect-serving
```

The image uses a non-root user, performs `/ready` health checks, downloads no
models, and obtains runtime settings from `ADA_*` environment variables. Upload
size, MIME type, batch size, cache limits, and temporary directory are bounded by
`ServingConfig`; request bodies are not retained.

For a read-only root filesystem, provide only the registry and temporary
directories as writable mounts:

```powershell
docker run --read-only --tmpfs /tmp/apartment-defect-ai:rw,noexec,nosuid `
  -v ${PWD}/model-registry:/var/lib/apartment-defect-ai/registry `
  -e ADA_MODEL=apartment-defect -p 8000:8000 apartment-defect-serving
```

Serving additionally limits request wait time, model-load duration, concurrent
requests, total batch bytes, JSON nesting depth, and decoded image dimensions.
PNG MIME and magic bytes, structural header, and dimensions are checked before
inference. Registry reload failure keeps the previous healthy session active.

Run release validation before deployment:

```powershell
apartment-data vision-release-check `
  --registry model-registry `
  --model apartment-defect `
  --version 1.0.0 `
  --output release-check
```

The command writes `release_check_report.json` and `release_manifest.json`.
Failures always return non-zero. Warnings return success normally and non-zero
with `--strict`. The manifest records application/Git/model/dataset/schema and
dependency versions, registry revision, checksum digest, target profile, and
known limitations.

Test commands:

```powershell
# Entire suite; optional tests skip with explicit reasons when dependencies lack
.\.venv\Scripts\python.exe -m pytest -q

# Fast core validation
.\.venv\Scripts\python.exe -m pytest -q `
  -m "not serving and not onnx and not training and not docker"

# Installed optional environments
.\.venv\Scripts\python.exe -m pytest -q -m serving
.\.venv\Scripts\python.exe -m pytest -q -m onnx
.\.venv\Scripts\python.exe -m pytest -q -m training
```

Operational recovery and rollback procedures are documented in
[`OPERATIONS.md`](OPERATIONS.md).

The run manifest records the run ID, UTC creation time, backend, stage states,
artifact list, final metrics, and either `completed` or a structured `failed`
status.
