# User Guide

## 1. 현장 데이터 수집

```powershell
apartment-data vision-ingest-images field-images dataset/ingestion/batch-001 `
  --source-batch batch-001 --operator field-team
apartment-data vision-check-image-quality dataset/ingestion/batch-001 `
  dataset/ingestion/batch-001/quality.jsonl
apartment-data vision-find-duplicates dataset/ingestion/batch-001 `
  dataset/ingestion/batch-001/duplicate_groups.json
```

실패 항목은 `errors.jsonl`에 격리된다. 원본은 변경되지 않는다.

## 2. 라벨링과 승인

```powershell
apartment-data vision-create-labeling-tasks dataset/ingestion/batch-001 `
  dataset/labeling/tasks.jsonl --task-type classification `
  --task-type detection --instructions-version 1.0 `
  --label-vocabulary-version 1.0
apartment-data vision-validate-annotations dataset/annotations/revisions.jsonl `
  dataset/annotations/qa-report.json
```

승인 annotation에는 reviewer가 필요하다. Masked dataset은 승인된 privacy derivative가
없는 이미지를 제외한다.

## 3. Dataset version과 학습

```powershell
apartment-data vision-build-dataset-version dataset/ingestion/batch-001 `
  dataset/annotations dataset/versions/dataset-001 `
  --version dataset-001 --seed 42
apartment-data vision-build-training-dataset `
  dataset/versions/dataset-001/records.jsonl `
  dataset/versions/dataset-001/annotations.jsonl training-dataset `
  --dataset-version dataset-001 --root dataset/versions/dataset-001
apartment-data vision-train training-dataset/training_spec.json `
  training-runs/run-001 --backend reference
```

실제 PyTorch 학습은 `--backend pytorch --device cpu` 또는 `cuda`를 사용한다.

## 4. 모델 package와 registry

```powershell
apartment-data vision-package-model training-runs/run-001 `
  model-packages/apartment-defect-1.0.0 `
  --model-name apartment-defect --model-version 1.0.0
apartment-data vision-validate-model-package `
  model-packages/apartment-defect-1.0.0 --strict
apartment-data vision-register-model model-registry `
  model-packages/apartment-defect-1.0.0 `
  --model-name apartment-defect --model-version 1.0.0
apartment-data vision-promote-model model-registry apartment-defect 1.0.0 `
  --stage production
```

## 5. 추론과 평가

```powershell
apartment-data vision-predict records.jsonl predictions.jsonl `
  --backend onnx --model model-packages/apartment-defect-1.0.0
apartment-data vision-evaluate annotations.jsonl predictions.jsonl report.json
```

## 6. Serving과 release check

```powershell
apartment-data vision-serve --registry model-registry `
  --model apartment-defect --host 127.0.0.1 --port 8000
apartment-data vision-release-check --registry model-registry `
  --model apartment-defect --version 1.0.0 --strict
```

운영 endpoint와 설정은 [SERVING_GUIDE.md](SERVING_GUIDE.md), 전체 명령은
[CLI_REFERENCE.md](CLI_REFERENCE.md)를 참고한다.
