# Google Colab ConvNeXt-Tiny 전체 학습

## 1. 로컬에서 입력 번들 생성

프로젝트 루트에서 실행한다. 번들에는 공개 이미지 URL, 학습 라벨, 현재 Python
소스만 들어가며 DB 비밀번호·DSN·환경변수는 포함하지 않는다.

```powershell
.\.venv\Scripts\apartment-data.exe vision-colab-export `
  workspace\datasets\defect-db-2026-07-21 `
  workspace\datasets\defect-convnext-full-training\training_spec.json `
  workspace\colab\defect-convnext-full-colab.zip
```

생성된 ZIP을 Google Drive의 다음 위치에 업로드한다.

```text
내 드라이브/Apartment_Defect_AI/colab-input/defect-convnext-full-colab.zip
```

## 2. Colab에서 학습

`notebooks/ConvNeXt_Colab_전체학습.ipynb`를 Colab으로 열고 런타임을 GPU로
변경한 다음 셀을 순서대로 실행한다.

- 이미지 다운로드와 학습은 `/content`에서 실행한다.
- 결과만 Drive의 `Apartment_Defect_AI/colab-results`에 저장한다.
- 런타임이 중단되면 다운로드부터 다시 실행해야 하므로, 장시간 학습에는
  Colab Pro의 지속 실행 환경을 권장한다.
- T4 기준 배치 32에서 메모리 부족이 발생하면 배치를 16 또는 8로 낮춘다.

## 3. 결과를 로컬 프로젝트로 반입

Drive에서 결과 ZIP을 내려받아 다음 명령을 실행한다.

```powershell
.\.venv\Scripts\apartment-data.exe vision-colab-import `
  C:\Users\<사용자>\Downloads\defect-convnext-full-run-YYYYMMDD-HHMMSS.zip `
  workspace\datasets\defect-convnext-full-colab-run
```

반입 명령은 ZIP 경로 탈출 공격을 차단하고, 완료 상태 및 필수 지표·학습명세·
라벨 매핑을 확인하며, 원본 ZIP의 SHA-256을
`colab_import_manifest.json`에 기록한다.

반입 후 보고서를 생성한다.

```powershell
.\.venv\Scripts\apartment-data.exe vision-training-report `
  workspace\datasets\defect-convnext-full-colab-run\defect-convnext-full-run `
  docs\AI_하자분류_ConvNeXt_전체학습_평가.html
```

## 운영상 주의사항

- Colab에는 AWS RDS 접속정보를 입력하지 않는다.
- 입력 번들은 공개 S3 이미지 URL만 사용한다.
- 원본/정제본 비교는 동일한 split, seed, epoch, learning rate를 사용한다.
- 정제본 전체 학습은 화살표·스티커 마스크 품질 검토 후 별도 번들로 실행한다.
