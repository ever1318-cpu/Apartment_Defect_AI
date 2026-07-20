# Schema Index

현재 release에는 29개 Draft 2020-12 JSON Schema가 있다.

## Field data

| Schema | Title |
|---|---|
| [image_record.schema.json](../dataset/schemas/image_record.schema.json) | Canonical image record |
| [image_ingestion_manifest.schema.json](../dataset/schemas/image_ingestion_manifest.schema.json) | Field ingestion batch |
| [image_quality_report.schema.json](../dataset/schemas/image_quality_report.schema.json) | Image quality result |
| [duplicate_groups.schema.json](../dataset/schemas/duplicate_groups.schema.json) | Exact/near duplicate groups |
| [privacy_mask.schema.json](../dataset/schemas/privacy_mask.schema.json) | Privacy mask and provenance |
| [labeling_task.schema.json](../dataset/schemas/labeling_task.schema.json) | Labeling work item |
| [annotation_revision.schema.json](../dataset/schemas/annotation_revision.schema.json) | Annotation revision |
| [annotation_qa_report.schema.json](../dataset/schemas/annotation_qa_report.schema.json) | Annotation QA result |
| [dataset_version_manifest.schema.json](../dataset/schemas/dataset_version_manifest.schema.json) | Dataset lineage and split |

## Vision and training

| Schema | Title |
|---|---|
| [vision_prediction.schema.json](../dataset/schemas/vision_prediction.schema.json) | Vision prediction |
| [vision_ground_truth.schema.json](../dataset/schemas/vision_ground_truth.schema.json) | Ground truth annotation |
| [vision_evaluation_report.schema.json](../dataset/schemas/vision_evaluation_report.schema.json) | Evaluation metrics |
| [vision_label_mapping.schema.json](../dataset/schemas/vision_label_mapping.schema.json) | Stable label indices |
| [vision_training_spec.schema.json](../dataset/schemas/vision_training_spec.schema.json) | Framework-neutral training spec |
| [vision_training_dataset_manifest.schema.json](../dataset/schemas/vision_training_dataset_manifest.schema.json) | Training dataset |
| [vision_training_run_manifest.schema.json](../dataset/schemas/vision_training_run_manifest.schema.json) | Training run |

## Model delivery

| Schema | Title |
|---|---|
| [model_manifest.schema.json](../dataset/schemas/model_manifest.schema.json) | Model package manifest |
| [model_compatibility_manifest.schema.json](../dataset/schemas/model_compatibility_manifest.schema.json) | Runtime compatibility |
| [model_checksums.schema.json](../dataset/schemas/model_checksums.schema.json) | SHA-256 manifest |
| [deployment_profiles.schema.json](../dataset/schemas/deployment_profiles.schema.json) | CPU/GPU profiles |
| [model_package_validation.schema.json](../dataset/schemas/model_package_validation.schema.json) | Package validation |
| [model_registry.schema.json](../dataset/schemas/model_registry.schema.json) | Registry index |

## Serving and release

| Schema | Title |
|---|---|
| [serving_config.schema.json](../dataset/schemas/serving_config.schema.json) | Serving configuration |
| [prediction_request.schema.json](../dataset/schemas/prediction_request.schema.json) | Single request |
| [prediction_batch_request.schema.json](../dataset/schemas/prediction_batch_request.schema.json) | Batch request |
| [api_error.schema.json](../dataset/schemas/api_error.schema.json) | Sanitized error |
| [service_metrics.schema.json](../dataset/schemas/service_metrics.schema.json) | Metrics snapshot |
| [release_check_report.schema.json](../dataset/schemas/release_check_report.schema.json) | Release check |
| [release_manifest.schema.json](../dataset/schemas/release_manifest.schema.json) | Release provenance |

Schema semantics and compatibility policy are documented in
[JSON_SCHEMA_REFERENCE.md](JSON_SCHEMA_REFERENCE.md).
