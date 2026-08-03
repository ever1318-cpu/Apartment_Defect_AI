# PinSet App — Galaxy S25 Ultra Build

한 번의 셔터로 여러 초점거리 사진을 동시에 촬영하고, 사전 등록한 참조사진 매칭으로 평면도 위에 하자 핀을 자동 배치하는 안드로이드 앱.

## 요구사항

- Android Studio Ladybug (2024.2.1) 이상
- JDK 17
- Android SDK Platform 35, minSdk 33
- Galaxy S25 Ultra (arm64-v8a, Android 14+)

## 프로젝트 구조

```
android/
├─ app/
│  ├─ build.gradle.kts
│  ├─ proguard-rules.pro
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ kotlin/com/axlife/pinset/       # 소스
│     ├─ res/                             # 아이콘·문자열·테마
│     └─ assets/
│        ├─ floorplans/                   # 평면도 이미지 + JSON
│        └─ reference/                    # 방별 참조사진
├─ gradle/wrapper/
├─ build.gradle.kts
├─ settings.gradle.kts
└─ keystore/                              # 릴리스 서명 (git에 커밋 금지)
```

## 초기 설정

### 1. Gradle Wrapper 초기화

Android Studio에서 프로젝트를 열면 `gradle-wrapper.properties`에 지정된 Gradle 8.10.2를 자동으로 다운로드합니다. `android/gradlew.bat` / `android/gradlew`가 없으면 Android Studio에서 **Sync Project with Gradle Files**를 실행하거나, 기존 프로젝트에서 wrapper 스크립트를 복사해 넣으세요.

### 2. 평면도 이미지 배치

`app/src/main/assets/floorplans/` 폴더에 평면도 PNG를 넣습니다. 저장소의 `ux_design_package.old/ux_design_package/screens/s08_floorplan.png`를 그대로 복사해 **`apt_101_1502.png`** 이름으로 저장하면 기본 예시 평면도가 활성화됩니다.

앵커 좌표는 `apt_101_1502.json`에 이미 정의되어 있습니다. 다른 평면도를 쓸 경우 JSON의 `cx`, `cy`(0–1 정규화)를 이미지에 맞춰 수정하세요.

### 3. 참조사진 등록 (선택)

`app/src/main/assets/reference/` 폴더에 방별로 사진을 몇 장씩(권장 3–5장) 촬영해 넣고, `index.json`에 항목을 추가하세요.

```json
{
  "entries": [
    { "file": "livingroom_1.jpg", "roomId": "livingroom", "roomLabel": "거실" },
    { "file": "livingroom_2.jpg", "roomId": "livingroom", "roomLabel": "거실" },
    { "file": "mainbed_1.jpg", "roomId": "mainbed", "roomLabel": "안방" }
  ]
}
```

`roomId`는 `apt_101_1502.json`의 방 `id`와 정확히 일치해야 합니다. 참조사진이 없으면 매칭은 실패로 처리되어 **수동 방 선택**으로 폴백합니다.

### 4. 릴리스 서명 keystore 생성

`android/keystore/` 폴더에서 (없으면 만들고):

```bash
keytool -genkeypair -v ^
  -keystore release.jks ^
  -alias pinset ^
  -keyalg RSA -keysize 2048 ^
  -validity 3650 ^
  -storepass pinset2026 -keypass pinset2026 ^
  -dname "CN=AXLife, O=AXLife, C=KR"
```

비밀번호를 바꾸려면 환경변수 `PINSET_STORE_PASSWORD`, `PINSET_KEY_PASSWORD`, `PINSET_KEY_ALIAS`를 지정하고 다시 빌드하세요.

## 빌드

```bash
cd android
./gradlew clean assembleRelease
```

빌드 결과: `app/build/outputs/apk/release/app-release.apk`

## 갤S25U에 설치

1. 폰: **설정 → 개발자 옵션 → USB 디버깅** 활성화 (개발자 옵션이 없으면 "빌드번호"를 7번 탭)
2. USB 연결 후 `adb install app/build/outputs/apk/release/app-release.apk`
3. 또는 APK를 갤S25U 저장소로 복사 후 파일에서 직접 설치 (알 수 없는 출처 앱 허용 필요)

## 사용 방법

1. 앱 실행 → 홈 화면에서 **"새 하자 촬영 시작"** 탭
2. 카메라 화면에서 초광각·광각 프리뷰 확인 → 셔터 누르기
3. 촬영 후 자동 매칭된 방 위치에 핀이 표시됨 → 필요 시 드래그로 미세조정
4. **"하자 정보 입력"** → 유형/등급/메모 입력 → 저장
5. 홈으로 돌아가 최근 하자 목록 또는 평면도 탭에서 핀 확인

### 촬영 모드 (상단바 토글)

- **동시**: Camera2 Logical Multi-Camera로 초광각+광각 하드웨어 동기 촬영 (S25U 기본)
- **순차**: CameraX 줌 브라케팅 (0.6x → 1x → 3x). 시차가 있을 수 있음

## 알려진 제약

- MVP는 로컬 색상 히스토그램 매칭입니다 — 조명·마감재가 유사한 방을 헷갈릴 수 있습니다. 정밀 매칭이 필요하면 OpenCV ORB로 교체 예정
- AI 자동 하자 분류는 포함되지 않음 (수동 태그 입력)
- 리포트는 최소 뷰만 제공 (v1.1에서 PDF 내보내기 예정)
- 3-way 동시 캡처(초광각+광각+망원)는 S25U의 Logical Multi-Camera 조합 지원 여부에 따라 자동으로 2-way로 폴백됩니다
