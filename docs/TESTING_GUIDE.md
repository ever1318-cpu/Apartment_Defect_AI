# Testing Guide

## Local commands

```powershell
# 전체 suite
.\.venv\Scripts\python.exe -m pytest -q

# 빠른 core
.\.venv\Scripts\python.exe -m pytest -q `
  -m "not serving and not onnx and not training and not docker"

# Optional environments
.\.venv\Scripts\python.exe -m pytest -q -m serving
.\.venv\Scripts\python.exe -m pytest -q -m onnx
.\.venv\Scripts\python.exe -m pytest -q -m training
.\.venv\Scripts\python.exe -m pytest -q -m docker
```

## Marker

| Marker | 의미 |
|---|---|
| `integration` | 여러 계층을 통과하는 통합 경로 |
| `serving` | FastAPI/TestClient 필요 |
| `onnx` | ONNX/ONNX Runtime 필요 |
| `training` | PyTorch training/export 필요 |
| `slow` | 실행 시간이 긴 smoke |
| `docker` | container/static Docker validation |

Optional package가 없으면 명확한 이유로 skip하며 core 실패로 취급하지 않는다.

## Static validation

```powershell
.\.venv\Scripts\python.exe -m compileall -q python
git diff --check
.\.venv\Scripts\python.exe -c "import json,pathlib; [json.loads(p.read_text(encoding='utf-8-sig')) for p in pathlib.Path('dataset/schemas').glob('*.json')]"
```

Core import test는 FastAPI, ONNX Runtime, torch가 `sys.modules`에 들어오지 않았는지
검사한다. 실제 model download, network, GPU는 test에서 사용하지 않는다.

## CI jobs

- `core`: Linux Python 3.11/3.12/3.13
- `windows-core`: Windows Python 3.12
- `serving`: optional FastAPI lifecycle
- `onnx-smoke`: local tiny ONNX CPU model
- `training-smoke`: tiny CPU training → ONNX → package → registry → evaluation
- `schema-static`: compile, whitespace, schema, Docker static test
- `docker-build`: non-pushing image build

현재 release 기준 전체 local 결과는 `107 passed, 7 skipped`다.
