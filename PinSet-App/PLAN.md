# Two-Shot PinSet App — 갤럭시 S25 Ultra 최종 배포본 계획

## Context

아파트/건물 하자 점검용 안드로이드 앱. 스마트폰 셔터 한 번으로 서로 다른 초점거리(0.5x 초광각 + 2x 망원 등)의 사진 2–3장을 동시에 촬영하고, 촬영 결과와 사전 등록된 참조사진을 특징점 매칭해 앱 assets에 번들된 평면도 위의 위치를 추정, 그 자리에 하자 핀을 자동으로 꽂는다. `Two shot PinSet App/` 폴더는 UX 설계 문서만 있고 소스는 아직 없어 신규 프로젝트로 만든다. 배포 목표는 Galaxy S25 Ultra에서 즉시 설치 가능한 **서명된 릴리스 APK**.

기획서(`ux_design_package.old/defect_inspection_app_plan_v2_final.html`)와 UX 스크린(`ux_design_package.old/ux_design_package/screens/s0*_*.png`)이 최종 UX의 기준이다.

## 확정 결정사항

| 항목 | 결정 |
|---|---|
| 플랫폼 | **Native Android — Kotlin + Jetpack Compose** |
| 최소 SDK | API 33 (Android 13). 타겟 SDK 34, Compile SDK 35 |
| 카메라 | Camera2 Logical Multi-Camera API — **① 동시 멀티렌즈(권장 기본)** + **② 순차 줌 브라케팅** 두 모드 옵션 |
| 위치 추정 | **이미지 매칭 DB** — 사전 번들된 참조사진의 ORB 특징점과 촬영본을 매칭해 방 라벨 결정 → 평면도 상 사전 정의된 방 앵커에 핀 배치, 사용자가 드래그로 미세조정 |
| 하자 분류 | **수동 태그만** — 유형(균열/누수/마감불량/기타) + 등급(경미/보통/중대) 사용자 선택. AI 없이 오프라인 동작 |
| 평면도 | **앱 assets에 샘플 1장 번들** (`s08_floorplan.png`와 동일한 4-Bed 예시). JSON으로 방 앵커 좌표 정의 |
| 데이터 저장 | **Room DB** (로컬). 사진 파일은 앱 전용 저장소. 핀 좌표는 0–1 정규화 |
| 배포 | **서명 APK 사이드로드** — release keystore로 서명, 갤S25U 직접 설치 |
| 언어 | 한국어 단일 |

## 아키텍처

MVVM + Repository, 단일 Activity + Compose Navigation.

```
android/
├─ app/
│  ├─ build.gradle.kts
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ kotlin/com/axlife/pinset/
│     │  ├─ MainActivity.kt              // ComponentActivity, NavHost
│     │  ├─ ui/
│     │  │  ├─ theme/                    // Material3 색상: primary #1565C0, secondary #E91E63
│     │  │  ├─ home/HomeScreen.kt        // s03_home 재현
│     │  │  ├─ camera/CameraScreen.kt    // s04_camera 재현, 상하 분할 프리뷰
│     │  │  ├─ camera/CameraViewModel.kt
│     │  │  ├─ pinset/PinPlacementScreen.kt  // 촬영 직후 자동 핀 결과 확인/미세조정
│     │  │  ├─ pinset/PinTagSheet.kt     // 유형/등급 수동 선택 BottomSheet
│     │  │  ├─ floorplan/FloorplanScreen.kt // s08_floorplan, 팬/줌/핀 렌더
│     │  │  ├─ detail/PinDetailScreen.kt // s09_pin_detail
│     │  │  └─ history/HistoryScreen.kt  // 최근 하자 목록
│     │  ├─ camera/
│     │  │  ├─ MultiLensCaptureController.kt   // Camera2 Logical Multi-Camera 세션
│     │  │  ├─ ZoomBracketController.kt        // 순차 줌 촬영 fallback
│     │  │  └─ CaptureMode.kt                   // enum: SIMULTANEOUS, SEQUENTIAL
│     │  ├─ vision/
│     │  │  ├─ FeatureExtractor.kt       // OpenCV ORB
│     │  │  ├─ RoomMatcher.kt            // 촬영본 vs 참조사진 DB → roomId + score
│     │  │  └─ ReferenceDb.kt            // assets/reference/*.jpg + index.json 로더
│     │  ├─ data/
│     │  │  ├─ AppDatabase.kt            // Room
│     │  │  ├─ entity/{Session,Defect,DefectPhoto}.kt
│     │  │  ├─ dao/{SessionDao,DefectDao}.kt
│     │  │  └─ repo/DefectRepository.kt
│     │  └─ util/{ImageStore.kt, Permissions.kt}
│     ├─ res/  // 아이콘, 문자열, 테마
│     └─ assets/
│        ├─ floorplans/apt_101_1502.png       // 샘플 평면도 (s08_floorplan 스타일)
│        ├─ floorplans/apt_101_1502.json      // 방 앵커: {id, name, cx, cy, bbox}
│        ├─ reference/livingroom_1..3.jpg     // 각 방 3장씩
│        ├─ reference/mainbed_1..3.jpg
│        ├─ reference/bath_1..3.jpg
│        ├─ reference/kitchen_1..3.jpg
│        └─ reference/index.json              // [{file, roomId}]
├─ build.gradle.kts
├─ settings.gradle.kts
└─ keystore/release.jks   // gitignore
```

