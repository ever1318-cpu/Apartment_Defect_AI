# Serving Guide

## 설치와 실행

```powershell
python -m pip install -e ".[serving,onnx]"
apartment-data vision-serve --registry model-registry `
  --model apartment-defect --host 0.0.0.0 --port 8000
```

Import 시 서버는 자동 실행되지 않는다. Application factory가 registry의 production
package를 validation하고 model session을 lazy load한다.

## Configuration

`ServingConfig`는 registry/model, host/port/workers, upload/batch 제한, threshold,
session/inference cache, compatibility strictness, MIME, temp/logging, request/model-load
timeout, concurrency, total batch bytes, image pixel, JSON depth 제한을 가진다.

환경변수 prefix는 `ADA_`다. 주요 값은 `ADA_REGISTRY`, `ADA_MODEL`, `ADA_HOST`,
`ADA_PORT`, `ADA_WORKERS`, `ADA_MAX_UPLOAD_BYTES`, `ADA_MAX_BATCH_SIZE`,
`ADA_REQUEST_TIMEOUT_SECONDS`, `ADA_MAX_CONCURRENT_REQUESTS`다.

## Lifecycle

- `/health`는 process 상태만 반영한다.
- `/ready`는 production model load 성공 여부를 반영한다.
- Registry revision 변화는 다음 resolution 시 감지한다.
- 새 model reload 실패 시 기존 healthy session을 유지한다.
- Session cache는 model/version LRU, inference cache는 SHA-256 LRU다.
- Shutdown은 active/retired session과 cache를 닫고 temp file은 request 종료 시 제거한다.

## Security limits

허용 MIME과 magic byte 일치, base64 decoded 크기, batch 총량, image dimension,
PNG 구조, JSON depth, concurrency wait를 검사한다. 오류는 sanitized API model로
반환하고 image bytes/base64/path/stack trace를 기록하지 않는다.

## Docker

```powershell
docker build -t apartment-defect-serving .
docker run --read-only --tmpfs /tmp/apartment-defect-ai:rw,noexec,nosuid `
  -v ${PWD}/model-registry:/var/lib/apartment-defect-ai/registry `
  -e ADA_MODEL=apartment-defect -p 8000:8000 apartment-defect-serving
```

이미지는 non-root로 실행되고 registry는 runtime mount다. `/ready` healthcheck와
SIGTERM graceful shutdown을 사용한다.

Endpoint 계약은 [API_REFERENCE.md](API_REFERENCE.md)를 참고한다.
