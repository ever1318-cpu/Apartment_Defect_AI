# Architecture

Apartment Defect AI는 현장 이미지 수집부터 모델 배포까지를 독립적인 계층으로 분리한
framework-neutral Vision AI 시스템이다.

## 전체 흐름

```text
현장 이미지
  → ingestion / quality / duplicate / privacy
  → labeling task / annotation revision / QA
  → dataset version / training dataset
  → TrainingBackend
  → checkpoint / ONNX export
  → model package / checksum / compatibility
  → model registry
  → VisionBackend / inference / evaluation
  → FastAPI serving / metrics / release check
```

## 계층

| 계층 | 주요 모듈 | 책임 |
|---|---|---|
| Data engineering | `python/data_engineering` | canonical record, validation, split, atomic I/O |
| Field data | `vision_ai.field_data` | ingestion, quality, duplicate, privacy, labeling, QA, versioning |
| Vision domain | `vision_ai.models`, `pipeline` | backend-neutral prediction 계약과 orchestration |
| Runtime adapter | `backend_registry`, `onnx_backend` | reference/ONNX backend 생성과 실행 |
| Evaluation | `evaluation` | classification, detection, severity metric |
| Training | `training`, `pytorch_training` | framework-neutral runner와 optional PyTorch 구현 |
| Packaging | `model_package`, `package_models` | portable artifact, checksum, compatibility |
| Registry | `model_registry` | immutable package copy, stage, revision, recovery |
| Serving | `serving`, `serving_app` | lifecycle, cache, metrics, HTTP API |
| Release | `release_readiness` | 배포 전 검사와 release manifest |

## 안정적인 protocol

`VisionBackend`는 `model_version`, `assess_quality`, `classify`, `detect`를 제공한다.
`TrainingBackend`는 `prepare`, `train`, `validate`, `export`를 제공한다. Optional
framework adapter는 이 두 protocol 밖에서 구현되며 protocol 자체를 변경하지 않는다.

## 직렬화 경계

모든 장기 보관 artifact는 JSON 또는 JSONL이며 `dataset/schemas`의 계약을 따른다.
경로는 artifact root 기준 상대경로로 기록한다. Timestamp는 UTC ISO 8601, checksum은
SHA-256, 정렬 가능한 목록과 mapping은 결정적인 순서를 사용한다.

## 보안 경계

- 외부 네트워크 및 pretrained download를 기본 workflow에서 사용하지 않는다.
- 원본 현장 이미지는 읽기 전용 입력이며 repository에 커밋하지 않는다.
- ingestion, package, registry는 path traversal과 symbolic link를 거부한다.
- privacy derivative는 원본을 덮어쓰지 않는다.
- API는 payload나 stack trace를 응답 또는 로그에 저장하지 않는다.
- FastAPI, ONNX Runtime, PyTorch, Pillow는 필요 시점에만 import한다.

## 확장 지점

- `BackendRegistry`에 새로운 inference backend factory 등록
- `TrainingBackend` protocol을 구현한 학습 framework adapter 추가
- privacy detector 또는 labeling platform adapter를 optional module로 추가
- JSON Schema version을 유지하며 새 field를 추가하거나 새 format version 정의

상세 계약은 [MODEL_PACKAGE_SPEC.md](MODEL_PACKAGE_SPEC.md),
[REGISTRY_SPEC.md](REGISTRY_SPEC.md), [SERVING_GUIDE.md](SERVING_GUIDE.md)를 참고한다.