### 촬영 파이프라인 (동시 멀티렌즈)

1. `CameraManager.getCameraIdList()` → `REQUEST_AVAILABLE_CAPABILITIES`에 `LOGICAL_MULTI_CAMERA` 포함 카메라 선택
2. `getPhysicalCameraIds()`로 초광각·광각(·3x 망원) physical id 확보
3. `CameraDevice.createCaptureSession`에 physical id를 지정한 `OutputConfiguration` 2–3개 등록
4. 단일 `CaptureRequest`에 output add → 1회 `capture()` 호출로 ISP 동기 촬영
5. 각 `ImageReader` → JPEG 저장 → `DefectPhoto`(lens: ULTRA/WIDE/TELE) 레코드
6. 지원 불가 기기(런타임 감지)는 자동으로 `ZoomBracketController` 폴백

### 위치 추정 파이프라인

1. 촬영 완료 → 광각 사진 그레이스케일 다운샘플(640px)
2. OpenCV ORB 추출 (nfeatures=500)
3. 참조 index의 각 이미지와 `BFMatcher(NORM_HAMMING)` + Lowe ratio 0.75
4. 방별 매칭 점수 합산, 최고 점수 방 선택. 임계값 이하면 "수동 선택" 화면
5. 평면도 JSON에서 해당 방의 앵커 (cx, cy)에 핀 배치, source=AUTO
6. 사용자 드래그 조정 시 source=MANUAL로 승격, 정규화 좌표(0–1) 저장

### 데이터 모델 (Room)

```kotlin
Session(id, unitLabel: "101동 1502호", floorplanAssetId, createdAt)
Defect(id, sessionId, roomId, xNorm, yNorm, defectType, severity, note, createdAt, source)
DefectPhoto(id, defectId, filePath, lens, isPrimary)
```

## 화면 매핑

| UX 스크린 | 라우트 | 재현 대상 |
|---|---|---|
| s01_splash | splash | Compose splash + 권한 요청 |
| s03_home | `/home` | 세션 요약 + "새 하자 촬영 시작" + 최근 목록 |
| s04_camera | `/camera` | 상하 분할 PreviewView, 셔터, 모드 토글, 위치 파악 배지 |
| s07_result | `/pin-placement` | 촬영 후 자동 핀 배치 결과 |
| s08_floorplan | `/floorplan` | 평면도, A/M 핀, 팬/줌, 드래그, "핀 추가" |
| s09_pin_detail | `/pin-detail/{id}` | 0.5x + 2x 나란히, 태그/메모 편집 |
| s10_report | `/report` | 세션 요약 이미지 공유 |

## 주요 라이브러리

- `androidx.compose.bom` (Material3, Navigation, Icons)
- `androidx.camera:camera-camera2` (프리뷰) + Camera2 raw (촬영)
- `androidx.room:room-ktx` + KSP
- `org.opencv:opencv-android` 4.10+ (AAR, arm64-v8a only)
- `androidx.datastore:datastore-preferences`
- `coil-compose`

