# Developer Guide

## 환경 준비

Python 3.11 이상이 필요하다.

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -e ".[test]"
```

Optional 환경:

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[test,serving]"
.\.venv\Scripts\python.exe -m pip install -e ".[test,onnx]"
.\.venv\Scripts\python.exe -m pip install -e ".[test,pytorch]"
.\.venv\Scripts\python.exe -m pip install -e ".[full]"
```

## 코드 구조

```text
python/data_engineering/   CLI, record, split, validation, versioning
python/vision_ai/          Vision, field data, training, packaging, serving
python/tests/              unit, integration, optional smoke tests
dataset/config/            default pipeline configuration
dataset/schemas/           JSON Schema contracts
docs/                      release documentation
```

## 개발 원칙

- `VisionBackend`와 `TrainingBackend` protocol은 하위 호환을 유지한다.
- core module import 시 optional dependency를 import하지 않는다.
- 파일 저장은 가능한 경우 atomic write 또는 temp-directory rename을 사용한다.
- manifest에는 로컬 절대경로, secret, 실제 사용자 payload를 기록하지 않는다.
- 기존 test를 삭제하거나 assertion을 완화하지 않는다.
- framework 또는 장비별 설정은 adapter/configuration 경계에 둔다.

## 새 backend 추가

1. `VisionBackend` method를 구현한다.
2. dependency import는 session 생성 시점까지 지연한다.
3. `BackendRegistry.register()`에 factory를 등록하거나 `module:attribute`로 노출한다.
4. fake session unit test와 실제 dependency smoke test를 분리한다.
5. output은 기존 `VisionPrediction`과 ONNX named-output 계약에 맞춘다.

## 새 schema 추가

`dataset/schemas`에 Draft 2020-12 JSON Schema를 추가한다. `$id`, title, required,
`additionalProperties` 정책을 명시한다. Python model의 `to_dict`/`from_dict`
round trip test와 전체 schema JSON 구문 검사를 추가한다.

## 검증

```powershell
.\.venv\Scripts\python.exe -m pytest -q
.\.venv\Scripts\python.exe -m compileall -q python
git diff --check
.\.venv\Scripts\python.exe -c "import json,pathlib; [json.loads(p.read_text(encoding='utf-8-sig')) for p in pathlib.Path('dataset/schemas').glob('*.json')]"
```

Marker와 CI 세부 정책은 [TESTING_GUIDE.md](TESTING_GUIDE.md)를 참고한다.
