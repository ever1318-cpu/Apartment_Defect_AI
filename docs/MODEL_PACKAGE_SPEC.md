# Model Package Specification

Package format version은 `1.0`이다.

## Directory

```text
model-package/
  model.onnx
  model_manifest.json
  compatibility_manifest.json
  checksums.json
  label_mapping.json
  preprocessing.json
  deployment_profiles.json
  README.txt
```

위 8개 파일은 필수다. Manifest에는 package 내부 상대 파일명만 기록한다.

## Model contract

Input은 NCHW `float32` image tensor다. Dynamic batch 여부와 shape는 manifest에 기록한다.
필수 named output:

- `quality`
- `space_scores`
- `trade_scores`
- `component_scores`
- `boxes`
- `detection_scores`
- `detection_labels`

## Checksum

`checksums.json`은 추적 파일의 SHA-256을 경로순으로 기록한다. Checksum manifest 자체는
대상에서 제외한다. Missing, mismatch, unexpected file을 구분하며 strict validation은
unexpected file도 error로 처리한다.

## Compatibility

Python 범위, 최소 ONNX Runtime, execution provider, CPU architecture, OS,
input/output dtype와 shape, application schema, vocabulary, preprocessing version을
기록한다. 검사 상태는 `pass`, `warning`, `fail`이다.

## Deployment profiles

기본 `cpu`는 `CPUExecutionProvider`, thread/optimization/memory arena 설정을 제공한다.
기본 `gpu`는 CUDA와 CPU fallback, device ID, memory/arena/convolution 설정을 제공한다.
특정 장비 값은 package builder 호출 시 override한다.

## Build와 validation

Builder는 completed training run, export/environment metadata, label mapping,
training spec, `model.onnx`를 검사한다. Sibling temp directory에서 package를 만든 뒤
atomic rename하며 기존 output을 덮어쓰지 않는다. Symlink와 traversal은 거부한다.

```powershell
apartment-data vision-package-model training-runs/run-001 package `
  --model-name apartment-defect --model-version 1.0.0
apartment-data vision-validate-model-package package --strict
```
