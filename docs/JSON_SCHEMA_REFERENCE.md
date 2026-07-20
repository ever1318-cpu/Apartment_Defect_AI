# JSON Schema Reference

모든 schema는 `dataset/schemas`에 있으며 Draft 2020-12 JSON이다.

## Field data

| Schema | 대상 |
|---|---|
| `image_record.schema.json` | canonical image record |
| `image_ingestion_manifest.schema.json` | ingestion batch summary |
| `image_quality_report.schema.json` | 이미지별 quality 결과 |
| `duplicate_groups.schema.json` | exact/near duplicate group |
| `privacy_mask.schema.json` | privacy polygon과 provenance |
| `labeling_task.schema.json` | labeling work item |
| `annotation_revision.schema.json` | annotation revision와 audit |
| `annotation_qa_report.schema.json` | QA issue, distribution, agreement |
| `dataset_version_manifest.schema.json` | approved dataset version lineage |

## Vision, evaluation, training

| Schema | 대상 |
|---|---|
| `vision_prediction.schema.json` | inference output |
| `vision_ground_truth.schema.json` | evaluation/training annotation |
| `vision_evaluation_report.schema.json` | metric report |
| `vision_label_mapping.schema.json` | stable vocabulary index |
| `vision_training_spec.schema.json` | framework-neutral TrainingSpec |
| `vision_training_dataset_manifest.schema.json` | training dataset artifact |
| `vision_training_run_manifest.schema.json` | training run 상태와 artifact |

## Model delivery

| Schema | 대상 |
|---|---|
| `model_manifest.schema.json` | package identity와 model contract |
| `model_compatibility_manifest.schema.json` | runtime compatibility |
| `model_checksums.schema.json` | SHA-256 file map |
| `deployment_profiles.schema.json` | CPU/GPU execution profile |
| `model_package_validation.schema.json` | package 검사 결과 |
| `model_registry.schema.json` | revisioned registry index |

## Serving와 release

| Schema | 대상 |
|---|---|
| `serving_config.schema.json` | server limit와 cache 설정 |
| `prediction_request.schema.json` | 단일 prediction request |
| `prediction_batch_request.schema.json` | batch request |
| `api_error.schema.json` | sanitized API error |
| `service_metrics.schema.json` | metrics snapshot |
| `release_check_report.schema.json` | release 검사 결과 |
| `release_manifest.schema.json` | 배포 provenance |

## 정책

- Schema는 required field와 `additionalProperties` 정책을 명시한다.
- 새 producer는 기존 required field를 제거하거나 의미를 바꾸지 않는다.
- Format 변화가 호환되지 않으면 schema/format version을 올린다.
- JSONL 파일은 각 줄이 해당 item schema의 독립적인 JSON object다.
- JSON 구문 검사:

```powershell
python -c "import json,pathlib; [json.loads(p.read_text(encoding='utf-8-sig')) for p in pathlib.Path('dataset/schemas').glob('*.json')]"
```
