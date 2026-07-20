# Dependency Graph

## Internal modules

```mermaid
flowchart TD
  CLI[data_engineering.cli] --> DE[data_engineering models/io/split]
  CLI --> FD[vision_ai.field_data]
  CLI --> VP[vision_ai.pipeline]
  CLI --> TR[vision_ai.training]
  CLI --> PKG[vision_ai.model_package]
  CLI --> REG[vision_ai.model_registry]
  CLI --> REL[vision_ai.release_readiness]

  FD --> DE
  FD --> EM[evaluation_models]
  VP --> VM[vision_ai.models]
  VP --> POST[postprocessing]
  TR --> TM[training_models]
  PTR[pytorch_training] --> TR
  PTR --> EM
  ONNX[onnx_backend] --> VP
  PKG --> TM
  PKG --> PM[package_models]
  REG --> PKG
  SERV[serving] --> REG
  SERV --> ONNX
  SERV --> VP
  APP[serving_app] --> SERV
  REL --> REG
  REL --> PKG
  REL --> SERV
```

## Optional external dependencies

| Group | Packages | Import boundary |
|---|---|---|
| Core | Python standard library only | Always available |
| Test | pytest | Test execution |
| Serving | FastAPI, uvicorn, python-multipart, httpx | App factory/server/TestClient |
| ONNX | onnx, onnxruntime, NumPy, Pillow | Session/image loader/smoke fixture |
| PyTorch | torch, torchvision, NumPy, Pillow, onnx | Training and export |
| Full | 위 그룹 전체 | CI/release integration |

```mermaid
flowchart LR
  CORE[Core package] -.lazy.-> FAST[FastAPI / uvicorn]
  CORE -.lazy.-> ORT[ONNX Runtime / NumPy / Pillow]
  CORE -.lazy.-> TORCH[PyTorch / torchvision / ONNX]
```

Core import test는 optional package가 `sys.modules`에 들어오지 않는지 검사한다.
Version 범위는 루트 `pyproject.toml`이 authoritative source다.
