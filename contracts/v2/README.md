# AI 하자점검 v2 계약 기준선

이 디렉터리는 `AI하자점검 통합제품설계서 v2.0`을 구현하기 위한
계약 우선 산출물입니다.

## 현재 고정된 항목

- API major version: `v2`
- 제품 분류체계 목표 버전: `2.0.0`
- 이미지 모델: `apartment-defect-convnext` `2.0.0`
- 모델 입력: RGB, 224×224, NCHW float32, 0~1 정규화
- 모델 출력 과업: `area`, `cause`, `part`, `part_detail`, `work_kind`
- 사용자 확인 없는 자동 확정 금지
- 대화형 확인은 최대 3턴
- 모바일 앱의 RDS 직접 접속과 AI 공급자 키 저장 금지

## 모델 분류와 제품 분류의 차이

현재 모델은 학습 DB에서 복구한 5개 과업을 출력합니다.

| 모델 과업 | 클래스 수 | 제품 사용 |
|---|---:|---|
| area | 17 | 위치 후보 |
| part | 3 | 대분류 부위 후보 |
| part_detail | 171 | 상세부위 후보 |
| work_kind | 57 | 공종 후보·규칙 입력 |
| cause | 9 | 원인 가설 |

모델의 `cause`에는 원인, 현상, 민원 상태가 혼재되어 있으므로 제품의
`현상 코드`와 `원인 가설`을 동일 필드로 저장하지 않습니다. 이 부분은
현장 코드북 검토 후 별도 매핑 규칙으로 확정해야 합니다.

## 보안 경계

`openapi.yaml`의 서버 URL과 OIDC URL은 의도적으로 동작하지 않는
placeholder입니다. 접속 비밀번호, DSN, API 키와 실제 내부 호스트는
계약 저장소에 기록하지 않습니다.

## 다음 구현

1. OpenAPI 문법·예제 검증
2. fake inference/assistant 서버 구현
3. Android API DTO와 fake repository 생성
4. 기존 Room 데이터의 명시적 migration 설계
5. 운영 RDS 변경은 별도 승인 후 실행
