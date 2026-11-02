# Flare Todo — Android

flare-todo 서버(REST API)를 쓰는 네이티브 Android 앱. Android 16 (API 36) 전용, Kotlin + Jetpack Compose (Material 3, 기기 색상 팔레트 사용).

루트의 Spring Boot 프로젝트와는 별개의 Gradle 빌드다 (`android/` 에서 실행).

## 빌드 · 설치

```bash
export JAVA_HOME=/Volumes/d/-default_library/amazon-corretto-21.jdk/Contents/Home
cd android

./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (R8 축소, 디버그 키 서명)
./gradlew assembleDebug       # 평문 HTTP 허용 → 로컬 서버(http://10.0.2.2:9090 등) 접속용
./gradlew lintDebug

# USB 디버깅으로 연결된 기기에 설치
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/release/app-release.apk
```

- `local.properties` 의 `sdk.dir` 은 각자 환경에 맞춘다 (git 제외).
- 최신 AndroidX 가 요구해서 `compileSdk` 는 37 이지만 `minSdk`/`targetSdk` 는 36 (Android 16).
- Android 16 에뮬레이터가 없을 때만 `-PminSdkOverride=34` 로 낮춰 설치할 수 있다 (Live Update 는 동작하지 않음).
- 기본 서버는 `https://todo.flare.im`. 로그인 화면의 "서버 설정"에서 바꿀 수 있다.

## 기능

| 기능 | 구현 |
| --- | --- |
| 로그인 / 회원가입 | JWT 를 Keystore 키로 암호화해 DataStore 에 저장 |
| **지문 로그인** | 아이디·비밀번호를 "지문 인증을 통과해야만 쓸 수 있는" Keystore AES 키로 암호화. 앱을 새로 열 때마다 지문 → 복호화 → 로그인 API. 기기 지문이 바뀌면 키가 폐기되어 비밀번호 로그인 필요 (`auth/BiometricLogin.kt`) |
| 목록 | 전체/미완료/완료, 카테고리 다중 필터, 정렬, 등록일·완료일 기간 검색, 당겨서 새로고침. 검색 조건은 기억된다 |
| 상세 / 편집 | 본문 HTML 은 WebView 로 표시. 편집은 contenteditable 기반(`assets/editor.html`)이라 웹(TinyMCE)에서 만든 표·이미지를 보존한다. 기존 할 일 편집 중 1분마다 자동 저장 |
| 첨부 | 파일 업로드, 본문 이미지 삽입, 외부 공유 링크 생성·관리 |
| **첨부 저장** | `Download/flare-todo/` 에 저장 (시스템 DownloadManager, 저장소 권한 불필요). CloudFront 서명 쿠키를 받아 요청에 실어 보낸다 (`download/AttachmentDownloader.kt`) |
| **상태표시줄 고정 (카테고리별)** | 서랍 메뉴 → "카테고리 · 상태표시줄 설정". 켠 카테고리마다 미완료 목록을 담은 진행 중 알림을 띄운다. Android 16 Live Update 로 승격을 요청해 상태표시줄 칩 / One UI Now Bar 에 건수 표시 (`notify/PinnedNotifier.kt`) |
| **공유 → "Flare Todo 비용"** | 갤러리에서 사진(여러 장 가능)을 공유하면 "비용" 카테고리의 이번 달 할 일(제목 `yyyyMM`, 예: 202610, 사용자 시간대 기준)에 첨부한다. 없으면 할 일을, 카테고리도 없으면 카테고리까지 만든다 (`share/`) |

## 구조

```
im.flare.todo/
├── FlareTodoApp.kt        # Application + AppGraph(수동 DI)
├── MainActivity.kt        # 내비게이션 (login, list, detail, edit, categories, shares, settings)
├── data/                  # ApiClient(OkHttp), SessionStore(DataStore), TodoRepository, DTO
├── auth/BiometricLogin.kt
├── notify/PinnedNotifier.kt   # 알림 + WorkManager 15분 주기 동기화 + 부팅 후 복구
├── download/AttachmentDownloader.kt
├── share/                 # 공유 메뉴 "Flare Todo 비용" (이번 달 비용 할 일에 사진 첨부)
└── ui/                    # Compose 화면
```

## 알아둘 점

- 서버 토큰 유효기간이 24시간(`jwt.expiration`)이라, 앱을 하루 넘게 열지 않으면 상태표시줄 알림은 마지막 내용에서 멈춘다. 앱을 열어 지문 로그인하면 다시 동기화된다.
- 웹에서 바꾼 내용은 최대 15분(WorkManager 최소 주기) 뒤, 또는 알림의 "새로고침"을 누르거나 앱을 열 때 반영된다.
- 첨부 CDN 쿠키는 `Domain=flare.im` 이라 운영 서버(`todo.flare.im`)에서만 본문 이미지·다운로드가 동작한다 (웹의 localhost 제약과 같다).