## 구현 단계

**Phase 1 — 스캐폴드 + 홈**
1. Android Studio 프로젝트 생성, minSdk 33, Compose
2. Material3 테마 (`#1565C0`, `#E91E63`, `#2E7D32`)
3. Nav 그래프, 라우트 스텁
4. `HomeScreen` s03 재현 (더미 데이터)
5. **검증**: 갤S25U debug 설치, 홈 표시, 탭 이동

**Phase 2 — Room + Repo**
6. `AppDatabase`, entities, DAOs
7. `DefectRepository` (Flow)
8. `HomeScreen` 최근 목록 DB 연결

**Phase 3 — 카메라**
9. `Permissions` (CAMERA) 요청
10. `MultiLensCaptureController` open + physical id 로그
11. `CameraScreen` 상하 분할 프리뷰
12. 셔터 → 두 장 저장, 임시 확인
13. `CaptureMode` 토글 UI + `ZoomBracketController` 구현
14. **검증**: 두 사진 저장, EXIF 확인, ISP 동기 육안 확인

**Phase 4 — 평면도 + 매칭**
15. 평면도 asset + 방 앵커 JSON 작성
16. OpenCV AAR 통합, ORB 스모크 테스트
17. 참조사진 3–5장/방씩 assets에 배치, `index.json`
18. `RoomMatcher.match(bitmap): MatchResult`
19. `FloorplanScreen` Canvas 렌더 + 팬/줌/드래그
20. **검증**: 각 방 실촬 → 매칭 정확도 확인

**Phase 5 — 핀 배치 & 상세**
21. `PinPlacementScreen` 자동 핀 미리보기 + 드래그
22. `PinTagSheet` 유형/등급/메모
23. 저장 → `Defect` + `DefectPhoto`
24. `PinDetailScreen` s09 재현
25. `FloorplanScreen` 실제 DB 핀 (A/M 뱃지)

**Phase 6 — 리포트 + 마무리**
26. `ReportScreen` — 평면도 축소 + 핀 목록, 이미지 공유 인텐트
27. 설정 (촬영 모드 기본, 세션 관리)
28. 아이콘/스플래시

**Phase 7 — 릴리스 서명 & 배포**
29. `release.jks` 생성 (로컬 보관, gitignore)
30. `signingConfigs.release` + `buildTypes.release { isMinifyEnabled = true }`
31. R8 규칙 (OpenCV, Room 유지)
32. `./gradlew assembleRelease` → `app-release.apk`
33. 갤S25U 사이드로드, 실사용 3세션 테스트

## 검증 (E2E)

- **카메라 동기**: 두 프리뷰 실시간, 셔터 후 두 JPEG EXIF `SubSecTimeOriginal` 근접 (ISP 하드웨어 동기 증거)
- **매칭 정확도**: 각 방 5회 촬영, 리콜 ≥80% 통과. 실패 시 수동 방 선택 폴백 동작
- **핀 좌표 영속성**: 저장 → 앱 재시작 → 핀 좌표 동일
- **드래그 미세조정**: 자동 핀 드래그 → source AUTO→MANUAL 전환
- **모드 폴백**: 개발자 설정 "순차 모드 강제" → 3장 다른 줌 순차 저장
- **릴리스 APK**: 앱 데이터 삭제 후 서명 APK 설치 → 권한 → 촬영 → 저장 크래시 없음

## 리스크 & 대응

- **S25U 3-way 동시 캡처 미지원**: Logical Multi-Camera는 조합 제한(대개 2-way). MVP는 2-way로 시작, 런타임 3-way 지원 시만 3장 저장
- **OpenCV AAR 크기**: arm64-v8a만 abi filter (S25U는 arm64)
- **매칭 실패**: 임계값 이하면 "방 수동 선택" 다이얼로그 폴백 — 사용자 신뢰 유지
- **참조사진 부재**: 실사용 전 각 방 3장 사전 촬영 등록 필요. Phase 6에 "참조 등록 모드" 훅만 남기고 별도 기능은 v2

## 산출물

- 소스 트리 `Two shot PinSet App/android/`
- 서명된 `app-release.apk` (~60MB 예상)
- README (설치·참조사진 준비 안내)
