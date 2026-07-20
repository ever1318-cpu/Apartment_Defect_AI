# Coverage Report

- 측정일: 2026-07-20
- 대상 branch: `release/v1.0.0-rc1`
- 기준 commit: `2d69bcc`
- Python: 3.12.13
- Coverage.py: 7.15.2

## Summary

| Metric | Result |
|---|---:|
| Tests | 107 passed, 7 skipped |
| Statements | 4,136 |
| Missed | 822 |
| Line coverage | **80%** |

## 주요 module

| Module | Coverage |
|---|---:|
| CLI | 93% |
| Field data | 82% |
| Evaluation | 92% |
| Model package | 85% |
| Model registry | 91% |
| Training dataset | 94% |
| Serving core | 91% |
| ONNX adapter | 82% |
| PyTorch training | 47% |
| FastAPI app | 12% |

## 해석

이 수치는 local core 환경에서 전체 suite를 실행한 결과다. FastAPI, ONNX Runtime,
PyTorch optional dependency가 설치되지 않아 관련 integration test가 skip되었다.
따라서 `serving_app`과 실제 PyTorch engine coverage가 낮다. CI의 serving,
onnx-smoke, training-smoke job은 해당 dependency를 설치해 별도로 검증하지만 이
local line coverage 합계에는 포함되지 않는다.

0%인 active-learning/augmentation module은 Sprint 1 artifact로 현재 pytest
collection에서 직접 실행되지 않는다. Release 전 후속 작업은 optional CI coverage
artifact 통합과 이 legacy module의 regression test 복원이다.

## Reproduce

```powershell
.\.venv\Scripts\python.exe -m pip install "coverage>=7,<8"
.\.venv\Scripts\python.exe -m coverage erase
.\.venv\Scripts\python.exe -m coverage run `
  --source=python/data_engineering,python/vision_ai `
  -m pytest -q --basetemp output/pytest-release-coverage
.\.venv\Scripts\python.exe -m coverage report --skip-covered
.\.venv\Scripts\python.exe -m coverage json `
  -o output/coverage-release.json
```

Generated coverage data와 JSON은 `output/`의 local artifact이며 source control에
포함하지 않는다.

Release artifacts:

- [Cobertura XML](../coverage.xml)
- [Browsable HTML report](../htmlcov/index.html)
- [Static coverage badge](coverage.svg)
