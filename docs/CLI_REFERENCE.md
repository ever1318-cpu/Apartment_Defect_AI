# CLI Reference

실행 형식은 `apartment-data <command> [arguments]`이다. 정확한 현재 옵션은
`apartment-data <command> --help`로 확인한다.

이 문서는 25개 public subcommand를 모두 다룬다.

## Data engineering

| 명령 | 위치 인자 | 주요 옵션 | 성공 결과 |
|---|---|---|---|
| `import-legacy` | input, output | `--skip-invalid` | canonical JSONL |
| `validate` | input | `--root`, `--require-files` | issue 출력, issue 시 exit 1 |
| `split` | input, output | `--train`, `--validation`, `--test`, `--seed` | group-safe split JSONL |
| `manifest` | input, output | `--version` | content-addressed manifest |

## Field data

| 명령 | 위치 인자 | 주요 옵션 |
|---|---|---|
| `vision-ingest-images` | source, output | `--source-batch`, `--operator`, `--device-metadata` |
| `vision-check-image-quality` | ingestion_directory, output | `--max-dimension`, `--min-dimension`, `--max-bytes` |
| `vision-find-duplicates` | ingestion_directory, output | `--similarity-threshold` |
| `vision-create-labeling-tasks` | ingestion_directory, output | 반복 `--task-type`, vocabulary/instructions version, assignee, priority |
| `vision-validate-annotations` | annotations, output | `--label-vocabulary` |
| `vision-build-dataset-version` | ingestion_directory, annotation_directory, output | `--version`, `--seed`, `--privacy-mode raw|masked` |

Ingestion은 일부 파일 실패 시 output batch와 `errors.jsonl`을 만들고 exit 1을 반환한다.
Quality fail과 annotation QA error도 exit 1이다.

## Inference와 evaluation

| 명령 | 설명 |
|---|---|
| `vision-validate input [--records]` | prediction JSONL 계약과 ID 검사 |
| `vision-predict input output` | manifest batch inference |
| `vision-predict-image image output` | 단일 이미지 inference |
| `vision-evaluate ground_truth predictions output` | classification/detection/severity 평가 |

Inference 공통 옵션은 `--backend`, `--model`, `--model-version`, 반복
`--provider`, `--deployment-profile`, `--fail-fast`이다. Batch는 `--root`,
`--errors`도 지원한다.

## Training과 export

| 명령 | 핵심 옵션 |
|---|---|
| `vision-build-training-dataset records annotations output` | `--dataset-version`, `--root`, `--tasks`, 반복 `--classification-task` |
| `vision-train spec run_directory` | `--backend reference|pytorch`, `--device auto|cpu|cuda` |
| `vision-export-onnx run_directory output` | `--checkpoint`, `--opset`, `--static-batch` |

## Package와 registry

| 명령 | 핵심 옵션 |
|---|---|
| `vision-package-model training_run output` | `--model-name`, `--model-version`, `--notes` |
| `vision-validate-model-package package` | `--output`, `--strict` |
| `vision-inspect-model-package package` | model manifest 출력 |
| `vision-register-model registry package` | name, version, `--stage`, notes |
| `vision-promote-model registry name version` | `--stage`, `--previous-production-stage` |
| `vision-list-models registry` | `--model-name` |

Stage는 `development`, `staging`, `production`, `archived` 중 하나다.

## Operations

| 명령 | 핵심 옵션 |
|---|---|
| `vision-serve` | `--registry`, `--model`, `--host`, `--port`, `--workers` |
| `vision-release-check` | registry, model, version, profile, output, `--strict` |

Release check fail은 항상 exit 1이다. Warning은 기본 exit 0, strict에서는 exit 1이다.
