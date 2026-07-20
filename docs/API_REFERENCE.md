# API Reference

Serving API는 application factory `vision_ai.serving_app.create_serving_app`으로 생성된다.
기본 base URL은 `http://127.0.0.1:8000`이다.

## 상태와 모델

| Method | Path | 설명 |
|---|---|---|
| GET | `/health` | process 생존 상태. 모델 준비 여부와 무관 |
| GET | `/ready` | production model load 가능 여부. 실패 시 503 |
| GET | `/v1/models` | registry revision과 전체 model entry |
| GET | `/v1/models/{model_name}` | 해당 model의 version 목록 |
| GET | `/v1/models/{model_name}/{model_version}` | 단일 registry entry |
| GET | `/v1/metrics` | thread-safe process-local metrics snapshot |

## 단일 prediction

`POST /v1/predict`, `Content-Type: application/json`

```json
{
  "image_base64": "iVBORw0KGgo...",
  "mime_type": "image/png",
  "image_id": "field-001",
  "model_name": "apartment-defect",
  "model_version": "1.0.0",
  "confidence_threshold": 0.25
}
```

`image_id`, model override, threshold는 선택 항목이다. 응답은
`vision_prediction.schema.json`과 호환된다. 응답 header에 `x-request-id`가 포함된다.

## Batch prediction

`POST /v1/predict/batch`

```json
{
  "items": [
    {"image_base64": "...", "mime_type": "image/jpeg", "image_id": "a"},
    {"image_base64": "...", "mime_type": "image/png", "image_id": "b"}
  ],
  "fail_fast": false
}
```

입력 순서를 유지한다. `fail_fast=false`이면 이미지별 `success` 또는 `error`를 반환한다.
Batch size와 encoded/decoded payload는 `ServingConfig` 제한을 적용한다.

## 오류

```json
{
  "error": {
    "code": "MODEL_NOT_READY",
    "message": "production model is not ready",
    "details": {},
    "request_id": "..."
  }
}
```

주요 code는 `INVALID_IMAGE`, `UNSUPPORTED_MEDIA_TYPE`, `PAYLOAD_TOO_LARGE`,
`BATCH_TOO_LARGE`, `MODEL_NOT_FOUND`, `MODEL_NOT_READY`,
`PACKAGE_VALIDATION_FAILURE`, `INFERENCE_FAILURE`, `INVALID_REQUEST`,
`REQUEST_TIMEOUT`, `INTERNAL_ERROR`다. 내부 path, payload, stack trace는 노출하지 않는다.

## Metrics

Request/success/error/image/batch/model-load/cache/reload/rejection/timeout count,
inference/request duration, model/error/status별 집계, readiness, active model,
cache size, concurrent request, batch bucket, uptime을 JSON으로 반환한다.
