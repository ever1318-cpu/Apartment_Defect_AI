# Release Notes — v1.0.0-rc1

이 문서는 `release/v1.0.0-rc1` release candidate의 기능 범위를 설명한다.
Python package metadata의 내부 개발 버전은 `0.12.0`이며 최종 1.0 release 전
호환성과 artifact version을 확정한다.

## 주요 기능

- Canonical data engineering과 group-leakage-safe split
- Backend-neutral Vision prediction, evaluation, training protocol
- Optional PyTorch CPU/CUDA training과 ONNX export
- Portable model package, SHA-256, compatibility, CPU/GPU profile
- Revisioned local registry와 production stage lifecycle
- Optional FastAPI serving, cache, metrics, sanitized errors
- Release check와 machine-readable release manifest
- Field image ingestion, quality, duplicate, privacy, labeling, annotation QA
- Approved-only, lineage-aware dataset version export
- Non-root Docker와 optional dependency CI matrix

## Compatibility

- Python 3.11 이상
- Core는 외부 ML/Web framework 없이 import 가능
- 기존 raw ONNX와 package-directory inference 지원
- `VisionBackend`, `TrainingBackend` protocol 유지
- Package/registry format version `1.0`

## 검증

- 전체 pytest: 107 passed, 7 skipped
- Core marker: 106 passed, 3 skipped, 5 deselected
- JSON Schema: 29개
- compileall, whitespace, Docker static validation 통과
- Optional dependency 실제 smoke는 분리된 CI job에서 실행

## 알려진 제한사항

- Reference backend는 workflow 검증용이며 실제 하자 정확도를 보장하지 않는다.
- Privacy 자동 탐지는 제공하지 않으며 수동 mask 또는 injected detector가 필요하다.
- Pillow가 없으면 JPEG/WebP/TIFF의 고급 pixel quality metric이 제한된다.
- Metrics/cache/registry는 단일 filesystem/process 운영 기반이다.
- 인증, remote signed artifact storage, durable telemetry는 포함하지 않는다.
- Request/model-load timeout은 실행 중 thread를 강제 종료하지 않는 cooperative 경계다.

## Upgrade와 rollback

배포 전 `vision-release-check --strict`와 package checksum을 확인한다. 새 model은
development → staging → production 순으로 승격한다. 문제가 발생하면 이전 version을
production으로 재승격하고 `/ready`, `/v1/metrics`, registry revision을 확인한다.
Registry 손상 복구 절차는 루트 [OPERATIONS.md](../OPERATIONS.md)를 따른다.
