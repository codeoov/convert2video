# CLAUDE.md — convert2video 단일 소스 (SSOT)

이 파일은 Cursor와 Claude Code 양쪽이 읽는 **단일 소스(Single Source of Truth)**다.
제품 범위·핵심 규칙·재발 방지·폴더 구조·용어 사전 모두 이 파일에 있다.
`.cursor/rules/` 파일들은 Cursor 규칙 엔진 및 Harness 번들러가 자동 로드하기 위한 포인터 스텁만 남긴다.

## Default Workflow (No Harness Trigger)

Harness가 트리거되지 않는 경우(아래 중 하나)에만 메인 에이전트가 직접 코딩한다.

### 트리거 예외 조건 (`sprint-harness-gate.mdc` §5 기준)

- 사용자가 **「하네스 생략」「직접 고쳐」「빠르게만」** 등으로 명시한 경우
- **질문만** (설명·리뷰·원인 분석, 코드 변경 없음)
- **단일 파일·1~5줄** 명시적 핫픽스 (스프린트·루프 키워드 없음)

애매하면 Harness를 탄다.

### 직접 코딩 절차 (인라인 계획 후 메인 코딩 — STEP 3)

1. **범위 확인**: 수정 대상 파일이 1개이고 변경 줄이 소규모임을 확인한다.
2. **인라인 계획**: 별도 Task 없이 메인 에이전트가 변경 내용을 간략히 서술한다.
3. **직접 수정**: `app/**` 코드 또는 `.cursor/**`·`docs/**` 문서를 직접 Edit/Write한다.
4. **완료 답변**: § Core Conventions §3 형식 — 한 일·수정 파일·확인 방법·다음 스텝·남은 리스크.

> **주의**: 파일 2개 이상 변경이 예상되거나 스프린트·루프·하네스 키워드가 포함되면 즉시 Harness로 전환한다.

## Product Scope

### 1. 제품 한 줄

오디오 + 배경 이미지를 합쳐 MP4 영상으로 만들고 기기 갤러리에 저장하는 Android 앱. **변환·저장은 로컬**; 선택적 **Google YouTube·Drive OAuth 아웃바운드 업로드**(`youtube/`·`drive/`) 지원.

### 2. 현재 플로우

**셸·드로어**
- **홈** (`HomeScreen` / `AppDestination.Home`) — Record / Listen / ConvertedVideos **3탭 셸 destination** (`RecordScreen`·`RecordingsListTabContent`·`ConvertedVideosTabContent` 임베드). 변환 조립 UI가 아님. ConvertedVideos는 `HomeTab`이지 `AppDestination`이 아님 (live enum에 ConvertedVideos 없음).
- **드로어** (`AppDrawerContent`) — Convert를 포함해 **어디서 열어도** 항상 Home / Options / Trash / ErrorLog / Microphone Source **5행만**. Convert는 live 조립 destination이지 **드로어 행이 아님**. ConvertedVideos 드로어 행도 없음. **drawer→Convert 경로 없음.** 테마 컨트롤 없음 — Options only.

**변환 골든패스 (Listen CTA → Convert 조립 → 목록 보기/칩으로 Home + HomeTab.ConvertedVideos)**

1. **Listen 진입 (Convert CTA 3경로 — 본문 탭은 Convert 아님)**
   - (a) 아이템 **「...」** 메뉴 Convert → (b) 다중선택 TopBar Convert 칩 → (c) import 칩. 각각 `onConvertAudioItems` → `applyFilePickSaved` → `AppDestination.Convert`. MainActivity에서 **Saving 게이트 없음** (Saving 중이어도 Convert 진입 가능). **비선택모드 본문 탭은 no-op** (Convert 미진입).
   - import 칩 → `RecordingsListStateful` 내부 GetContent (`importAudioFromUri`) → `importedAudio` emit → `onConvertAudioItems` → 같은 `AppDestination.Convert`. **AudioPick 미진입**. import 칩만 Saving 중 비활성 (`navigationBlocked`).
2. **변환 조립** (`ConvertScreen` / `AppDestination.Convert`) — 배경·오디오 선택 요약 + 변환 시작; `conversionState` SSOT는 `ConvertViewModel`. **결과 목록 화면이 아님** (드로어 행도 아님).
3. **배경 선택** (`BackgroundPickScreen`)
   - 갤러리에서 이미지 추가 → `filesDir/backgrounds/` 복사 + Room 저장
   - 탭으로 선택(단일 선택), 롱프레스 삭제
4. **오디오 선택** (`AudioPickScreen`) — Listen import 및 Convert 내부 FilePick 재진입 공용. MediaStore 목록 (`AudioRepository`); 권한 없으면 GetContent 폴백. **항상 Convert로 복귀하지 않음.**
   - 완료: `onAudioBatchPicked` → `applyFilePickSaved` → `AppDestination.Convert`
   - 뒤로: `onNavigateBack` → `navigateToLanding` → `AppDestination.Home`
5. **변환 시작**
   - 선택된 배경 + 오디오 URI → `WorkManager` unique work `audio_to_video_conversion`
   - `ConversionWorker` → `VideoConverter`(Media3) → `MediaStoreSaver`
6. **진행 UI**
   - `conversionState: StateFlow<ConversionUiState>` — WorkInfo **phase** (`WorkInfoUiPhase`) + **payload** (`toConversionUiState` in `ConvertViewModel`; 앱 재진입 후에도 복구)
   - 다이얼로그는 `ConvertScreen`에서 표시
7. **완료 다이얼로그 (Success)** — 자동 랜딩 없음. 버튼 2개를 섞지 말 것.
   - 확인 (`action_confirm`) = `onDismissResult`만 → **Convert에 잔류**. ConvertedVideos로 이동하지 않음.
   - 목록 보기 (`conversion_success_view_list`, en: View list / ko: 목록 보기) = dismiss + `onNavigateToConvertedVideos` → `AppDestination.Home` + `HomeTab.ConvertedVideos`. live `AppDestination.ConvertedVideos` 복원 금지.
   - Convert TopBar 칩(`drawer_converted_videos`)도 같은 `onNavigateToConvertedVideos` → `AppDestination.Home` + `HomeTab.ConvertedVideos`. Success 목록 버튼 키는 `conversion_success_view_list`이지 `drawer_converted_videos`가 아님.

### 3. 비범위 (당분간)

- 자체 호스팅 서버·범용 백엔드 동기화 (Retrofit/PHP/MySQL API 백엔드 등). 단, `desktopsync/` 로컬 LAN 1:1 데스크톱 페어링은 예외 — 구현은 보존하되 1.0에서는 Options에 숨기고 runtime을 dormant로 유지하며 자동 시작하지 않는다. `PairingServerService`(Ktor CIO)·`DesktopSyncController`는 로컬 Bearer 페어링만 사용하고 1.1 복원 후보로 남긴다. 클라우드·범용 백엔드 아님, 자체 계정 없음.
- **제한된 `backend/` 예외:** analytics 이벤트 수집, crash 보고 수집, billing entitlement verify/refresh/provider notification만 제공한다. 실제 라우트는 `POST /events`, `POST /crash`, entitlement 검증·갱신·provider notification 라우트이며 파일 동기화·범용 API·앱 계정 서버가 아니다. Android `AppDestination` 라우트와 무관한 별도 서버 엔드포인트다. 이 문서는 범위·소유권 기록이며 analytics/crash/billing end-to-end 런타임 검증을 주장하지 않는다.
- 범용 클라우드 파일 동기화·다운로드 서비스 (first-party sync 서버 없음)
- 다중 트랙 편집·자막·타임라인 에디터 (요청 시 별도 스프린트)

**범위 내(이미 제공):** 선택적 Google YouTube·Drive **아웃바운드** 업로드(OAuth; `youtube/`·`drive/` OkHttp REST)와 제한된 `backend/` analytics·crash·billing entitlement verify/refresh/provider notification 엔드포인트. 앱 자체 계정·파일 동기화 서버는 없음.

### 4. 권한

- 이미지/오디오 피커·저장 시 필요한 미디어 권한만. 과도한 권한 추가 금지.
- 블루투스 SCO 라우팅: `MODIFY_AUDIO_SETTINGS`(install-time normal). `BLUETOOTH_CONNECT` 없음 — 기기 이름(`productName`) 미사용.

### 5. 품질 기준 (런타임 포인트)

- 변환 중 앱을 내려도 WorkManager 작업이 이어지는지
- 실패 시 사용자에게 스택 대신 짧은 메시지
- 대용량 이미지는 short-side 제한(`VIDEO_SHORT_SIDE_PX`) 유지
- 콜드스타트 → Home 3탭 → Listen 다중선택 → Convert → Success **확인 ≠ 랜딩**(Convert 잔류). **Semantic assertion:** `action_confirm`은 `onDismissResult`만 호출하여 Convert에 남고, 「목록 보기」(`conversion_success_view_list`) 또는 TopBar 칩(`drawer_converted_videos`)만 `AppDestination.Home` 그다음 `HomeTab.ConvertedVideos`로 이동한다
- 데스크톱 페어링: `POST /pair/request`와 `GET /whoami`는 페어링·서버 식별 정보만 반환한다. `/upload`에는 기존 Bearer 페어링 토큰 인증만 적용하며 추가 상업적 접근 상태 검사나 게이트를 두지 않는다
- Options Desktop sync: DesktopSync 섹션은 1.0 Options 화면에 노출하지 않으며 runtime도 dormant 상태로 두고 자동 시작하지 않는다. 구현은 보존한다. `startDesktopSync()`는 `Starting`/`Running` 중복을 막고 `Failed`·시작 예외 후 재시도 가능해야 하며, 구현 복원은 1.1 후보로 남긴다
- Phase 2·3 검증 포인트: long audio exact boundary enqueue, Options root의 DesktopSync 비노출·dormant 정책, cold-start 무리스닝, 명시적 pairing/whoami 식별 정보, serverState/start 예외 및 Failed 재시도. 아래 테스트명은 계측 테스트 실행 결과가 아니라 실제 클래스·메서드 존재 및 compile/checks 확인 대상으로만 기록한다
- 현행 1.0 변환 경로에는 상업적 접근 제한이나 접근 제한 UI가 없다. DesktopSync production 경로도 동일한 정책을 따르며 로컬 Bearer 페어링만 사용한다
- Listen import → Convert → 같은 버튼 정책 (확인은 Convert 잔류; 목록 보기/칩만 위 랜딩)
- Listen **비선택모드 본문 탭 → no-op** (Convert는 「...」/다중선택/import만)
- Convert를 포함해 어디서 열어도 드로어는 Home / Options / Trash / ErrorLog / Microphone Source **5행만** (Convert·ConvertedVideos 행 없음). 테마 컨트롤 없음 — Options only.
- 마이크 소스 Bluetooth + SCO 미연결 → 오류 없이 내장 마이크 폴백. WAV Bluetooth는 44100→16000→8000 샘플레이트 폴백(AAC는 프레임워크 리샘플).
- 녹음 중 통화/VoIP **AudioFocus** 선점 시 자동 일시정지·포커스 회복 시 자동 재개(사용자 수동 pause는 재개하지 않음). `READ_PHONE_STATE` 없음 — 포커스를 요청하지 않는 앱은 놓칠 수 있는 휴리스틱.

## Core Conventions

> 원본 문서 제목: 핵심 규칙 (Kotlin) — convert2video

**한 줄**: Kotlin+Compose 로컬 앱 / Room·Media3·WorkManager / 용어 `background`·`conversion`·`video` / 목차는 `rules-index-kotlin.mdc`.

---

### 1. 스택·금지

- **클라이언트**: Kotlin, Jetpack Compose, MVVM, StateFlow, Room, Coil, Media3 Transformer, WorkManager.
- **클라이언트에서 없음(의도적)**: 범용 자체 서버/API 레이어, Retrofit, Hilt, PHP/MySQL API 레이어, Flutter. 단, 별도 `backend/`에는 analytics·crash·billing entitlement verify/refresh/provider notification 전용 PHP 엔드포인트가 존재하며 파일 동기화·범용 API 서버가 아니다. **OkHttp 정책:** Android `okhttp3`는 `youtube/`·`drive/`에서 Google REST 아웃바운드용, `analytics/`에서 텔레메트리(`/events`·`/crash`) 아웃바운드용, `billing/`에서 entitlement verify/refresh 아웃바운드용으로만 허용한다. 그 외 패키지·범용 HTTP 클라이언트 추가는 명시 요청 전까지 금지. **자동 검사:** `scripts/check-forbidden-imports.ps1`는 `retrofit2`·`dagger.hilt`·`gson`·happy_v12·flutter 등만 금지 — OkHttp 패키지/경로 allowlist는 **미구현**; OkHttp 범위는 본 정책 + 리뷰에 의존. 스크립트 헤더 주석에 okhttp3가 「allowlisted」라고 적힌 부분은 **레거시·오해 소지** — 실제 동작은 okhttp3가 금지 목록에 없을 뿐(경로 allowlist 검사 없음).
- **Desktop Sync 구현 예외:** Ktor(`ktor-server-core`/`ktor-server-cio`)는 `desktopsync/` 전용 로컬 LAN 임베디드 서버에만 사용하며 그 외 패키지의 Ktor import 금지. 구현은 보존하지만 1.0에서는 Options에 숨기고 `PairingServerService`를 시작하거나 호출하지 않으며 runtime을 dormant로 유지한다. `PairingServerService`는 1.1 복원 시 `0.0.0.0:47321`에서 4개 라우트만 제공: `GET /ping`(인증 없음), `POST /pair/request`(60초 단일 신뢰 페어링 승인/거부), `GET /whoami`(Bearer), `POST /upload`(Bearer, WAV, MAX_UPLOAD_BYTES(500MiB)). `/pair/request`와 `/whoami`는 페어링·서버 식별 정보만 반환한다. `/upload`는 기존 Bearer 페어링 토큰만으로 동작하며 추가 접근 상태 검사나 게이트를 두지 않는다. `DesktopSyncController` 싱글톤; Activity/VM은 Controller만 참조. `NsdAdvertiser` `_c2vsync._tcp`. `PairingTokenStore` Keystore AES-256-GCM; DataStore 파일 `pairing_token.preferences_pb`(SettingsRepository 분리). 인증 전 multipart 파싱 금지, URI/path 로그 금지. 자체 계정·범용 동기화·외부 API 서버 확장 금지. Ktor도 스크립트 금지 목록에 없을 뿐 경로 allowlist 검사 없음 — `desktopsync/` 외 범위 확장은 본 정책 위반. 1.1 복원 후보로 남긴다.
- **패키지**: `com.example.convert2video` 만. `happy_v12`·모임 앱 패키지/용어 이식 금지.

---

### 2. 용어 (최소)

- 배경 이미지 `background` / 변환 `conversion` / 결과 영상 `video` / 오디오 `audio`.
- 변환 작업명·Worker 키는 기존 상수 재사용 (`ConversionWorker`, `CONVERSION_UNIQUE_WORK_NAME` / `CONVERSION_WORK_NAME` 별칭 등).
- **금지 이식 용어**(이전 앱): gathering, booking, meetup, reservation, my_page, my_info (도메인 의미로).
- 신규 도메인 용어는 § Terminology Glossary에 등록.
- 문서 검색·검증의 금지 용어 판정은 도메인 식별자와 단어 경계 기준으로 하며, 일반 문장 속 부분 문자열이나 보존 설명의 일치(false positive)는 금지 용어 사용으로 판정하지 않는다.

---

### 3. 로깅·에러·답변 마무리

- **로깅**: `utils/AppLogger`만 사용(도입 시). 직접 `android.util.Log.*` 금지. 민감 경로·URI 전체 로깅 금지.
- I/O·변환·DB는 try-catch, catch 비우지 않기.
- **개발**: Snackbar 등으로 상세 피드백 허용. **운영**: 시스템 코드·스택 사용자 노출 금지 → 정중한 Fallback 문자열.
- **작업 완료 답변**: (1) 한 일·수정 파일 (2) 확인 방법 (3) 다음 스텝 (4) 남은 리스크.
- 유저 룰 중복 금지 → `.cursor/user-rules-minimal-ko.md` 참고.

---

### 4. 네이밍

- 클래스·파일 PascalCase, 변수·함수 camelCase, Boolean은 `is`/`has`/`should`.
- **Screen** `XxxScreen.kt`, **ViewModel** `XxxViewModel.kt`, **Repository** `XxxRepository.kt`, **Worker** `XxxWorker.kt`, **Dao** `XxxDao.kt`.
- 모호한 이름 `data`/`info`/`temp` 금지.

---

### 5. Deprecated

- 새 코드에 Deprecated API 금지. 불가피 시 최소 범위 `@Suppress("DEPRECATION")` + TODO(날짜)·사유.

---

### 6. 테스트

- Unit `src/test/`, AndroidTest `src/androidTest/`, 패키지는 main과 동일.
- 클래스 `XxxTest` / `XxxAndroidTest`, 메서드 `success_` / `failure_` / `exception_` 접두.
- Given/When/Then 주석.

---

### 7. 재발방지 핵심 (상세는 § Anti-Repeat / Single-Source Rules)

- **의존성**: `gradle/libs.versions.toml` + `libs.xxx`. 버전 하드코딩 금지.
- **색**: `MaterialTheme.colorScheme` 우선. 임의 하드코딩 Color는 최소화.
- **변환**: Media3 `Transformer`는 메인(Looper) 스레드에서 구동. UI에서 장시간 변환 금지 → `ConversionWorker` + WorkManager.
- **배경 저장**: `BackgroundRepository` / Room `BackgroundImage` 단일 경로. 파일은 `filesDir/backgrounds/`.
- **상태(2-tier)**: `WorkInfo` phase = `WorkInfoUiPhase`·`toWorkInfoUiPhase()` (`ui/shared/WorkInfoUiPhase.kt`); conversion payload = `toConversionUiState` (`ConvertViewModel`); YouTube payload = `toYouTubeUploadUiState` (`YouTubeUploadViewModel`). Screen에서 `WorkInfo.State` 분기 복제 금지.
- **backend/결제**: backend는 analytics/crash와 billing entitlement verify/refresh/provider notification만 허용한다. Android OkHttp는 `youtube/`·`drive/`·`analytics/`·`billing/`에서만 outbound 용도로 사용하며, `BACKEND_BASE_URL`·`PRO_PRODUCT_ID`·`pro_lifetime_unlock`은 각 canonical owner에서 단일 관리한다.
- **l10n**: 사용자 문자열은 `stringResource` / `getString`. 비-Composable 콜백 안 `stringResource` 금지.

---

### 8. 어디에 있나

- **폴더 트리**: § Folder Structure (이 파일)
- **기능·플로우**: § Product Scope (이 파일)
- **용어 표**: § Terminology Glossary (이 파일)

## Anti-Repeat / Single-Source Rules

> 원본 문서 제목: 재발 방지 규칙 (Kotlin) — convert2video

> `.kt/.kts` 작업 시 로드. 요약은 § Core Conventions.

### 1. 의존성·빌드

- 버전·라이브러리는 `gradle/libs.versions.toml` → `libs.xxx`.
- `app/build.gradle.kts`에 버전 문자열 하드코딩 금지.
- `namespace` / Kotlin package = `com.example.convert2video`; `applicationId` = `com.convert2video`.

### 2. UI

- 색은 `MaterialTheme.colorScheme` 우선.
- 로딩/진행: 기존 `LinearProgressIndicator` 패턴 유지하거나 공통 컴포넌트로 한곳에 모을 것(중복 인디케이터 난립 금지).
- 화면에서 Room Dao/`VideoConverter` 직접 호출 금지 → ViewModel·Repository·Worker.

### 3. 데이터·파일

- 배경 CRUD는 `BackgroundRepository`만.
- DB 접근은 `AppDatabase` + Dao. UI에서 Dao 직접 사용 금지.
- 배경 파일 디렉터리: `BackgroundRepository.backgroundsDir` 단일 소스.
- 삭제 시 DB row + 파일 동시 정리(현행 `deleteBackground` 패턴).

### 4. 변환·WorkManager

- Unique work 이름 literal SSOT: `video/ConversionWorkNames.kt` → `CONVERSION_UNIQUE_WORK_NAME` (`"audio_to_video_conversion"`). **별칭:** `ConvertViewModel.CONVERSION_WORK_NAME`, `RecordingAutoConvertTrigger.MANUAL_CONVERSION_WORK_NAME`(convert import 없이 동일 값). 문자열 리터럴 중복 금지 — 상수 import만.
- Unique work 이름(수동 Convert): 위 별칭·literal. `conversionState`는 수동 unique만 구독.
- Unique work 이름(녹음 후 자동): `autoConvertWorkName` (`auto_convert_` + `Uri.encode`) — **수동 unique와 다른 work name**; `CONVERSION_UNIQUE_WORK_NAME`·별칭 재사용 금지. `video/`는 `ui.screens.convert` / `ui.screens.youtube_upload` import 금지. YouTube authorized → `beginUniqueWork`+`.then(YouTubeUploadWorker)` Private; !auth convert-only. wifiOnly read failure → CONNECTED, not convert-only.
- Worker 입력 키: `ConversionWorker.KEY_*` 상수만.
- **WorkInfo 2-tier 매핑:** (1) phase = `WorkInfoUiPhase`·`toWorkInfoUiPhase()` (`ui/shared/WorkInfoUiPhase.kt` SSOT; ENQUEUED/RUNNING/BLOCKED → Active). (2) payload — conversion: `toConversionUiState` / aggregate·batch helpers (`ConvertViewModel.kt`); YouTube: `toYouTubeUploadUiState` (`YouTubeUploadViewModel.kt`). Screen·Composable에서 `WorkInfo.State` when 분기 복제 금지.
- `conversionState`·`startConversion`·`dismissConversionResult` — `ConvertViewModel` 단일 소스. `BackgroundPickViewModel`에 잔존 금지.
- `VideoConverter.convert`는 Flow 수집·Transformer 생성 모두 Looper 있는 스레드(보통 Main). Worker 내부에서 준수.
- **워터마크 게이트:** `ConversionWorker`는 export 전 `withEntitlementSnapshot`으로 `EntitlementRepository.isProSnapshot()`을 **한 번** 읽고, 두 `videoConverter.convert()` 호출 모두 `applyWatermark = !isPro` 전달. 스냅샷 실패는 false(워터마크 유지). 변환 시작 후 구매가 완료돼도 이미 시작한 작업의 정책은 불변. `VideoConverter.convert(applyWatermark=true)` 기본값·`WatermarkCompositor` 구현은 변경 금지 — 호출부 gate만.

### 4-1. 공통 헬퍼 (internal SSOT)

- `utils/AppString.kt` — `Context.appString` / `AndroidViewModel.appString` (`@StringRes`; LaunchedEffect·콜백 안 `stringResource` 대체). 패키지 내부 SSOT.
- `utils/RequireApplication.kt` — `Context.requireApplication()` — `applicationContext as Application` 단일 경로; 실패 시 `AppLogger.e` + `IllegalArgumentException`.
- `ui/shared/WorkInfoUiPhase.kt` — `WorkInfoUiPhase`, `WorkInfo.toWorkInfoUiPhase()` — WorkInfo 4-phase tier SSOT. Options `hasPendingConstrainedYouTubeUpload`는 `isPendingConstrained`(ENQUEUED|BLOCKED) 사용 — Active 전체와 혼동 금지.

### 5. l10n

- 사용자 노출 문자열: `stringResource` / `context.getString`.
- `LaunchedEffect`·콜백·`semantics { }` 람다 안에서 `stringResource` 호출 금지 → Composable 본문에서 미리 `val`.

### 6. 에러

- catch 비우지 않기. 로그 + 사용자 Fallback.
- 운영 빌드에서 예외 메시지·스택을 Snackbar에 그대로 넣지 않기.

### 7. 금지 이식

- Retrofit/Hilt/PHP·MySQL API 백엔드 레이어를 Android 앱에 복사·추가하지 않음. 별도 `backend/`는 analytics·crash·billing entitlement verify/refresh/provider notification 전용 서버 예외이며 파일 동기화·범용 API가 아니다. **OkHttp 정책:** Android `okhttp3`는 `youtube/`·`drive/`에서 Google REST 아웃바운드용, `analytics/`에서 텔레메트리(`/events`·`/crash`) 아웃바운드용, `billing/`에서 entitlement verify/refresh 아웃바운드용으로만 허용한다. 그 외 패키지·범용 HTTP 클라이언트 추가는 명시 요청 전까지 금지. **자동 검사:** `check-forbidden-imports.ps1`는 retrofit2/hilt/gson 등만 금지; OkHttp 경로 allowlist 없음 — 정책 + 리뷰.
- happy_v12 Atomic `5_pages` / Route.kt / gathering 플로우 이식 금지.
- 검사: `scripts/check-forbidden-imports.ps1`(retrofit2/hilt/gson/… 금지), `check-terminology-forbidden.ps1`, `check-logging-forbidden.ps1`.

### 8. 한 줄 요약

> libs.toml · Repository/Worker 단일 경로 · WorkInfoUiPhase + payload mapper 2-tier · ConversionUiState/YouTubeUploadUiState 매핑 복제 금지 · unique work literal SSOT(ConversionWorkNames) / 수동 vs auto_convert_ 별도 work name · youtube·drive·analytics·billing OkHttp only · `BACKEND_BASE_URL`/`PRO_PRODUCT_ID`/`pro_lifetime_unlock` 단일 소스 · 이전 앱 스택·용어 이식 금지.

## Folder Structure

> 원본 문서 제목: 프로젝트 구조 (Kotlin) — convert2video

> 요약·목차: § Core Conventions, `rules-index-kotlin.mdc`.
>
> **문서 범위:** 본 §·Architecture Equivalence는 **이미 배포된 main 패키지 구조**를 기술한다. Phase 5 문서 동기화는 코드 변경 없음; working tree의 미커밋·범위 외 변경과 **별개**이며 git 전체가 clean하다고 가정하지 않는다.
>
> 별도 `backend/`는 Android `com.example.convert2video` 패키지 트리가 아니며, `backend/public/index.php`가 analytics/crash와 billing entitlement verify/refresh/provider notification의 제한된 서버 라우트를 등록한다. Android `AppDestination`과 혼동하지 않는다.

### 1. 패키지 구조

```
com.example.convert2video/
├── MainActivity.kt             # AppDestination enum(내부) + rememberSaveable 라우팅 — live: Home·BackgroundPick·AudioPick·Options·ErrorLog·Convert·Trash·MicrophoneSource (ConvertedVideos는 route 아님; 레거시 saved name `"ConvertedVideos"` restore → Home + HomeTab.ConvertedVideos); LanguagePrompt gate + apply/`setLanguagePromptShown`/recreate 소유
├── Convert2videoApplication.kt # Application — AppLogger.installPersistSink(E/W → ErrorLogRepository) 설치
├── data/
│   ├── AppDatabase.kt          # Room DB 싱글톤 (v9)
│   ├── AudioItem.kt            # data class (MediaStore 오디오 항목)
│   ├── AudioRepository.kt      # MediaStore 오디오 쿼리
│   ├── BackgroundDao.kt
│   ├── BackgroundImage.kt      # Entity
│   ├── BackgroundRepository.kt
│   ├── ConversionHistoryRepository.kt  # conversion_records CRUD·세그먼트 이력
│   ├── ConversionRecord.kt     # Entity (conversion_records)
│   ├── ConversionRecordDao.kt
│   ├── ConvertedVideoRepository.kt  # ConvertedVideo data class + MediaStore 조회·삭제·이름변경
│   ├── DisplayNameValidation.kt # FORBIDDEN_DISPLAY_NAME_CHARS·stem 검증 SSOT
│   ├── ErrorLogEntry.kt        # Entity (error_log_entries)
│   ├── ErrorLogDao.kt
│   ├── ErrorLogRepository.kt
│   ├── RecordingAudioMapping.kt # RecordingRecord → AudioItem 매핑
│   ├── RecordingRecord.kt      # Entity (recording_records, v9 stable backupId)
│   ├── RecordingDao.kt
│   ├── RecordingRepository.kt  # 녹음 파일 인덱싱·삭제·FileProvider URI (Engine/Service import 금지)
│   ├── RecordingSchedule.kt    # Entity (recording_schedules, v6) — overnight 허용·동일 분 금지
│   ├── RecordingScheduleDao.kt
│   ├── RecordingScheduleRepository.kt  # 스케줄 CRUD·쓰기 require / 읽기 soft-normalize (Alarm/Context 없음)
│   ├── SettingsRepository.kt   # DataStore 설정 (테마·녹음 포맷·LanguageOption·languagePromptShown·youtubeAutoUploadEnabled·driveAutoUploadEnabled·pendingCountdown·microphoneSource 등)
│   ├── UploadHistoryRepository.kt  # upload_records CRUD
│   ├── UploadRecord.kt         # Entity (upload_records)
│   ├── UploadRecordDao.kt
│   └── TrashPurgeWorker.kt     # daily WorkManager purge → TrashRepository.purgeExpired(DEFAULT_RETENTION_DAYS)
├── desktopsync/
│   ├── PairingServerService.kt  # 보존 구현. 1.0에서는 시작·호출하지 않고 runtime dormant; 1.1 복원 후보. 복원 시 Ktor CIO FGS — GET /ping·POST /pair/request·GET /whoami·POST /upload; Bearer·multipart 수신·timeout·meter 규칙 유지; pairing/whoami는 식별 정보만 반환
│   ├── DesktopSyncController.kt # 프로세스 싱글톤 UI 브리지 — serverState/pairingRequest/isPaired/pairedDeviceName StateFlow; 화면 재구성 시 유지; Service 재시작 시 pending pairing 취소·토큰·paired만 DataStore 복원. getInstance(application: Application) / getInstance(context: Context); LocalBinder 직접 구독 금지 — Activity/VM은 getInstance만
│   ├── PairingProtocol.kt       # 라우트·에러코드·업로드 필드·MAX_UPLOAD_BYTES·PAIRING_REQUEST_TIMEOUT_MILLIS SSOT (internal)
│   ├── PairingTokenStore.kt     # PairingTokenStore 인터페이스 + AndroidKeystorePairingTokenStore — Keystore AES-256-GCM, pairing_token.preferences_pb (SettingsRepository 분리)
│   ├── PairingAuth.kt           # generatePairingToken / bearerTokenFromHeader / bearerTokenMatches (파일; 클래스 아님) (internal)
│   └── NsdAdvertiser.kt         # _c2vsync._tcp; MulticastLock; NetworkCallback 재광고; MAX_REGISTRATION_ATTEMPTS=3 후 fail-open
├── ui/
│   ├── screens/                # 화면·ViewModel 도메인 패키지 (flat ui/*.kt Screen/VM 금지)
│   │   ├── convert/            # ConvertScreen, ConvertViewModel (toConversionUiState·CONVERSION_WORK_NAME alias) — live 조립 destination (드로어 행 아님). Home+HomeTab.ConvertedVideos는 Success 「목록 보기」또는 TopBar 칩만 (자동 아님; 확인은 Convert 잔류)
│   │   ├── home/               # HomeScreen — Record/Listen/ConvertedVideos 3탭 셸 destination; RecordScreen·RecordingsListTabContent·ConvertedVideosTabContent 임베드
│   │   ├── audio_pick/         # AudioPickScreen/ViewModel, AudioSourceFilter*
│   │   ├── background_pick/    # BackgroundPickScreen/ViewModel (BackgroundPickContent 포함)
│   │   ├── converted_videos/   # ConvertedVideosTabContent + ConvertedVideosViewModel + ConvertedVideosHostChrome + ConvertedVideoFolderLabel.kt (YouTube Composable 잔류). fullscreen ConvertedVideosScreen wrapper 없음; filename ConvertedVideosScreen.kt 레거시 OK
│   │   ├── error_log/          # ErrorLogScreen/ViewModel
│   │   ├── language_pick/      # LanguagePickerScreen + resolveLanguagePromptGate / LanguagePromptUi — first-launch language chooser (no ViewModel)
│   │   ├── options/            # OptionsScreen/ViewModel, RecordingScheduleViewModel, RecordingCountdownViewModel, OptionsWifiOnlyUploadContent, OptionsDriveAccountContent, OptionsYoutubeAccountContent, OptionsRecordingFormatContent, OptionsNoiseReductionContent, OptionsRecordingBackupContent, OptionsRecordingScheduleContent, OptionsRecordingCountdownContent, ContactUsIntent.kt (SUPPORT_EMAIL·buildContactUsIntent — mailto Intent 빌더, internal)
│   │   ├── record/             # RecordScreen/ViewModel, RecordUiState (≠ record/ 엔진 패키지) — Home Record 탭 임베드
│   │   ├── recordings_list/    # RecordingsListTabContent/ViewModel/Navigation/RecordingsListScreen, ListenListScrollPolicy, RecordingsListSortOrder/Mapping, RecordingsListConversionFilter/Mapping — Home Listen 탭 임베드 (top-level destination 없음); onConvertAudioItems → Convert (Saving 게이트 없음); **비선택모드 본문 탭 no-op**; import 칩은 내부 GetContent (importAudioFromUri) → onConvertAudioItems (AudioPick 미진입; Saving 중 칩 비활성). ConvertedVideos 랜딩은 Convert 목록 보기/칩만
│   │   ├── trash/              # TrashScreen/ViewModel — 통합 휴지통 목록 (복원/영구삭제)
│   │   ├── microphone_source/  # MicrophoneSourceScreen/ViewModel — 드로어 5번째 행 destination. SegmentedControl Default/Bluetooth + 연결 힌트. OptionsNoiseReductionContent 패턴 로컬 복제(options import 금지)
│   │   └── youtube_upload/     # YouTubeUploadViewModel + YouTubeUploadUiState만 (Composable은 converted_videos)
│   ├── shared/                # 화면 간 공유 UI 상태·포맷·Drawer (Sprint 2)
│   │   ├── ConversionUiState.kt     # sealed UI 상태 타입만 — phase는 WorkInfoUiPhase; payload 매핑은 ConvertViewModel
│   │   ├── WorkInfoUiPhase.kt       # WorkInfoUiPhase·toWorkInfoUiPhase() — phase tier SSOT (internal)
│   │   ├── MediaDurationFormat.kt   # formatMediaDurationMs / MediaDurationStyle (Timer·ListRow SSOT)
│   │   └── AppDrawerContent.kt      # Modal drawer 본문 — 정확히 5행 Home/Options/Trash/ErrorLog/Microphone Source (Convert/ConvertedVideos 행 금지); 테마 컨트롤 없음 — Options only; components import 허용
│   ├── components/             # 공통 Compose 위젯 (theme에 두지 않음) — Sprint1 SSOT; Sprint2는 수정 안 함. shared Drawer가 components를 import해도 됨
│   │   ├── buttons/            # SoftChipButton, AccentCtaButton, SoftIconButton, …
│   │   ├── badges/             # SectionStepBadge, SectionLabel, StatusBadge, …
│   │   ├── cards/              # C2vCard
│   │   ├── controls/           # SegmentedControl, StepperControl, C2vSwitch, DropdownSelector
│   │   ├── layout/             # GradientThumbnailPlaceholder, DashedDropZone, SegmentPreviewBar
│   │   └── pickers/            # RecordingScheduleEditDialog (TimePicker×2 내장; TimePickerXxx 분리 금지)
│   └── theme/                  # Color, Theme, Type, Shapes (토큰만 — 공통 위젯 금지)
├── utils/
│   ├── AppLogger.kt            # e/w/d + installPersistSink (persist sink, Sprint 5-4)
│   ├── AppString.kt              # Context/AndroidViewModel appString — @StringRes SSOT (internal)
│   ├── InstallId.kt             # public Context.installId() — 설치별 익명 UUID 생성·SharedPreferences 저장 SSOT
│   └── RequireApplication.kt     # requireApplication() — Application cast SSOT (internal)
├── store/                      # YouTube/Drive capability SSOT를 제공하는 스토어 플레이버 구조
│   └── StoreCapabilities.kt    # BuildConfig.STORE_ID → isGoogleServicesAvailable·supportsYouTube·supportsDrive. `StoreCapabilities.current` 단일 진입점; Screen/VM/Worker는 BuildConfig 직접 참조 금지
├── youtube/                    # YouTube REST·Upload Worker — outbound OkHttp (okhttp3) only
│   ├── YouTubeAuthGateway.kt   # (src/main) store-neutral 인증 인터페이스 + AuthorizationOutcome
│   ├── YouTubeApiClient.kt     # OkHttp REST client
│   ├── YouTubeUploadWorker.kt  # !capabilities.supportsYouTube → Result.success() skip
│   └── YouTubeErrorMessages.kt
│   # 구현: src/google/…/youtube/YouTubeAuthManager.kt (GMS Identity) + createYouTubeAuthGateway()
│   #       src/huawei/…/youtube/YouTubeAuthGatewayHuawei.kt (no-op) + createYouTubeAuthGateway()
├── drive/                      # Google Drive REST·auto-upload Worker — outbound OkHttp (okhttp3) only
│   ├── DriveAuthGateway.kt     # (src/main) store-neutral 인증 인터페이스 + DriveAuthorizationOutcome
│   ├── GoogleDriveApiClient.kt # OkHttp REST client
│   ├── DriveAutoUploadWorker.kt # !capabilities.supportsDrive → Result.success() skip
│   ├── DriveAutoUploadTrigger.kt # capability 게이트가 authorization 조회보다 선행
│   └── DriveErrorMessages.kt
│   # 구현: src/google/…/drive/GoogleDriveAuthManager.kt (GMS Identity) + createDriveAuthGateway()
│   #       src/huawei/…/drive/DriveAuthGatewayHuawei.kt (no-op) + createDriveAuthGateway()
├── billing/                   # Pro 일회성 entitlement·provider 연동 — outbound OkHttp only
│   ├── BillingGateway.kt      # store-neutral billing 계약 + `PRO_PRODUCT_ID`/`pro_lifetime_unlock`·`PurchaseResult`·`BillingGatewayOwner`(process-wide) SSOT
│   ├── EntitlementApiClient.kt # backend `POST /entitlement/verify`·`/entitlement/refresh(jwt, installId)` OkHttp (HTTPS only; placeholder URL → NotConfigured)
│   ├── EntitlementStore.kt    # `entitlement.preferences_pb` DataStore + Keystore AES-256-GCM JWT/pending 암호화 계약
│   └── EntitlementRepository.kt # process-wide singleton actor — `isProHot: StateFlow<Boolean?>`·`isProSnapshot()`·purchase/restore mutex·ProcessLifecycle foreground refresh. `getInstance(context)` 유일 진입점 (OptionsViewModel·ConversionWorker 공유)
│   # 구현: src/google/…/billing/GoogleBillingGateway.kt (PBL) + BillingGatewayFactory.createBillingGateway()
│   #       src/huawei/…/billing/HuaweiBillingGateway.kt + HuaweiPurchaseResultParser.kt + createBillingGateway()
├── analytics/                  # telemetry /events·/crash outbound OkHttp (okhttp3) only
│   ├── TelemetryApiClient.kt   # POST /events·/crash (internal)
│   ├── AnalyticsReporter.kt    # process singleton + EVENT_* 상수 3개
│   └── CrashReporter.kt        # UncaughtExceptionHandler + SharedPreferences persist/flush
├── record/                     # 마이크 캡처·포그라운드 Service (Phase 1)
│   ├── RecordingFormat.kt      # AAC/WAV enum 단일 정의 (재선언 금지)
│   ├── RecordingScheduleRepeatMode.kt  # ONCE/WEEKLY/DAILY SSOT — Room `.name`; 쓰기 require / 읽기 soft
│   ├── RecordingScheduleAlarmScheduler.kt  # AlarmManager register(Boolean)/cancel + nextTrigger/stopTrigger (setExact 성공 후 cancel; 권한 false→cancel stale; RecordingAlarmBackend seam; ScheduledRecordingReceiver FQCN·action 상수). C3: 통화 pause가 RTC STOP을 미루지 않음
│   ├── RecordingCountdownAlarmScheduler.kt  # Quick Timer ELAPSED_REALTIME_WAKEUP START/STOP (own backend seam; RTC_WAKEUP / RecordingAlarmBackend.setExact 재사용 금지; requestCode -71001/-71002; PI extras 없음). C2: 통화 pause 시 cancelStop + resume 시 registerStopRemainingMs
│   ├── ScheduledRecordingReceiver.kt  # Manifest 등록 — 예약 START/STOP + countdown START/STOP → RecordingController (예약 extras SSOT; countdown extras 없음; COUNTDOWN ≠ handleStart/handleStop). C3: 예약 RTC STOP 무보정
│   ├── BootRecordingScheduleReceiver.kt  # Manifest 등록 — 부팅 후 stale 알람 재등록
│   ├── ExactAlarmPermission.kt # SCHEDULE_EXACT_ALARM 헬퍼 (권한만; 스케줄링 아님)
│   ├── NoiseReductionMode.kt   # DeviceDefault/On/Off enum SSOT (SettingsRepository import만)
│   ├── MicrophoneSource.kt     # Default/Bluetooth enum SSOT (SettingsRepository import만)
│   ├── MicrophoneSourceRouting.kt  # Bluetooth SCO 탐색·라우팅 (productName 금지; BLUETOOTH_CONNECT 없음)
│   ├── NoiseSuppressionEffect.kt  # AudioEffect noise suppression 래퍼
│   ├── RecordingExtension.kt   # RecordingFormat.name → 파일 확장자 SSOT
│   ├── RecordingBackupManifest.kt # SAF manifest + backup identity filename SSOT
│   ├── RecordingBackupTrigger.kt  # backup Work input keys + per-ID unique work name SSOT
│   ├── RecordingBackupWorker.kt   # write-through ACTIVE/TRASHED/REMOVE SAF worker
│   ├── RecordingBackupReconciler.kt # reconnect-time manifest → local recording/trash restore
│   ├── RecordingStorageEstimator.kt  # 녹음 예상 용량 추정
│   ├── C2vRecordingNames.kt    # Music/C2V 경로·네이밍·claimUniqueDestFile
│   ├── CallAudioFocusMonitor.kt  # AudioFocus 휴리스틱 통화 감지 (권한 0; READ_PHONE_STATE 없음; 100% 아님)
│   ├── RecordingEngine.kt      # 상태 머신 + AudioCaptureBackend (`microphoneSourceHot`/`noiseReductionModeHot` 동기 읽기)
│   ├── MediaRecorderAacBackend.kt  # AAC 캡처; Bluetooth면 MicrophoneSourceRouting (샘플레이트 폴백 없음)
│   ├── AudioRecordWavBackend.kt    # WAV 캡처; Bluetooth면 SCO 라우팅 + 44100→16000→8000 폴백
│   ├── WavHeader.kt            # 44바이트 PCM WAV 헤더 순수 함수
│   ├── RecordingService.kt     # bind+start 이중 모드 포그라운드 Service; AudioFocus pause/resume (`pausedByCallDetection`); C2 countdown STOP 보정 / C3 예약 RTC STOP 무보정
│   ├── RecordingController.kt  # ViewModel용 싱글톤 진입점 (bind는 내부)
│   ├── RecordingQuickAction.kt # Tile·Glance 위젯 공통 onClick SSOT (performRecordingQuickClick·ReviewPending은 앱 열기·REQ_LAUNCH_TILE/WIDGET)
│   ├── QuickRecordWidgetColors.kt # Glance QuickRecord ColorProvider SSOT
│   ├── QuickRecordWidgetStateSync.kt # Controller.state collect → Glance updateAll (Tile collect 대칭)
│   ├── QuickRecordWidget.kt    # 1×1 Glance AppWidget (RecordingController만)
│   ├── QuickRecordWidgetReceiver.kt  # GlanceAppWidgetReceiver (exported=false; quickRecordGlanceWidget 단일 인스턴스)
│   └── RecordingTileService.kt # Quick Settings 타일 진입점 — RecordingController만 호출 (isActiveRecordingSession top-level internal 포함)
└── video/
    ├── ConversionWorkNames.kt  # CONVERSION_UNIQUE_WORK_NAME literal SSOT (internal)
    ├── C2vOutputNames.kt       # C2V_FOLDER 상수 + buildDisplayName
    ├── VideoConverter.kt       # Media3 Transformer 래퍼
    ├── VideoSegmentPlanner.kt  # 오디오 구간 분할 계획 (MIN_SEGMENT_DURATION_US·MAX_SEGMENT_COUNT)
    ├── ConversionWorker.kt     # WorkManager Worker
    ├── MediaStoreSaver.kt      # 결과 갤러리 저장 (Movies/C2V)
    └── RecordingAutoConvertTrigger.kt  # 녹음 인덱싱 후 lastUsedBackgroundPath 가드 → ConversionWorker plain 1건 (auto_convert_ unique; CONVERSION_UNIQUE_WORK_NAME 재사용 금지). 토글은 YouTube 체인 게이트 — authorized일 때만 읽기 (`!auth`/auth-throw → YouTube step omitted; toggle unread). YouTube authorized && youtubeAutoUploadEnabled() → YouTubeUploadWorker Private chain (wifiOnly constraints YouTube-only, KEY_ITEM_FAILED skip); 로깅된 convert-only skip: !auth (`youtube step omitted: not authorized`) / 토글 false (`youtube step omitted: toggle off`) / blank title (`youtube step omitted: blank title`); degrade convert-only: auth-throw / 토글 읽기실패 (`youtube chain degraded`); wifiOnly read failure → CONNECTED, not convert-only (`wifiOnly read failed; using CONNECTED`, not degrade). Public auth default=`isYoutubeAuthorizedFromPrefs`(Identity ctor 없음). MANUAL_CONVERSION_WORK_NAME(=CONVERSION_UNIQUE_WORK_NAME)으로 수동 unique 조회(`!isFinished` skip; non-CE fail-closed; CE rethrow). File.isFile false/outside → skip+clear; isFile Exception / confinement 판정 불가(IO/Security) → skip, clear 금지. Error ≠ Exception swallow.
```

### 1.3 스토어 플레이버

- `flavorDimensions += "store"` — `google`(`STORE_ID=google_play`, `GOOGLE_SERVICES_ENABLED=true`) / `huawei`(`STORE_ID=huawei`, `GOOGLE_SERVICES_ENABLED=false`). `buildFeatures.buildConfig = true`.
- `play-services-auth`는 `googleImplementation` 전용. GMS Identity를 쓰는 `YouTubeAuthManager`·`GoogleDriveAuthManager`는 `src/google/…`에만, huawei no-op gateway는 `src/huawei/…`에 둔다. `src/main`에는 `YouTubeAuthGateway`·`DriveAuthGateway` 인터페이스와 `create*AuthGateway()` factory 선언만.
- huawei runtime classpath는 `com.google.android.gms` 그룹을 exclude하여 Google 전용 인증 구현이 포함되지 않게 한다.
- Google 아웃바운드 기능(YouTube/Drive) 및 store별 billing 노출은 `StoreCapabilities.current`로 게이트 — Screen/VM/Worker/Trigger는 `BuildConfig` 직접 분기 금지.
- 플레이버별 Google 서비스 사용 가능 여부와 YouTube/Drive capability는 `StoreCapabilities`가 단일하게 결정한다. huawei 플레이버의 YouTube/Drive 인증 gateway는 no-op 정책을 따른다.
- `StoreCapabilities.supportsBilling` — google/huawei 모두 true, unknown store는 fail-closed(false). Options Pro 섹션은 `supportsBilling`일 때만 노출.
- billing은 `googleImplementation(libs.play.billing.ktx)`와 `huaweiImplementation(libs.huawei.iap)`로만 연결한다. `src/main`에는 store-neutral `BillingGateway` 인터페이스, 각 flavor `src/google`·`src/huawei`에 gateway 구현 + `createBillingGateway()` factory. AGConnect 실제 application/config 적용은 이 구조·Phase의 범위에서 제외하며(`agcp` plugin은 카탈로그 선언만·미적용), `agconnect-services.json`을 추가하거나 적용하지 않는다.

### 1.4 테스트

- **Unit**: `app/src/test/java/com/example/convert2video/…` (+ 플레이버 전용 `src/testGoogle/…`·`src/testHuawei/…`)
- **AndroidTest**: `app/src/androidTest/java/com/example/convert2video/…`
- main과 동일 패키지 거울 구조 유지.

### 2. 역할 분리

| 계층 | 역할 |
|------|------|
| `ui/` | Compose 테마·shared·components·screens. 비즈니스/파일 I/O 최소화 |
| `ui/screens/<domain>/` | 화면·ViewModel·도메인 UiState. 패키지 `ui.screens.<domain>` |
| `ui/shared/` | 화면 간 공유 Drawer·ConversionUiState·`WorkInfoUiPhase`·duration 포맷. `components` import 허용(삭제/인라인 금지). Sprint2는 `components/**` 수정 금지 |
| `ui/components/` | 공통 위젯(Sprint1 SSOT) — `ui/theme` 금지, `ui/components`만. Sprint2에서 내용 변경 금지 |
| `ui/screens/*/…ViewModel` | 상태·Repository/WorkManager 호출. WorkInfo **phase** = `WorkInfoUiPhase`; conversion **payload** = `ConvertViewModel.toConversionUiState`; YouTube **payload** = `YouTubeUploadViewModel.toYouTubeUploadUiState` |
| `data/` | Room Entity/Dao/DB + Repository (파일 복사·삭제·인덱싱) — **Model** |
| `video/` | 변환·저장·백그라운드 작업 + `ConversionWorkNames` |
| `record/` | 마이크 캡처 엔진·WAV/AAC 백엔드·포그라운드 Service·RecordingController·예약 알람 스케줄러 |
| `youtube/` | YouTube OAuth·REST·Upload Worker — outbound OkHttp only |
| `drive/` | Google Drive OAuth·REST·auto-upload Worker — outbound OkHttp only |
| `billing/` | Pro 일회성 billing gateway·`EntitlementApiClient`·`EntitlementStore`·`EntitlementRepository`(process-wide actor) + flavor gateway(`src/google`·`src/huawei`) — verify/refresh outbound OkHttp only; provider notification은 제한된 backend route. UI/Worker는 `EntitlementRepository`만 참조 (BillingClient·API·DataStore 직접 호출 금지) |
| `analytics/` | `TelemetryApiClient`·`AnalyticsReporter`·`CrashReporter`를 통한 텔레메트리 `/events`·`/crash` outbound OkHttp only |
| `desktopsync/` | 1.0에서는 Options에 숨기고 runtime을 dormant로 유지하는 로컬 LAN 데스크톱 페어링 서버·Controller·토큰 저장·mDNS 광고 — Ktor CIO 임베디드 서버 전용 (outbound REST 아님). 1.1 복원 후보이며 로컬 Bearer 페어링만 사용 |
| `utils/` | `AppLogger`·`AppString`·`InstallId`·`RequireApplication` 등 cross-cutting internal 헬퍼 |

- ViewModel에 `Context` 장기 보관 금지 → `AndroidViewModel`의 `Application`만.
- UI에서 `Transformer`/`VideoConverter` 직접 장시간 돌리지 않음 → Worker 경유.
- UI에서 `MediaRecorder`/`AudioRecord` 직접 호출 금지 → `RecordingController`(권장) / `RecordingService`/`RecordingEngine` 경유.
- ViewModel은 `bindService` 없이 `RecordingController.getInstance`만 호출.
- `RecordingRepository`는 Engine/Service/Controller를 import하지 않음.
- `RecordingScheduleRepository`는 Context/Alarm/파일 I/O 없음 — Dao thin wrapper + normalize SSOT.

### 3. 확장 시 배치

- 새 화면 → `ui/screens/<domain>/`
- 화면 간 공유 Drawer·포맷·ConversionUiState 타입 → `ui/shared/` (`components` 의존 허용, components 내용 수정 금지)
- 공통 Compose 위젯 → `ui/components/{buttons,badges,cards,controls,layout,pickers}` (`ui/theme` 금지)
- 새 로컬 테이블 → `data/`
- 새 미디어 변환 처리 → `video/`
- 새 마이크 캡처·녹음 Service → `record/`
- 공통 로거·문자열·Application 헬퍼 → `utils/` (`AppLogger`·`AppString`·`RequireApplication`; 신규는 **먼저 사용자 확인**)
- DI(Hilt) 도입은 명시 요청 전 금지. 현행은 수동 생성(`AppDatabase.getInstance`, ViewModel factory/기본).

### 4. 한 줄 요약

> data(Room) → ViewModel → UI. 변환은 video/ + WorkManager. 녹음은 record/ + RecordingController → RecordingService. YouTube/Drive/telemetry/billing은 youtube/·drive/·analytics/·billing/ OkHttp outbound only.

## Architecture Equivalence (MVC / Atomic Design)

> **개념 매핑 전용** — MVC/Atomic Design 역할·의존 방향 설명이며, 추가 물리 폴더 rename은 요구하지 않는다. Screen/ViewModel은 `ui/screens/<domain>/`에 두고 flat `ui/*.kt` Screen/VM은 **금지**(과거 `screens/` restructure 완료).

| MVC (개념) | Atomic Design (개념) | convert2video 패키지 |
|------------|----------------------|----------------------|
| **Model** | — | `data/` — Room Entity·Repository·DataStore |
| **Controller** (동등) | — | `ui/screens/*/XxxViewModel` — UI 상태·Repository/WorkManager 조율; `video/*Worker`·`record/*`(Engine/Service/Controller) — 백그라운드·I/O 실행 |
| **View** | **Organisms / Templates / Pages** | `ui/screens/**` Composable (`*Screen`·`*Content`) — 화면 조립·네비게이션 콜백 |
| — | **Atoms / Molecules** | `ui/components/**` — 무상태 공통 위젯 (Sprint1 SSOT; Sprint2 내용 변경 금지) |
| — | **공유 타입·크로스컷** | `ui/shared/**` — Drawer·`ConversionUiState`·`WorkInfoUiPhase`·duration 포맷; `utils/**` — `AppLogger`·`appString`·`requireApplication` |
| — | **아웃바운드 REST** | `youtube/`·`drive/` — Google API OkHttp; `analytics/` — telemetry `/events`·`/crash` OkHttp; `billing/` — entitlement verify/refresh OkHttp (Android outbound client) |
| — | **제한된 서버 예외** | `backend/` — `AnalyticsController`, `CrashController`, entitlement controller가 analytics·crash·billing entitlement verify/refresh/provider notification만 처리. Android `AppDestination`·OkHttp allowlist와 별개이며 파일 동기화·범용 API 서버 아님 |
| — | **임베디드 서버** | `desktopsync/` — 1.0에서 숨김·dormant인 Ktor CIO 로컬 LAN 서버·`DesktopSyncController`·mDNS 광고. `/pair/request`·`/whoami`는 식별 정보만 반환하고 `/upload`는 로컬 Bearer 페어링만 사용하며 추가 접근 상태 검사나 게이트가 없음; 1.1 복원 후보 |

- ViewModel·Worker가 Model을 직접 노출하지 않고 StateFlow·UiState로 View에 전달 (MVVM).
- `ui/components`는 Screen에서 import; Screen은 components 내용을 인라인·복제하지 않음.
- `video/` → `ui.screens.convert` / `ui.screens.youtube_upload` import 금지 (레이어 역방향 차단).

## Terminology Glossary

> 원본 문서 제목: 용어 사전 — convert2video

### 1. 도메인 용어

| 용어 | 의미 | 코드 예 |
|------|------|---------|
| WorkInfoUiPhase | WorkInfo 4-phase tier — Active(ENQUEUED/RUNNING/BLOCKED)·Succeeded·Failed·Cancelled | `WorkInfoUiPhase`, `toWorkInfoUiPhase()` (`ui/shared/WorkInfoUiPhase.kt`, internal) |
| toWorkInfoUiPhase | `WorkInfo`/`WorkInfo.State` → phase tier. payload mapper보다 먼저 적용 | `WorkInfo.toWorkInfoUiPhase()`, `WorkInfo.State.toWorkInfoUiPhase()` |
| toConversionUiState | conversion WorkInfo **payload** → `ConversionUiState` (phase는 `toWorkInfoUiPhase` 선행) | `ConvertViewModel.kt` — aggregate·batch helpers 포함 |
| toYouTubeUploadUiState | YouTube upload WorkInfo **payload** → `YouTubeUploadUiState` | `YouTubeUploadViewModel.kt` |
| appString | non-Composable `@StringRes` 조회 SSOT — `Context`·`AndroidViewModel` 확장 | `utils/AppString.kt` (internal) |
| requireApplication | `applicationContext as Application` 단일 경로 | `utils/RequireApplication.kt` (internal) |
| AppDestination | Android UI destination enum: `Home`·`BackgroundPick`·`AudioPick`·`Options`·`ErrorLog`·`Convert`·`Trash`·`MicrophoneSource`. `ConvertedVideos`는 enum·route가 아니며 `HomeTab.ConvertedVideos`다. backend POST route와 혼동 금지 | `MainActivity.kt` |
| StoreCapabilities | YouTube/Drive/billing capability availability SSOT. `forStoreId(BuildConfig.STORE_ID)` → `isGoogleServicesAvailable`·`supportsYouTube`·`supportsDrive`·`supportsBilling`; 이는 플레이버별 Google 서비스와 outbound/billing 기능 사용 가능 여부만 결정하고 상업적 접근 게이트가 아니다. unknown store는 fail-closed(전부 false). `supportsBilling`은 google/huawei 모두 true, unknown false. `StoreCapabilities.current` lazy 싱글톤이 유일한 앱 진입점 — Screen/VM/Worker/Trigger가 `BuildConfig.GOOGLE_SERVICES_ENABLED`/`STORE_ID`를 직접 분기하지 않음 | `store/StoreCapabilities.kt` |
| billing | 로그인 없는 Pro 일회성(non-consumable) 구매·entitlement 도메인. Google Play/Huawei provider를 사용하며 backend verify/refresh/provider notification 범위만 허용 | `billing/`·`backend/` |
| BillingGateway | store-neutral billing 계약. Google Play/Huawei flavor 구현이 provider 구매·복원 상태를 전달하며 `PRO_PRODUCT_ID`를 단일 소스로 소유한다 | `billing/BillingGateway.kt` |
| EntitlementApiClient | 제한된 backend entitlement verify/refresh 호출 client. 구매 token/JWT/Authorization header를 로그에 남기지 않으며 범용 API client가 아니다 | `billing/EntitlementApiClient.kt` |
| EntitlementStore | entitlement 상태 저장 계약. JWT 만료·provider 결과와 함께 Pro 상태를 보존하며 boolean 하나만 권한의 근거로 사용하지 않는다. `AndroidKeystoreEntitlementStore`는 `entitlement.preferences_pb` DataStore에 Keystore AES-256-GCM로 암호화한 JWT/pending purchase만 저장 (SettingsRepository·PairingTokenStore와 분리) | `billing/EntitlementStore.kt` |
| EntitlementRepository | process-wide singleton entitlement 조율·영속 boundary (actor 모델). `isProHot: StateFlow<Boolean?>`(null=확인 중), `isProSnapshot()`(IO 읽기+expiry 확인; 읽기 실패=false), 구매/복원 mutex 직렬화, `store purchase → provider state → backend verify+acknowledge → 저장` 순서, backend 일시 오류는 token/JWT 보존 재시도·403 revoked는 즉시 false·만료 후 refresh 실패는 fail-closed, 앱 시작/ProcessLifecycle foreground에서 만료 임박 JWT를 `refresh(jwt, installId())`. `getInstance(context)` 유일 진입점 — OptionsViewModel·ConversionWorker가 동일 hot state 관찰 | `billing/EntitlementRepository.kt` |
| BillingGatewayOwner / createBillingGateway | process-wide 단일 `BillingGateway` 소유자 + flavor factory. 화면 회전·ViewModel 재생성으로 BillingClient가 여러 개 생기지 않음. `Convert2videoApplication.onCreate`가 gateway 1개 생성 후 `EntitlementRepository.attachGateway` | `billing/BillingGateway.kt`, `src/google`·`src/huawei/…/billing/BillingGatewayFactory.kt` |
| GoogleBillingGateway / HuaweiBillingGateway | flavor별 `BillingGateway` 구현. Google=PBL(`billing-ktx`), Huawei=HMS IAP + `HuaweiPurchaseResultParser`(ActivityResult 순수 파서). PENDING은 entitlement 미부여·UI 대기, PURCHASED만 backend verify. acknowledge는 gateway 아닌 backend 단일 경로 | `src/google/…/billing/GoogleBillingGateway.kt`, `src/huawei/…/billing/HuaweiBillingGateway.kt` |
| `/entitlement/verify` / `/entitlement/refresh` | 제한된 backend billing route. verify=`{install_id,store,product_id,purchase_token}`, refresh=`Authorization: Bearer <JWT>` + `{install_id}`만(JWT `sub`와 install ID binding 검증). Android client는 `EntitlementApiClient` (HTTPS only) | `backend/public/index.php` → `EntitlementController::verify/refresh` |
| `entitlement.preferences_pb` | `EntitlementStore` DataStore 파일명. cloud backup·device transfer 양쪽 exclude (`pairing_token.preferences_pb`·`install_id.xml`와 동일 정책) | `backup_rules.xml`·`data_extraction_rules.xml` |
| OptionsProUpgradeContent | Options Pro 업그레이드 무상태 Compose content (`OptionsYoutubeAccountContent` 패턴). `proStatus: Boolean?` + `billingPurchaseState` + upgrade/restore 콜백만. testTag `pro_upgrade_button`·`pro_restore_button`·`pro_status_text`. null=확인 중, false=inactive(upgrade 활성), true=active(upgrade 비활성·restore는 유지), pending/preparing=busy. `StoreCapabilities.current.supportsBilling` 게이트는 호출부 | `ui/screens/options/OptionsProUpgradeContent.kt` |
| BillingPurchaseUiState / proStatus | Options billing UI 상태. `OptionsViewModel.proStatus: StateFlow<Boolean?>` = `EntitlementRepository.isProHot`. `isPurchaseInFlight`·`isRestoreInFlight` 이중 탭 가드. `purchasePro(activity)`·`restorePurchase()`·`onPurchaseActivityResult(resultCode, data)`. Huawei resolution은 `SharedFlow<IntentSenderRequest>`. 결과 메시지는 resource ID (HTTP code·raw exception·token UI 노출 금지) | `ui/screens/options/OptionsViewModel.kt` |
| PRO_PRODUCT_ID | Pro 상품 ID 상수의 단일 소스. 값은 `pro_lifetime_unlock`이며 billing gateway 외 중복 선언 금지 | `billing/BillingGateway.kt` |
| pro_lifetime_unlock | Google Play/Huawei 공통 non-consumable Pro lifetime unlock SKU. 구독·로그인·다중 SKU로 확장하지 않는다 | `PRO_PRODUCT_ID` (`billing/BillingGateway.kt`) |
| BACKEND_BASE_URL | analytics/crash/billing entitlement의 backend base URL. `app/build.gradle.kts`가 `-PbackendBaseUrl`(명시적 빈 값 포함) → `BACKEND_BASE_URL` 환경 변수 → 빈 값 순서로 주입하고 trim 후 모든 trailing slash를 제거해 `BuildConfig.BACKEND_BASE_URL`로 단일 제공한다 | `app/build.gradle.kts` |
| YouTubeAuthGateway / DriveAuthGateway | `src/main`의 store-neutral 인증 인터페이스. `src/google`은 `YouTubeAuthManager`/`GoogleDriveAuthManager`(GMS Identity) 구현, `src/huawei`는 no-op(`AuthorizationOutcome.Failed`/`DriveAuthorizationOutcome.Failed`, `"Google 인증을 진행할 수 없습니다"` → `error_google_auth_unavailable`). 각 플레이버가 `createYouTubeAuthGateway(context)`/`createDriveAuthGateway(context)` factory 제공 | `youtube/YouTubeAuthGateway.kt`, `drive/DriveAuthGateway.kt`, `src/google/…`, `src/huawei/…` |
| install_id | 계정 ID가 아닌 설치별 익명 UUID. Android 생성·영속화의 canonical owner는 `utils/InstallId.kt`이며, backend analytics/crash 수집기는 요청 payload 필드로만 소비한다 | `utils/InstallId.kt` (canonical); `backend/public/index.php` route registry + `AnalyticsController`·`CrashController` request consumers |
| TelemetryApiClient | telemetry REST OkHttp. `BACKEND_BASE_URL` trimEnd('/'). timeout 10/10/10. POST `/events`·`/crash`. Boolean. 프로세스 1개는 `AnalyticsReporter`가 소유. `postJson`은 IO + `Call.cancel` on cancel. CE rethrow. IO/Exception → simpleName + false. URI/URL/필드값/throwable 로그 금지 | `analytics/TelemetryApiClient.kt` (internal) |
| AnalyticsReporter | 프로세스 싱글톤 fire-and-forget. 자체 SupervisorJob+IO. `TelemetryApiClient` 1개 소유. `EVENT_APP_OPEN`는 `languagePromptShown` eligible 후 AtomicBoolean 1회(pending flush). conversion 이벤트는 실제 호출부에서 전송 | `analytics/AnalyticsReporter.kt` |
| CrashReporter | UncaughtExceptionHandler. prefs `crash_reporter` / `pending_stack_trace` + `commit()`(false면 1회 재시도). stack 상한 16KiB UTF-16 `String.length`. `install` AtomicBoolean 멱등. flush blank는 키 제거·POST 금지. 제품 UI 크래시 버튼 없음 | `analytics/CrashReporter.kt` (internal) |
| crash_reporter | crash pending SharedPreferences 파일명 (`crash_reporter.xml`). backup/device-transfer exclude | `CrashReporter.PREFS_NAME` |
| pending_stack_trace | crash pending stack SharedPreferences 키 | `CrashReporter.KEY_PENDING_STACK_TRACE` |
| EVENT_APP_OPEN | analytics 이벤트 상수 `app_open`. MainActivity.onCreate 1곳. `languagePromptShown` eligible 전 pending, 이후 CAS 1회 | `AnalyticsReporter.EVENT_APP_OPEN` |
| EVENT_CONVERSION_STARTED | analytics 이벤트 상수 `conversion_started`. enqueueConversionChain true 직후 | `AnalyticsReporter.EVENT_CONVERSION_STARTED` |
| EVENT_CONVERSION_COMPLETED | analytics 이벤트 상수 `conversion_completed`. mapper 밖 collect. enqueue true 세대 `watched` ⊆ Success만. leftover unique Success 금지 | `AnalyticsReporter.EVENT_CONVERSION_COMPLETED` |
| background | 배경 이미지(라이브러리 항목) | `BackgroundImage`, `BackgroundRepository` |
| conversion | 오디오+배경으로 영상을 만드는 작업 | `ConversionWorker`, `ConversionUiState` (`ui/shared/ConversionUiState.kt`) |
| video | 결과 MP4 | `VideoConverter`, MediaStore 저장 |
| WatermarkCompositor | 배경 이미지에 텍스트 워터마크를 합성하고 cacheDir에 JPEG로 저장. decode 실패 시 원본 반환. Phase 1: 텍스트 전용, 로고 에셋 추가 후 Phase 2 대체 예정 | `video/WatermarkCompositor.kt` (internal object) |
| audio | 입력 오디오 트랙 | `audioUri`, durationUs |
| convertedVideo | 변환 완료된 갤러리/앱 저장 영상 | `ConvertedVideo`, `ConvertedVideoRepository` |
| conversionRecord | 변환 이력 1건 (DB row) | `ConversionRecord`, `ConversionRecordDao`, `ConversionHistoryRepository` |
| uploadRecord | YouTube 업로드 이력 1건 (DB row) | `UploadRecord`, `UploadRecordDao`, `UploadHistoryRepository` |
| conversionHistory | conversion 이력 Room 저장 계층 | `ConversionHistoryRepository` |
| uploadHistory | YouTube upload 이력 Room 저장 계층 | `UploadHistoryRepository` |
| drive | Google Drive 자동 업로드(녹음 파일) 패키지·도메인 | `com.example.convert2video.drive`, `GoogleDriveAuthManager`, `GoogleDriveApiClient` |
| DriveAuthorizationOutcome | Drive `drive.file` 동의 결과 sealed (YouTube `AuthorizationOutcome`과 분리) | `Authorized` / `NeedsConsent` / `Failed` (`drive/DriveAuthGateway.kt`) |
| DriveApiResult | Drive REST 호출 결과 sealed; Failure에 `httpCode` 포함 | `DriveApiResult.Success` / `DriveApiResult.Failure(message, httpCode)` |
| YouTubeApiResult | YouTube REST 호출 결과 sealed; Failure에 `httpCode` 포함. HTTP 오류만 `failureFor`가 `httpCode = response.code` 이식 + 기존 `AppLogger.e` (`YouTube API error: ${code}`). Location/videoId/channel-title null Failure는 `httpCode=null`(추가 w/e 금지). IO catch는 simpleName만(throwable·URL 금지). `fetchChannelTitle` 401 → prefs 클리어 금지(Options/AuthManager 무변경). | `YouTubeApiResult.Success` / `YouTubeApiResult.Failure(message, httpCode)` (`youtube/YouTubeApiClient.kt`) |
| signOutIfUnauthorized | Worker HTTP 401-only signOut SSOT (`httpCode == 401`). 세션 init Failure + PUT Done Failure에서 `signOut` 람다 호출. 403/`null`은 no-op. signOut 예외(non-CE) swallow 후 호출부가 `failWithNotification` 계속. 추가 AppLogger.e 금지; 성공 시 w `signed out after HTTP 401`. Failed auth는 이 함수를 타지 않음(signOut 금지). NeedsConsent는 별도 try/catch signOut + `authorization NeedsConsent; signed out for re-login` 후 반드시 failWithNotification. | `signOutIfUnauthorized` (`youtube/YouTubeUploadWorker.kt`) |
| segment | 오디오 구간 분할(클립) 단위 | `VideoSegment`, `VideoSegmentPlanner` |
| segmentBatchId | 한 배치 변환을 묶는 UUID (비세그먼트면 null) | `ConversionRecord.segmentBatchId`, `KEY_SEGMENT_BATCH_ID` |
| segmentIndex | 배치 내 1-based 세그먼트 번호 | `ConversionRecord.segmentIndex`, `KEY_SEGMENT_INDEX` |
| segmentTotal | 배치 세그먼트 총 개수 | `ConversionRecord.segmentTotal`, `KEY_SEGMENT_TOTAL` |
| recording | 앱 내 마이크 녹음(캡처·인덱싱) | `RecordingEngine`, `RecordingService`, `RecordingController`, `RecordingRepository` |
| desktop pairing | 동일 LAN의 데스크톱과 1:1 페어링하는 보존 구현. 1.0에서는 Options UI에 숨기고 `PairingServerService`를 시작·호출하지 않아 runtime을 dormant로 유지하며, 1.1 복원 후보로 둔다. 복원 시 route/security contract: `GET /ping`(생존 확인·인증 없음), `POST /pair/request`(60초·단일 신뢰·승인/거부·409·503), `GET /whoami`(Bearer), `POST /upload`(Bearer, WAV·RIFF/WAVE·MAX_UPLOAD_BYTES(500MiB)). 토큰: Keystore AES-256-GCM, `pairing_token.preferences_pb` 백업 제외. `DesktopSyncController` 프로세스 싱글톤. mDNS `_c2vsync._tcp`. 로컬 Bearer 페어링 동작은 유지한다. URI/path 로그 금지 | `desktopsync/PairingProtocol.kt`, `PairingServerService`, `DesktopSyncController`, `NsdAdvertiser`, `PairingTokenStore` |
| ServerState | 데스크톱 페어링 서버 라이프사이클 sealed — `Stopped` / `Starting` / `Running` / `Failed`. `DesktopSyncController.serverState` StateFlow | `ServerState` (`desktopsync/PairingServerService.kt`), `DesktopSyncController.serverState` |
| PairingRequest | 승인 대기 중인 단일 페어링 요청 data class — `requestId`, `deviceName`, `remoteHost`, `requestedAtEpochMillis`. `pairingRequest: StateFlow<PairingRequest?>` | `PairingRequest` (`desktopsync/PairingProtocol.kt`), `DesktopSyncController.pairingRequest` |
| DesktopSyncController | 프로세스 싱글톤 UI 브리지. 1.0에서는 Options에 숨기고 runtime을 dormant로 유지하며 1.1 복원 후보로 둔다. 화면 재구성(회전 등) 시 StateFlow 연속성 유지; Service 재시작 시 pending pairing 취소·토큰·paired 상태만 DataStore 복원. `LocalBinder` 직접 구독 금지 — Activity/VM은 `getInstance(application: Application)` 또는 `getInstance(context: Context)` 만 참조 | `desktopsync/DesktopSyncController.kt` |
| PairingTokenStore | Keystore AES-256-GCM 토큰 저장 인터페이스 + `AndroidKeystorePairingTokenStore` 구현. `readToken`/`replaceToken`/`clearToken`/`storeDeviceName`/`readDeviceName`/`clearDeviceName`. SettingsRepository와 분리, 전용 DataStore(`pairing_token.preferences_pb`) | `desktopsync/PairingTokenStore.kt` (internal) |
| NsdAdvertiser | mDNS 광고 구현. `MulticastLock` 획득/해제, `ConnectivityManager.NetworkCallback` Wi-Fi 재연결 시 재광고. 등록 실패 시 최대 `MAX_REGISTRATION_ATTEMPTS`(3)회 재시도 후 fail-open(서버는 계속 동작) | `desktopsync/NsdAdvertiser.kt` (internal) |
| pairingToken | `generatePairingToken`이 `SecureRandom` 32바이트 → Base64URL 인코딩으로 생성. 재페어링 시 기존 토큰 즉시 교체(단일 활성 토큰). DataStore 파일명 `pairing_token.preferences_pb`; backup exclude: `<exclude domain="file" path="datastore/pairing_token.preferences_pb"/>` | `pairing_token.preferences_pb` (DataStore 파일명); 생성 함수는 `PairingAuth.kt` 파일 주석 참조 |
| `startDesktopSync()` | 1.0에는 UI나 production caller가 없고 `PairingServerService`를 시작·호출하지 않는 dormant/future-restoration seam이다. 구현은 보존하며 1.1에 caller 복원을 검토한다. 복원된 flow는 `Starting`/`Running` 중복을 막고 `Failed`·시작 예외 후 재시도 가능해야 한다 | `OptionsViewModel.startDesktopSync()` (`ui/screens/options/OptionsViewModel.kt`) |
| AnalyticsController | backend analytics 이벤트 수집 controller. `install_id`·`event_name`을 `analytics_events`에 기록 | `backend/src/Controllers/AnalyticsController.php` (`/events`) |
| CrashController | backend crash 보고 수집 controller. `install_id`·`stack_trace` 등을 `crash_reports`에 기록 | `backend/src/Controllers/CrashController.php` (`/crash`) |
| `/events` | analytics telemetry 수신 POST route. Android client 호출자는 `TelemetryApiClient` | `backend/public/index.php` → `AnalyticsController.ingest()` |
| `/crash` | crash telemetry 수신 POST route. Android client 호출자는 `TelemetryApiClient` | `backend/public/index.php` → `CrashController.ingest()` |
| multipartUploadContract | 개념. Ktor 3 multipart 수신 규칙 — `receiveMultipart(formFieldLimit=MAX_UPLOAD_BYTES)`, `provider()` 반환 `ByteReadChannel` 사용(`streamProvider()` 금지), 모든 `PartData.dispose()` 호출, 스트리밍 중 누적 MAX_UPLOAD_BYTES 초과 시 413, 실패 경로에서 임시 파일 삭제 | `PairingServerService.kt`(private `handleUpload` 포함), `meterUploadRequestBytes`, `ImportedAudioRepository.UploadSession` |
| wavUploadValidation | 개념. WAV 업로드 유효성 검사 규칙 — `format != "wav"` → 즉시 400; 첫 12바이트 RIFF/WAVE 매직 검증(불일치 → 400); 저장 파일 확장자 항상 `.wav` 강제(클라이언트 파일명 확장자 무시); `Content-Length` 없어도 스트리밍 MAX_UPLOAD_BYTES 상한 적용 | `PairingServerService.kt`(private `handleUpload` 내부), `ImportedAudioRepository` |
| RecordingFormat | 녹음 출력 포맷(AAC/WAV) — Sprint1 `record/RecordingFormat.kt` 단일 정의. DataStore는 `storageValue`(`aac`/`wav`), Service Intent는 `name`(`AAC`/`WAV`) | `RecordingFormat.AAC`, `RecordingFormat.WAV`, `storageValue`, `fromStorageValue` |
| recording_format | DataStore Preferences 키 — Options 녹음 포맷 설정. 타입은 `record.RecordingFormat` (Settings에 enum 재선언 금지) | `SettingsRepository.recordingFormat`, `KEY_RECORDING_FORMAT`(`recording_format`) |
| recordingBackupFolderUri | 녹음 백업 SAF tree URI DataStore Flow. 값은 UI에 노출·로그하지 않으며, [RecordingBackupFolder.SelectionResult.Persisted]일 때만 저장한다. null/blank=미설정 | `SettingsRepository.recordingBackupFolderUri`, `setRecordingBackupFolderUri` |
| recording_backup_folder_uri | DataStore Preferences 키 — 녹음 백업 SAF tree URI | `KEY_RECORDING_BACKUP_FOLDER_URI`(`recording_backup_folder_uri`) |
| RecordingBackupFolder | 녹음 백업 SAF helper. `buildOpenDocumentTreeIntent`는 read/write/persistable tree flags를 포함하고, `handleSelection` 성공만 Persisted URI를 반환한다. `.nomedia` marker는 best-effort이며 미디어 스캔에만 영향을 준다 | `record/RecordingBackupFolder.kt` |
| RecordingBackupManifest | 여러 녹음 Entry의 SAF 백업 메타데이터 manifest. read/transform/replace는 단일 mutex로 원자화하며 Entry ID는 unique다. identity 파일명은 displayName이 아닌 `backupId + format`만으로 만든다 | `record/RecordingBackupManifest.kt`, `recordingBackupFileName` |
| RecordingBackupTrigger | 로컬 상태 전이 write-through enqueue SSOT. 입력 키·`recording_backup_` + backupId work name·`APPEND_OR_REPLACE`는 이 파일 밖에서 재선언 금지 | `record/RecordingBackupTrigger.kt`, `recordingBackupWorkName` |
| RecordingBackupWorker | 선택된 SAF tree에 ACTIVE/TRASHED/REMOVE를 best-effort 반영한다. setup/SAF/IO/invalid 입력 실패는 log+success, CancellationException만 rethrow; rehydration/UI는 범위 밖 | `record/RecordingBackupWorker.kt` |
| RecordingBackupReconciler | 사용자가 SAF 폴더를 재연결할 때 manifest의 ACTIVE/유효 TRASHED 항목을 로컬 파일·Room으로 복원하고 15일 만료 TRASHED 항목을 정리한다 | `record/RecordingBackupReconciler.kt` |
| RecordingBackupFolderUiState | Options 백업 폴더 공개 UI 상태는 정확히 3개: `Unavailable`(URI 없음) / `Connected`(유효한 persisted read+write grant) / `BrokenGrant`(URI는 있으나 grant 무효). URI/path 공개·네 번째 UI 상태 금지 | `OptionsViewModel.kt` |
| OptionsRecordingBackupContent | Options 백업 폴더 무상태 C2vCard. Unavailable=Select folder, BrokenGrant=Reconnect, Connected=정보만. 재연결 중에는 복원 진행 인디케이터를 표시한다 | `OptionsScreen.kt`, `OptionsRecordingBackupContentTest` |
| wifiOnlyUpload | Wi-Fi 전용 업로드 설정 (DataStore boolean cold-Flow, 기본 **true**) | `SettingsRepository.wifiOnlyUpload`, `setWifiOnlyUpload` |
| wifi_only_upload | DataStore Preferences 키 — Wi-Fi 전용 업로드 | `KEY_WIFI_ONLY_UPLOAD`(`wifi_only_upload`) |
| youtubeAutoUploadEnabled | YouTube 녹음 후 자동 변환·업로드 토글 (Settings DataStore boolean **cold-Flow**, 기본 false). Trigger YouTube 체인 게이트. Settings Flow ≠ VM `Boolean?`. | `SettingsRepository.youtubeAutoUploadEnabled`, `setYoutubeAutoUploadEnabled` |
| youtube_auto_upload_enabled | DataStore Preferences 키 — YouTube 자동 변환·업로드 | `KEY_YOUTUBE_AUTO_UPLOAD_ENABLED`(`youtube_auto_upload_enabled`) |
| driveAutoUploadEnabled | Google Drive 자동 업로드 on/off (DataStore boolean cold-Flow, 기본 false). Auth/Worker/UI는 후속 스프린트 | `SettingsRepository.driveAutoUploadEnabled`, `setDriveAutoUploadEnabled` |
| drive_auto_upload_enabled | DataStore Preferences 키 — Drive 자동 업로드 | `KEY_DRIVE_AUTO_UPLOAD_ENABLED`(`drive_auto_upload_enabled`) |
| lastUsedBackgroundPath | 마지막 수동 변환 배경 절대경로 (DataStore string cold-Flow, 미설정 null). **경로 값 로그 금지**(simpleName만). persist는 enqueue 성공 후에만. `String?` blank-remove. delete 시 매칭되면 clear(try-catch / `clearLastUsedBackgroundPathCatching`) | `SettingsRepository.lastUsedBackgroundPath`, `setLastUsedBackgroundPath`, `clearLastUsedBackgroundPathCatching` |
| last_used_background_path | DataStore Preferences 키 — 마지막 수동 변환 배경 경로 | `KEY_LAST_USED_BACKGROUND_PATH`(`last_used_background_path`) |
| pendingCountdown | Options Quick Timer 대기 상태 (DataStore). start+duration 모두 있을 때만 non-null. persist는 `registerStart` true 후에만 (VM). started 키는 COUNTDOWN START Accepted 후 | `PendingCountdown`, `SettingsRepository.pendingCountdown`, `setPendingCountdown`, `markPendingCountdownRecordingStarted`, `clearPendingCountdown` |
| pending_countdown_start_elapsed_millis | DataStore long 키 — countdown START trigger elapsedRealtime | `KEY_PENDING_COUNTDOWN_START_ELAPSED_MILLIS` |
| pending_countdown_duration_minutes | DataStore int 키 — countdown 녹음 길이(분, 1..180) | `KEY_PENDING_COUNTDOWN_DURATION_MINUTES` |
| pending_countdown_recording_started_elapsed_millis | DataStore long 키 — COUNTDOWN START Accepted 시각. null=무장만 | `KEY_PENDING_COUNTDOWN_RECORDING_STARTED_ELAPSED_MILLIS` |
| RecordingCountdownAlarmScheduler | Quick Timer AlarmManager — `ELAPSED_REALTIME_WAKEUP` only. `RecordingAlarmBackend.setExact`(RTC_WAKEUP) 재사용 금지. PI 타깃 `ScheduledRecordingReceiver` FQCN, extras 없음. requestCode START=`-71001` STOP=`-71002`. `registerStart` 범위 1..180. 권한 false→cancel stale 후 false. `hasPendingStart/StopPendingIntent`. **C2:** 통화 pause → `cancelStop`(STOP만); resume → `registerStopRemainingMs`(1ms..180분). Once/Weekly/Daily RTC STOP은 이 객체가 아님(C3) | `RecordingCountdownAlarmScheduler`, `ACTION_COUNTDOWN_START`, `ACTION_COUNTDOWN_STOP`, `RecordingCountdownAlarmBackend`, `registerStopRemainingMs`, `cancelStop` |
| RecordingCountdownViewModel | Options Quick Timer VM. 기본 start-in 5 / duration 10. persist는 registerStart true 후만. userMessage는 Options merge | `RecordingCountdownViewModel` (`ui/screens/options`) |
| OptionsRecordingCountdownContent | Options 스케줄 섹션 **다음** Compose. StepperControl. Start는 `needsExactAlarmPermission`이면 비활성. Exact-alarm 배너 복제 금지. testTag `recording_countdown_*`. waiting remaining N (`countdownRemainingStartMinutes` ceil); remaining≤0이면 `options_recording_countdown_status_waiting_soon`(0분 카피 금지). recording elapsed X of M (`countdownElapsedRecordingMinutes`). 틱커는 Content 내부·`Lifecycle.STARTED+`만 (`nowElapsedMillis` non-null이면 테스트 고정 시계). 표시 분은 wall-clock `elapsedRealtime`(pause 제외 캡처 시간은 C2) | `OptionsRecordingCountdownContent` (`OptionsScreen.kt`) |
| countdownRemainingStartMinutes | START 알람까지 남은 분. 잔여 ms는 ceil(1ms→1분). 과거/동일 시각→0(음수 없음). UI는 0을 waiting 카피에 쓰지 않고 [shouldShowCountdownWaitingSoon] | `countdownRemainingStartMinutes` (`RecordingCountdownAlarmScheduler.kt`, internal) |
| shouldShowCountdownWaitingSoon | waiting remaining≤0 → soon 카피 (`options_recording_countdown_status_waiting_soon`). "Starts in 0 min" / "0분 후 시작 예정" 금지 | `shouldShowCountdownWaitingSoon` (`RecordingCountdownAlarmScheduler.kt`, internal) |
| countdownElapsedRecordingMinutes | START Accepted 이후 경과 분(floor). duration≤0이면 0(throw 금지). 그 외 clamp 0..duration. **벽시계** `elapsedRealtime - recordingStartedElapsedMillis` — C2 통화 pause 제외 녹음 경과와 의도적 차이(C2 전) | `countdownElapsedRecordingMinutes` (`RecordingCountdownAlarmScheduler.kt`, internal) |
| RecordingAutoConvertTrigger | 녹음 인덱싱 성공 후 lastUsedBackgroundPath가 있으면 ConversionWorker plain 1건 enqueue. format/세그먼트 키 없음. Conversion unconstrained. KEEP. `auto_convert_`+Uri.encode. `CONVERSION_UNIQUE_WORK_NAME`·별칭 재사용 금지. video→ui.screens.convert / ui.screens.youtube_upload import 금지. **YouTube 체인:** `isYoutubeAuthorized && youtubeAutoUploadEnabled()` + `youtubeAutoUploadTitleOrNull` non-null → `enqueueAssembledAutoConvertChain`(`beginUniqueWork`+`then`) privacy private, description "", title=sanitize stem only, enqueue 시 KEY_VIDEO_URI 없음. **토글은 authorized일 때만 읽기** (`!auth`/auth-throw → YouTube step omitted; toggle unread). YouTube step만 `wifiOnlyUpload` Network Constraints (`networkTypeForYoutubeWifiOnly`). 로깅된 convert-only skip: `!auth` (`youtube step omitted: not authorized`) / 토글 false (`youtube step omitted: toggle off`) / blank title (`youtube step omitted: blank title`, `_.m4a`); degrade convert-only: auth-throw / 토글 읽기실패 (logs `youtube chain degraded`). **wifiOnly read failure → CONNECTED, not convert-only** (logs `wifiOnly read failed; using CONNECTED`, not degrade). CE rethrow. Public default=`isYoutubeAuthorizedFromPrefs` (internal). Predecessor `KEY_ITEM_FAILED` → `youtubeUploadPreludeResult` → `predecessorFailureSkipResult()` empty success. **수동 가드:** `MANUAL_CONVERSION_WORK_NAME`(=CONVERSION_UNIQUE_WORK_NAME) 조회, `WorkInfo.State !isFinished`(ENQUEUED/RUNNING/BLOCKED skip), `get()` timeout + non-CE fail-closed; CE rethrow. **파일 가드:** `File.isFile` + `BackgroundRepository.backgroundsDir` confinement; isFile false / outside → skip + clearLastUsed (write 실패 swallow, 경로 없는 로그); isFile Exception / 판정 불가(IO/Security) → skip, **clear 금지** (`isFile` false ≠ isFile throw; canonical undetermined ≠ outside). Error ≠ Exception swallow | `enqueueAutoConvertIfEnabled`, `autoConvertWorkName`, `MANUAL_CONVERSION_WORK_NAME`, `isYoutubeAuthorized`, `youtubeAutoUploadEnabled` seam, `wifiOnlyUpload` seam, `networkTypeForYoutubeWifiOnly`, `enqueueAssembledAutoConvertChain`, `youtubeAutoUploadTitleOrNull` (`video/RecordingAutoConvertTrigger.kt`) |
| shouldSkipUploadForPredecessorFailure | Conversion predecessor soft-fail 가드. `input.getBoolean(ConversionWorker.KEY_ITEM_FAILED, false)`. true(+KEY_VIDEO_URI 있어도) skip. key absent/false → 수동 업로드 경로 불변 | `shouldSkipUploadForPredecessorFailure` (`youtube/YouTubeUploadWorker.kt`) |
| predecessorFailureSkipResult | Predecessor skip 시 반환 Result SSOT — `Result.success()` empty (`outputData` 키 없음). **시그니처·반환값 불변.** | `predecessorFailureSkipResult` (`youtube/YouTubeUploadWorker.kt`) |
| youtubeUploadPreludeResult | doWork FIRST gate. skip input → [predecessorFailureSkipResult]; 그 외 null 후 setForeground. **함수 본체·시그니처·반환값 불변.** predecessor 로그 [`YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG`] (`youtube upload skipped: predecessor itemFailed`)는 **doWork 단일 호출부**(prelude return 직전 `AppLogger.w`); prelude 함수 안이 아님. NeedsConsent: try/catch `signOut` + `authorization NeedsConsent; signed out for re-login` 후 반드시 failWithNotification. Failed: `authorization Failed`만(signOut 금지). | `youtubeUploadPreludeResult`, `YOUTUBE_UPLOAD_SKIPPED_PREDECESSOR_LOG` (`youtube/YouTubeUploadWorker.kt`) |
| isYoutubeAuthorizedFromPrefs | YouTube authorized prefs-only 읽기 (**internal**). `YOUTUBE_AUTH_PREFS_NAME`=`youtube_auth` / `YOUTUBE_AUTH_KEY_AUTHORIZED`=`authorized`. Play Services `Identity` ctor 없음. Trigger 공개 default seam. 인스턴스 `isAuthorized`는 같은 prefs 필드 재사용 | `isYoutubeAuthorizedFromPrefs` (`youtube/YouTubeAuthManager.kt`) |
| youtubeAutoUploadTitleOrNull | 녹음 파일명 → YouTube title. `sanitizeOriginalFileStem` 후 blank/`_`만 → null (YouTube step omit) | `youtubeAutoUploadTitleOrNull` (`RecordingAutoConvertTrigger.kt`) |
| enqueueAssembledAutoConvertChain | Unique-work continuation 조립 SSOT. youtube non-null이면 `then` **결과**를 enqueue (pre-then handle enqueue 금지) | `enqueueAssembledAutoConvertChain` (`RecordingAutoConvertTrigger.kt`) |
| networkTypeForYoutubeWifiOnly | wifiOnlyUpload → YouTube auto-convert chain NetworkType. true=UNMETERED, false=CONNECTED. Drive `networkTypeForDriveWifiOnly`·YouTubeUploadViewModel `networkTypeForWifiOnly`와 공유 금지 | `networkTypeForYoutubeWifiOnly` (`RecordingAutoConvertTrigger.kt`) |
| MANUAL_CONVERSION_WORK_NAME | Trigger-local 수동 unique **별칭**. 값=`CONVERSION_UNIQUE_WORK_NAME`=`ConvertViewModel.CONVERSION_WORK_NAME`. Trigger는 convert import 금지. auto unique(`auto_convert_*`)에 재사용 금지 | `MANUAL_CONVERSION_WORK_NAME` (`RecordingAutoConvertTrigger.kt`) |
| persistLastUsedJob | ConvertViewModel lastUsed persist Job + `conversionEnqueueMutex`. **버튼 탭 직후 금지.** `enqueueConversionChain` 성공(true) 후에만 launch. SkipUnfinished / query-failed / `SingleAudioRequired`는 persist 금지. `shouldPersistLastUsedAfterStart` SSOT | `persistLastUsedJob`, `shouldPersistLastUsedAfterStart`, `launchPersistLastUsedBackgroundPath` (`ConvertViewModel.kt`) |
| OptionsDriveAccountContent | Options Drive 계정+자동업로드 무상태 Compose. **상태 머신 A**: `driveAccountEmail != null`일 때만 로그인됨 UI; in-flight는 「확인 중…」+로그인 비활성. **quota 표시만:** 로그인됨 + `driveStorageQuota` non-null일 때 LinearProgressIndicator+텍스트 (`DriveStorageQuota`; 업로드 차단 없음) | `OptionsDriveAccountContent` (`OptionsScreen.kt`) |
| OptionsYoutubeAccountContent | YouTube 계정 카드 무상태 Compose. Drive와 **공통 추출 금지**. **상태 머신:** signed-in=`isYoutubeSignedInUi`(`isAuthorized && channelTitle != null`, **게이트=channelTitle**). **표시=이메일** (`youtubeAccountEmail`, blank/null이면 `channelTitle` 폴백; `checkNotNull(youtubeAccountEmail)`만으로 표시 금지)+로그아웃; `channelTitleFetchFailed`→실패+재시도; `isChannelTitleLoading`→확인 중+progress; authorized+title 미확정+!loading→확인 중 대기(인디케이터 없음); else 미로그인 힌트+로그인. **Switch:** `enabled = isYoutubeAutoUploadReady`(`youtubeAutoUploadEnabled != null`, 로그인 무관; `isLanguageApplying`으로 Switch 잠그지 않음). 로그인 버튼만 `!isLanguageApplying`. 로그아웃/info는 language apply로 안 잠김. **info:** lastUsedBackgroundPath(수동 변환 배경 재사용) 안내, `rememberSaveable`, `action_confirm`, testTag `youtube_auto_upload_info_button` / `youtube_auto_upload_info_confirm_button`. 라벨·본문·cd는 `options_youtube_auto_upload*` / `cd_options_youtube_auto_upload*` 키(영문 리터럴 금지). | `OptionsYoutubeAccountContent` (`OptionsScreen.kt`), `isYoutubeSignedInUi`, `isYoutubeAutoUploadReady`, `youtube_channel_checking`, `youtube_channel_loading_indicator`, `youtube_login_button`, `youtube_sign_out_button`, `youtube_retry_button`, `OptionsViewModel.youtubeAutoUploadEnabled`, `setYoutubeAutoUploadEnabled`, `youtube_auto_upload_switch`, `OptionsYoutubeAccountContentTest` |
| OptionsViewModel.youtubeAutoUploadEnabled | Options YouTube 토글 StateFlow `Boolean?`. Eagerly `initialValue=null`(DataStore 첫 값 대기·초기 false 깜빡임 방지). Settings `Flow<Boolean>`과 혼동 금지. `setYoutubeAutoUploadEnabled`는 `settingsMutex` 직렬화, catch→`options_settings_save_failed`, 로그인 무관. | `OptionsViewModel.youtubeAutoUploadEnabled`, `setYoutubeAutoUploadEnabled` |
| youtubeAccountEmail | Options YouTube 표시용 계정 이메일 StateFlow. `YouTubeAuthManager.cachedAccountName` 동기화(`refreshAuthState` / `commitPendingYouTubeAccountName` 후 / `signOut` 후). 게이트 아님. | `OptionsViewModel.youtubeAccountEmail` |
| cachedAccountName | YouTube 마지막 선택/저장 Google 계정명 prefs. AuthManager 무변경(이 스프린트). | `YouTubeAuthManager.cachedAccountName` |
| DriveStorageQuota | Drive about `storageQuota` 파싱 결과. `usageBytes` 필수, `limitBytes` null=무제한. **public** (androidTest 생성 가능). | `DriveStorageQuota` (`GoogleDriveApiClient.kt`) |
| parseDriveStorageQuota | about JSON → `DriveStorageQuota?`. usage 필수, limit 없으면 `limitBytes=null`, malformed → null. **internal**. | `parseDriveStorageQuota` (`GoogleDriveApiClient.kt`) |
| fetchStorageQuota | GET `{API_BASE}/about?fields=storageQuota`. `fetchAccountEmail`과 동일 OkHttp 패턴. 신규 스코프 없음. | `GoogleDriveApiClient.fetchStorageQuota` |
| driveStorageQuota | Options Drive 사용량 StateFlow. **조회 시점:** Options 진입 시 기존 `LaunchedEffect(Unit)`에서 `refreshAuthState`/`refreshDriveAuthState`와 함께 `refreshDriveStorageQuota`. VM init·`refreshDriveAuthState` 단독·Home에서는 about GET 금지. prefs 미로그인(`!isAuthorized` 또는 email null)이면 silent `requestAuthorization`/about GET 스킵·quota null. 로그인 Success는 보유 accessToken으로 `fetchStorageQuota`(silent `requestAuthorization` 재호출 금지). quota 쓰기는 `driveStorageQuotaJob` + generation/`isActive` 가드. `signOutDrive` → null. NeedsConsent/Failed/조회실패 → quota null, 동의 UI·로그아웃·업로드 차단 없음. `limitBytes <= 0`은 invalid(숨김), unlimited 아님. SI 상한 GB(1000^3; 1TB+=1000 GB). 퍼센트=`roundToInt`. | `OptionsViewModel.driveStorageQuota`, `refreshDriveStorageQuota` |
| formatDriveStorageBytes | Drive 사용량 SI(1000) 순수 포맷. 숫자 라벨 + unit `@StringRes`(기존 `options_drive_storage_unit_*`). 코드에 GB 리터럴 금지. Screen private 금지. | `formatDriveStorageBytes`, `DriveStorageByteFormat` (`GoogleDriveApiClient.kt`) |
| isDriveAuthInFlight | Drive 로그인/동의/이메일 조회 진행 플래그. 이중 탭 가드·진행 표시 SSOT | `OptionsViewModel.isDriveAuthInFlight` |
| driveAccountEmail | Drive 계정 이메일 StateFlow. email 확정 = 로그인됨 UI 게이트(상태 머신 A) | `OptionsViewModel.driveAccountEmail` |
| isDriveAuthorized | Drive prefs authorized StateFlow. Content 계정 카드 분기는 email 게이트(상태 머신 A) | `OptionsViewModel.isDriveAuthorized` |
| completeDriveAuthorization | 토큰 확보 후 이메일 조회. Success 전 generation 가드(signOut TOCTOU). Failure→`signOutDrive` 롤백. Success 시 동일 accessToken으로 `fetchStorageQuota`(silent `requestAuthorization` 재호출 금지). | `OptionsViewModel.completeDriveAuthorization` |
| onDriveAuthorizationLaunchFailed | `driveAuthorizationLauncher.launch` 예외 seam — in-flight 해제 + `drive_auth_incomplete` Fallback | `OptionsViewModel.onDriveAuthorizationLaunchFailed` |
| driveAuthGeneration | signOut/cancel/launch-fail 시 증가. `completeDriveAuthorization` Success 적용 TOCTOU 차단 | `OptionsViewModel` private |
| driveUserFacingMessage | Drive 계층 raw 메시지 Options 화이트리스트/Fallback (`drive_auth_incomplete`) | `OptionsViewModel.driveUserFacingMessage` |
| RecordingRecord | 녹음 완료 이력 1건 (DB row). 신규 Keep은 generated local ID를 `backupId`로 원자 저장하고 restore는 nonzero backupId를 보존한다 | `RecordingRecord`, `RecordingDao`, `recording_records` |
| RecordingSchedule | 예약 녹음 스케줄 1건 (DB row). overnight(end < start) 허용. **동일 분 금지**(start==end 불가) | `RecordingSchedule`, `RecordingScheduleDao`, `recording_schedules`, `RecordingScheduleRepository` |
| ExactAlarmPermission | Exact alarm 권한 헬퍼 (`canScheduleExactAlarms` / `buildRequestIntent`). DataStore 플래그 `exact_alarm_prompted`(default false) — MainActivity 최초 1회 유도·Options 배너 ON_RESUME. **AlarmManager null SSOT:** UI null→`true`(배너 오탐 방지); Scheduler 등록 경로 null→실패/`false`(pretend-success 금지) | `ExactAlarmPermission`, `exactAlarmPrompted`, `setExactAlarmPrompted` |
| LanguageOption | Per-app UI 언어 enum (`en`/`ko` tag). `SettingsRepository.kt`에만 정의(ThemeMode 옆). DataStore 키 없음. 적용=`AppCompatDelegate.setApplicationLocales`/`getApplicationLocales`. **영속:** API 33+ `LocaleManager`; pre-33 Manifest `AppLocalesMetadataHolderService` + `autoStoreLocales=true`. **policy A:** `getApplicationLocales()` empty → English. **Options:** Screen이 `restartApp` 하드 리스타트 소유 (`NEW_TASK\|CLEAR_TASK` + `Runtime.exit(0)`). **LanguagePicker/MainActivity first-launch:** 기존 `activity.recreate()` 유지(Options와 구분). `SettingsRepository.applyLanguage`는 restart/recreate를 호출하지 않음. | `LanguageOption.English`, `LanguageOption.Korean`, `tag`, `fromTag`, `SettingsRepository.applyLanguage`, `SettingsRepository.currentLanguageOption` |
| languageOption | Options 현재 언어 StateFlow. `SettingsRepository.currentLanguageOption()`으로 시드; DataStore Flow 없음 | `OptionsViewModel.languageOption` |
| isLanguageApplying | Options 언어 apply/restart in-flight. SegmentedControl disable + progress 표시 SSOT. 성공 시 Screen `restartApp`까지 true 유지; 실패/rollback 시 false. **Out-of-scope:** 녹음·변환 중 language restart/`exit(0)` 가드(Record/Convert StateFlow를 Options에 끌어오지 않음) | `OptionsViewModel.isLanguageApplying` |
| OptionsViewModel.setLanguage | `(option, canRestart) → Boolean`. same/in-flight→false. `canRestart=false`(Screen: null/finishing/destroyed)→no apply + `language_picker_apply_failed`. true+apply ok→commit + Screen이 `restartApp` 하드 리스타트 소유. restart 실패→`rollbackAfterRestartFailure(previous)`. **Out-of-scope:** 녹음·변환 중 exit 가드 | `setLanguage`, `rollbackAfterRestartFailure` |
| languagePromptShown | 최초 언어 선택 프롬프트 표시 여부 (DataStore boolean cold-Flow, 기본 false). `exactAlarmPrompted`와 동일 패턴 | `SettingsRepository.languagePromptShown`, `setLanguagePromptShown` |
| LanguagePickerScreen | First-launch 언어 선택 무상태 Compose UI. apply 코루틴 없음 — MainActivity `setContent` scope가 apply/`setLanguagePromptShown`/recreate 소유. `isApplying` 중 행 비활성+progress | `LanguagePickerScreen` (`ui/screens/language_pick/LanguagePickerScreen.kt`) |
| LanguagePromptUi | First-launch 언어 프롬프트 게이트 sealed 상태 — `Loading` / `Picker` / `ApplyingProgress` / `App` | `LanguagePromptUi` (`ui/screens/language_pick/LanguagePickerScreen.kt`) |
| resolveLanguagePromptGate | 순수 게이트: `(promptShown, committed) → LanguagePromptUi` (null→Loading; false+!committed→Picker; false+committed→ApplyingProgress; true+!committed→App; true+committed→ApplyingProgress) | `resolveLanguagePromptGate` (`ui/screens/language_pick/LanguagePickerScreen.kt`) |
| languageSelectionCommitted | MainActivity flash 게이트. **탭 시작에 true 금지**. `setLanguagePromptShown(true)` **쓰기 전**에 committed=true (true+!committed → App 플래시 방지). write/host 실패 시 false 롤백. | `MainActivity` `languageSelectionCommitted` |
| findActivityOrNull | Context→Activity walk (`ContextWrapper`). LanguagePicker first-launch/`exact-alarm` CTA는 recreate·startActivity; Options 언어는 Screen `restartApp` 하드 리스타트용 SSOT. null이면 applying 롤백 + `language_picker_apply_failed`(언어) / Snackbar(exact-alarm) + `AppLogger.e`. Options는 finishing/destroyed도 null과 동일(`canRestart=false`) | `MainActivity.kt` top-level `Context.findActivityOrNull` |
| RecordingScheduleAlarmScheduler | 예약 녹음 AlarmManager 엔진 — `nextTriggerEpochMillis`(순수)·`registerStart`/`registerStop`→`Boolean`·`cancel`. PI 타깃은 `ScheduledRecordingReceiver` FQCN·START/STOP action(Manifest 등록). requestCode START=`id≪1` / STOP=`id≪1\|1`(충돌 금지); 상한 `MAX_SCHEDULE_ID_FOR_REQUEST_CODE`=(Int.MAX_VALUE/2) — overflow는 register/cancel에서 catch→false/no-op. cancel 매칭=`requestCode+action+component`; cancel 조회=`FLAG_NO_CREATE\|IMMUTABLE`. **setExact 성공 후에만** 짝 cancel(실패 시 기존 알람 보존·replace semantics). 권한 false→**cancel stale** 후 false. ONCE `nextTrigger`=다음 벽시계 start만(one-shot disable은 Receiver·VM). **C3:** 통화 pause가 RTC STOP을 cancel/재등록하지 않음 — 벽시계 종료 시각 유지 (`shouldDeferScheduledStopOnCallPause`=false) | `RecordingScheduleAlarmScheduler`, `nextTriggerEpochMillis`, `stopTriggerEpochMillis`, `ACTION_SCHEDULED_START`, `ACTION_SCHEDULED_STOP`, `RECEIVER_CLASS_NAME`, `MAX_SCHEDULE_ID_FOR_REQUEST_CODE`, `RecordingAlarmBackend` |
| ScheduledRecordingReceiver | Manifest 등록 BroadcastReceiver(예약 START/STOP + countdown START/STOP → RecordingController). **예약 extras:** `hasExtra(EXTRA_SCHEDULE_ID)` / `hasExtra(EXTRA_OCCURRENCE_START_EPOCH_MILLIS)` 필수 — `getLongExtra(..., 0)` 기본값 금지(id=0·epoch=0 혼동). START occurrence=triggerAt; STOP occurrence=occurrenceStart. **Countdown:** extras 없음. COUNTDOWN 분기는 `handleStart`/`handleStop` 금지. Busy/fail→clear+cancel(STOP 없음). Accepted→elapsedRealtime+DataStore duration으로 registerStop + mark started. STOP→stopController+clear+cancel. `awaitScheduledStartAttempt` internal 재사용 | `ScheduledRecordingReceiver`, `RecordingScheduleAlarmScheduler.RECEIVER_CLASS_NAME`, `EXTRA_SCHEDULE_ID`, `EXTRA_OCCURRENCE_START_EPOCH_MILLIS`, `ACTION_COUNTDOWN_START`, `ACTION_COUNTDOWN_STOP` |
| RecordingScheduleRepeatMode | 예약 녹음 반복 모드 — `record/RecordingScheduleRepeatMode.kt` SSOT. Room은 `.name`(`ONCE`/`WEEKLY`/`DAILY`). 쓰기=`requireFromStorageValue`(unknown 거부), 읽기=`fromStorageValue`(soft→ONCE). Settings/DataStore에 재선언 금지 | `ONCE`, `WEEKLY`, `DAILY`, `storageValue`, `requireFromStorageValue`, `fromStorageValue` |
| `NoiseReductionMode` | 잡음 감소 모드 SSOT: `record/NoiseReductionMode.kt`에만 enum 정의(`DeviceDefault`/`On`/`Off`). `data/SettingsRepository.kt`는 import만 — 재선언 금지 | `NoiseReductionMode.DeviceDefault`, `storageValue`, `fromStorageValue` |
| `MicrophoneSource` | 녹음 마이크 입력 소스 SSOT: `record/MicrophoneSource.kt`에만 enum 정의(`Default`/`Bluetooth`). `data/SettingsRepository.kt`는 import만 — 재선언 금지. `storageValue`=`default`/`bluetooth`. `fromStorageValue`(null/unknown→`Default`). Bluetooth인데 SCO 미연결이면 오류 없이 Default 폴백 | `MicrophoneSource.Default`, `MicrophoneSource.Bluetooth`, `storageValue`, `fromStorageValue` |
| microphoneSource | 마이크 입력 소스 DataStore cold-Flow. emit 시 process-wide [microphoneSourceHot]도 갱신. Settings Flow ≠ VM `Boolean?` 패턴과 달리 enum Flow | `SettingsRepository.microphoneSource`, `setMicrophoneSource` |
| microphoneSourceHot | 프로세스 전역 마이크 소스 hot StateFlow. `RecordingEngine.create()`가 동기적으로 읽음. null=DataStore 미시드 → `MicrophoneSource.Default`. 격리=`resetMicrophoneSourceHotForTests()` | `SettingsRepository.microphoneSourceHot` |
| setMicrophoneSource | DataStore 저장 + hot cache 낙관적 갱신. IOException 시 hot cache 롤백 후 throw | `SettingsRepository.setMicrophoneSource` |
| MicrophoneSourceRouting | 블루투스 SCO 마이크 탐색·오디오 라우팅. `findConnectedBluetoothMic`/`isBluetoothMicConnected`/`beginRouting`/`endRouting`. 기기 이름(`productName`) 절대 읽지 않음. `BLUETOOTH_CONNECT` 없음. API 31+: `setCommunicationDevice`/`clearCommunicationDevice`. API 26–30: deprecated `startBluetoothSco`/`stopBluetoothSco` (`@Suppress("DEPRECATION")`+TODO). 미연결 `beginRouting`→false(백엔드는 내장 마이크 폴백) | `MicrophoneSourceRouting` (`record/MicrophoneSourceRouting.kt`, internal) |
| MicrophoneSourceScreen | 드로어 5번째 행 destination. Scaffold+TopAppBar. SegmentedControl Default/Bluetooth + info `AlertDialog` + 연결 힌트. OptionsNoiseReductionContent 패턴 **로컬 복제**(options import 금지). `microphoneSource` null이면 SegmentedControl 비활성 | `MicrophoneSourceScreen` (`ui/screens/microphone_source/MicrophoneSourceScreen.kt`) |
| MicrophoneSourceViewModel | 마이크 소스 화면 VM. `microphoneSource: StateFlow<MicrophoneSource?>` = [microphoneSourceHot]. `isBluetoothConnected`는 화면 진입 `LaunchedEffect`/`refreshBluetoothConnected`에서 `isBluetoothMicConnected`. `setMicrophoneSource`는 SettingsRepository 위임 | `MicrophoneSourceViewModel` (`ui/screens/microphone_source`) |
| recording_schedules | 예약 녹음 스케줄 Room 테이블 | `RecordingSchedule`, `MIGRATION_5_6` |
| daysOfWeekMask | WEEKLY 요일 비트마스크 — bit0=월 .. bit6=일 (Monday-start). **WEEKLY는 최소 1 bit**(0 금지). ONCE/DAILY는 **저장 0·해석 무시** | `RecordingSchedule.daysOfWeekMask` |
| CallAudioFocusMonitor | AudioFocus 휴리스틱 통화 감지. **권한 0:** `READ_PHONE_STATE` / TelephonyCallback 없음. 포커스를 요청하지 않는 VoIP는 놓칠 수 있음(100% 아님). **통화 모드**(`MODE_IN_CALL`/`MODE_IN_COMMUNICATION`)일 때만 LOSS/TRANSIENT로 pause — 미디어 재생 등 일반 포커스 손실은 무시. 영구 LOSS 후 GAIN 미보장 → 알림 Pause/Resume으로 수동 resume. pause 대기 중 abandon 금지(세션 종료만). Deprecated streamType `requestAudioFocus` 금지 — `AudioFocusRequest` only. request 실패는 fail-open(녹음 계속). | `CallAudioFocusMonitor`, `RecordingAudioFocusBackend`, `AndroidRecordingAudioFocusBackend` (`record/CallAudioFocusMonitor.kt`) |
| resolveCallAudioFocusAction | AudioFocus change → Service pause/resume 매핑 SSOT. `LOSS`/`LOSS_TRANSIENT`+Recording+`isCallModeActive`→Pause; `GAIN`/`GAIN_TRANSIENT`+Paused+`pausedByCallDetection`→Resume; duck·사용자 pause·Idle/Review/Stopping/Saved/Failed·Recording+LOSS+!callMode→None | `resolveCallAudioFocusAction`, `CallAudioFocusAction`, `audioFocusChangeLabel` |
| pausedByCallDetection | Service 내부 플래그. AudioFocus pause만 true. 사용자 [handlePauseResume] pause는 false. resume·세션 종료 시 false. 사용자 일시정지는 자동 재개 금지 | `RecordingService` |
| remainingCountdownStopMs | C2 남은 Quick Timer STOP 지연 SSOT — `durationMinutes*60_000 - elapsedRecordingMs`, 하한 0. 분 단위 `registerStop`과 달리 1분 미만 잔여도 허용 | `remainingCountdownStopMs`, `shouldStopCountdownImmediately`, `registerStopRemainingMs` (`RecordingCountdownAlarmScheduler.kt`) |
| shouldDeferCountdownStopOnCallPause | C2 게이트. `pausedByCall && hasPendingCountdownStop`만 true. 사용자 pause·예약 RTC STOP은 false | `shouldDeferCountdownStopOnCallPause` |
| shouldDeferScheduledStopOnCallPause | C3 계약 — 항상 false. Once/Weekly/Daily RTC STOP은 통화 구간만큼 미루지 않음 | `shouldDeferScheduledStopOnCallPause` |
| RecordingController | ViewModel용 녹음 싱글톤 진입점(Hilt 없음). bindService는 내부만. Review sticky=Saved/Failed(Idle wipe 금지; Discard Idle는 Service confirm 후만). `keep()`/`discard()`; Review 중 start/pause/resume/stop no-op; **Keep in-flight 동안 Discard/Start no-op**; `clearTerminalState`는 Review 미포함. Occupancy는 [RecordingService.isRunning] (Review 포함) — Tile [isActiveRecordingSession]과 접지 금지. | `RecordingController.getInstance(application)` |
| RecordingTileService | Quick Settings 타일 진입점 — RecordingController만 호출. onClick: [performRecordingQuickClick] SSOT — **Review → 앱 열기**(Review UI; silent no-op/Start 금지), 비활성+권한없음→MainActivity(`REQ_LAUNCH_TILE`=90001), 권한있음→start(); 활성(Recording/Paused/Stopping)→stop(). `isActiveRecordingSession`(top-level internal)이 활성 판단 SSOT — **Review는 false**(occupancy와 접지 금지). 타일 라벨은 Review 시 `recording_notification_review` ([tileLabelResIdFor]) | `record/RecordingTileService.kt` |
| RecordingQuickAction | Tile·Glance 위젯 공통 onClick SSOT — `resolveRecordingQuickClickBranch`·`performRecordingQuickClick`. **Review → `ReviewPending`(isActive보다 먼저). perform는 앱을 열어 Review — silent no-op/Start 금지.** 활성→stop / 권한+Idle→start / 권한거부→MainActivity. 권한 유도 PI: Tile `REQ_LAUNCH_TILE`(90001) / Widget `REQ_LAUNCH_WIDGET`(90002). Widget 경로 [EXTRA_QUICK_RECORD] 금지 | `record/RecordingQuickAction.kt` |
| QuickRecordWidgetStateSync | Application.onCreate에서 install — [RecordingController.state] collect → [quickRecordGlanceWidget.updateAll]. Tile [RecordingTileService] collect와 대칭 | `record/QuickRecordWidgetStateSync.kt`, `Convert2videoApplication` |
| quickRecordGlanceWidget | Receiver·ActionCallback·StateSync 공유 단일 [GlanceAppWidget] 인스턴스 — 매 탭 new QuickRecordWidget() 금지 | `record/QuickRecordWidgetReceiver.kt` |
| QuickRecordWidget | 1×1 Glance 홈 위젯 — 탭 시 Tile과 동일 [performRecordingQuickClick] (Main.immediate). Controller만; Service/Engine/bindService 금지. 권한 거부 시 permission UI + `REQ_LAUNCH_WIDGET` PI | `record/QuickRecordWidget.kt`, `QuickRecordActionCallback` |
| QuickRecordWidgetReceiver | Glance `GlanceAppWidgetReceiver` — Manifest `exported=false`, `APPWIDGET_UPDATE`, `@xml/quick_record_widget_info` | `record/QuickRecordWidgetReceiver.kt` |
| quick_record_widget_info | 1×1 AppWidget provider — `targetCellWidth/Height=1`, `minWidth/Height≈40dp`, `resizeMode=none`, `updatePeriodMillis=0` | `res/xml/quick_record_widget_info.xml` |
| RecordingState | 녹음 엔진/서비스/컨트롤러 상태 | `Idle`/`Recording(elapsedMs,amplitude)`/`Paused(elapsedMs)`/`Stopping`(Service)/`Stopped`(Engine)/`Review(outputFile,elapsedMs)`(STOP>5s Keep 전, Room 0, Service/Controller 전용 sticky)/`Saved(outputFile,elapsedMs)`(Keep insert 후 UI 1회)/`Failed` |
| RecordingErrorCodes | Failed용 안정 에러 코드(사용자 문자열은 Service getString 매핑) | `START_FAILED`, `CAPTURE_FAILED`, `INDEX_FAILED` 등 |
| RecordUiState | 녹음 화면 UI 상태 (Controller 상태+권한 매핑) | `Idle`/`PermissionDenied`/`Recording`/`Paused`/`Saving`/`Review(outputFile,elapsedMs)`/`Saved`/`Error`. `toRecordUiState(Review)→Review` (Saved/Saving 접지 금지) |
| RecordViewModel | 녹음 UI 상태 단일 소스. `RecordingController.getInstance`만 사용 | `RecordViewModel` |
| RecordScreen | 녹음 화면 stateful wrapper (Sprint 2-3/2-4). 권한 launcher·ON_RESUME·ViewModel 콜백·`onRecordingSaved` seam | `RecordScreen` (`RecordScreen.kt`) |
| RecordContent | 녹음 화면 무상태 Compose UI (Sprint 2-2). ViewModel/launcher 없음 | `RecordContent` (`RecordScreen.kt`) |
| onRecordingSaved | Phase 3 seam — Saved 성공 1회. 성공: URI→applyRecordingSaved→Home. 실패: Record 잔류+discardStickySaved+Toast(목록/탭 유지). Active 재진입 Out-of-scope | `RecordScreen(onRecordingSaved=)`, `resolveRecordingSavedSeamAction` |
| MediaDurationFormat | UI 공통 duration 포맷 SSOT (ms→초 floor) | `formatMediaDurationMs`, `MediaDurationStyle` (`ui/shared/MediaDurationFormat.kt`) |
| MediaDurationStyle.Timer | 녹음 경과 타이머 — 1시간 미만 `mm:ss`, 이상 `h:mm:ss` | `RecordContent` 타이머 |
| MediaDurationStyle.ListRow | 목록 행 duration — `m:ss`(분>59 허용). Timer와 intentional 차이 | `AudioPickScreen` 등 |
| AudioSourceTab | Convert 조립 화면 오디오 소스 탭(녹음/파일선택). 빈 상태 SegmentedControl·채움 시 "변경" 재진입 대상 | `ConvertViewModel.AudioSourceTab` (`Record`/`FilePick`), `selectAudioSourceTab` |
| AudioSourceFilter | AudioPick SegmentedControl 필터(전체/내 녹음/파일에서). 기본 `MyRecordings`. enum `ui/screens/audio_pick/AudioSourceFilter.kt` | `AudioSourceFilter`, `AudioSourceFilterMapping.kt`, `filterDisplayedItems` |
| `RecordingsListSortOrder.Duration` | 녹음 목록 정렬 — durationMs 내림차순(긴 것 먼저), 동점 시 dateAdded 내림차순. DropdownSelector index 2 (0=Time/1=Name/2=Duration) | `RecordingsListSortOrder.Duration`, `sortDisplayedItems` |
| `RecordingsListConversionFilter` | Listen 화면 변환 여부 필터 enum — `All`(전체) / `Converted`(변환됨만) / `NotConverted`(미변환만). 기본 `All`. DropdownSelector index 0=All/1=Converted/2=NotConverted. testTag `listen_conversion_filter_dropdown`. `RecordingsListViewModel._conversionFilter` SSOT | `RecordingsListConversionFilter` (`ui/screens/recordings_list/RecordingsListConversionFilter.kt`), `filterByConversion` |
| `filterByConversion` | `(items, filter, convertedUris) → List<AudioItem>` — 변환 여부 필터 순수 함수. ViewModel 인라인 금지. All→그대로, Converted→uri.toString() in convertedUris, NotConverted→uri.toString() !in convertedUris | `filterByConversion` (`ui/screens/recordings_list/RecordingsListConversionFilterMapping.kt`) |
| `filterListenAudioItems` | Listen 화면의 오디오 소스 필터 SSOT (`internal`). `(mediaAndImported, recordings, filter) → List<AudioItem>`에서 `All`은 MediaStore+imported 입력만, `MyRecordings`는 recordings 입력만 사용하고 `Files`는 exhaustive 방어 분기로 MediaStore+imported 입력을 사용한다(실제 Listen ViewModel은 Files를 MyRecordings로 정규화). `dateAdded`·`id` 내림차순 정렬. 호출 순서는 `filterListenAudioItems` → `filterByConversion` → `sortDisplayedItems`. AudioPick의 `filterDisplayedItems`와 의미·구현·호출 경로를 분리한다. | `filterListenAudioItems` (`ui/screens/recordings_list/RecordingsListSourceFilterMapping.kt`) |
| `ConvertedVideoSourceFilter` | Converted 탭 소스 필터 enum — `All`(My recordings **제외** 전체) / `MyRecordings`(녹음 출처만). Listen `filterListenAudioItems`의 "All = My recordings 제외" 규칙과 동일 정의로 정렬. Group row는 첫 children의 `audioUri`로 판정(`isRecordingSourced`) | `ConvertedVideoSourceFilter` (`ui/screens/converted_videos/ConvertedVideoSourceFilter.kt`) |
| `filterConvertedVideosListRowsBySource` | `(rows, filter) → List<ConvertedVideosListRow>` — `All → rows.filterNot { isRecordingSourced() }`(recording-sourced row 제외), `MyRecordings → rows.filter { isRecordingSourced() }`. Group 내부 children 순서는 유지 | `filterConvertedVideosListRowsBySource`, `ConvertedVideosListRow.isRecordingSourced` (`ui/screens/converted_videos/ConvertedVideosFilterSortMapping.kt`) |
| `DropdownSelector` | Pill 배경(surfaceVariant/pill/labelMedium) 드롭다운 셀렉터. options/selectedIndex/onSelect/modifier/enabled/testTag/contentDescription(null→selectedLabel). Row semantics Role.Button | `DropdownSelector` (`ui/components/controls/DropdownSelector.kt`) |
| RecordingsList | Home Listen 탭에 임베드된 녹음 목록. 아이템 **「...」** 메뉴: Play / Rename(녹음 항목만) / Convert / Delete. import 칩·다중선택 Convert 칩. **비선택모드 본문 탭은 no-op**. top-level `AppDestination` 없음 (`RecordingsListTabContent`) | `RecordingsListTabContent`, `RecordingsListViewModel` |
| HomeTab | Home 3탭 SSOT. enum: `Record` / `Listen` / `ConvertedVideos`. MainActivity `currentHomeTab` `rememberSaveable` (기본 `Record`). ConvertedVideos는 `AppDestination`이 아님. 목록 보기/칩 랜딩: `onNavigateToConvertedVideos` → `AppDestination.Home` + `HomeTab.ConvertedVideos` (**route 복원 금지**; Success 확인은 Convert 잔류). 레거시 saved name `"ConvertedVideos"` → 탭만 (`restoreLegacyHomeTabFromSavedDestination`) | `HomeTab` (`HomeScreen.kt`), `currentHomeTab` (`MainActivity`) |
| ConvertedVideosTabContent | Home ConvertedVideos 탭 임베드 (nested TopBar 없음). top-level `AppDestination` 없음. 선택 모드 셸은 `ConvertedVideosHostChrome` (HomeScreen이 선택 TopBar 소유) | `ConvertedVideosTabContent` (`ConvertedVideosScreen.kt`) |
| ConvertedVideosHostChrome | ConvertedVideos 탭 선택 모드 셸 상태 (count·menu lock). nested TopBar 없음 — HomeScreen 선택 chrome이 소유 | `ConvertedVideosHostChrome`, `ConvertedVideosHostChromeActions` |
| onConvertAudioItems | Listen **「...」** 메뉴 Convert·다중선택 Convert 칩·import 칩 → Convert CTA. `(List<AudioItem>) -> Unit`. HomeScreen/MainActivity 전달. **Saving 게이트 없음** (import 칩 비활성과 비대칭). **비선택모드 본문 탭은 호출하지 않음**. ConvertedVideos 랜딩은 Convert 목록 보기/칩만. ~~onConvertRecordings~~ 대체 (Sprint E 이후 제거) | `RecordingsListTabContent`, `HomeScreen`, `MainActivity` |
| onImportAudioRequested | ~~Sprint E 이후 제거~~. import 칩은 `RecordingsListStateful` 내부 `rememberAudioMediaPermissionState` GetContent 로 처리 (`importAudioFromUri` → `importedAudio` → `onConvertAudioItems`). **네비게이션 경로 없음** — `AppDestination.AudioPick` 진입하지 않음. Saving 중 칩 비활성은 `navigationBlocked` 파라미터로 유지 | 삭제됨 (`RecordingsListScreen.kt`, `HomeScreen.kt`, `MainActivity.kt`) |
| onNavigateToConvertedVideos | Convert TopBar 칩(`drawer_converted_videos`)·Success 「목록 보기」(`conversion_success_view_list`) 콜백. 확인(`action_confirm`)은 이 콜백을 호출하지 않음. MainActivity 본문만: `AppDestination.Home` + `HomeTab.ConvertedVideos`. live `AppDestination.ConvertedVideos` **복원 금지** | `ConvertScreen(onNavigateToConvertedVideos=)`, `MainActivity` |
| restoreLegacyHomeTabFromSavedDestination | 구버전 saved name `"ConvertedVideos"` → `HomeTab.ConvertedVideos` (탭만). `restoreAppDestination`은 `Home`만 반환; 탭은 `AppDestinationSaver.restore` 경유 1회 | `restoreLegacyHomeTabFromSavedDestination` (`MainActivity.kt`) |
| resolveDrawerSelected | 드로어 selected 매핑. Home / Options / Trash / ErrorLog / MicrophoneSource만 selected; Convert·AudioPick·BackgroundPick 등 **else → null**. 드로어 행은 이 5개뿐 | `resolveDrawerSelected` (`MainActivity.kt`), `AppDrawerContent` |
| TrashedItem | 통합 휴지통 엔티티 1건 (DB row) | `TrashedItem`, `TrashedItemDao`, `trashed_items` |
| TrashRepository | 휴지통 이동·복원·영구삭제·만료정리 저장 계층 | `TrashRepository` |
| TrashPurgeWorker | 15일 보존 만료 휴지통 항목 일 1회 영구삭제 Worker | `TrashPurgeWorker`, `TRASH_PURGE_UNIQUE_WORK_NAME` (`data/TrashPurgeWorker.kt`) |
| TrashScreen | 휴지통 화면 Composable (목록/빈 상태/복원/영구삭제 UI) | `ui/screens/trash/TrashScreen.kt` |
| TrashViewModel | 휴지통 화면 상태·액션 ViewModel | `ui/screens/trash/TrashViewModel.kt` |
| recordingExtension | RecordingFormat.name → 파일 확장자 (m4a/wav). record 패키지 SSOT | `record/RecordingExtension.kt` |
| recordingAudioItemId | recording_records.id → AudioItem.id (`-recordingId - 1`) | `data/RecordingAudioMapping.kt` |
| recordingIdFromAudioItemId | AudioItem.id → recording_records.id (`Long?`). 음수(녹음 AudioItem.id) → `recordingAudioItemId(audioItemId)` 재사용(involution), 양수/0(MediaStore id) → null. [recordingAudioItemId]의 역함수 SSOT | `data/RecordingAudioMapping.kt` |
| AudioItem.fileName | MediaStore DISPLAY_NAME 그대로. 녹음 항목은 `recordingAudioItemTitle(filePath)`. 미지정 시 빈 문자열 기본값 | `data/AudioItem.kt` |
| AudioItem.folderLabel | MediaStore RELATIVE_PATH 끝 슬래시 제거 결과. 녹음 항목은 `RECORDING_AUDIO_FOLDER_LABEL`(`"Music/C2V"`). API < Q 또는 미지정 시 null | `data/AudioItem.kt`, `data/RecordingAudioMapping.kt` |
| RECORDING_AUDIO_FOLDER_LABEL | `mapRecordingToAudioItem` folderLabel SSOT — `"Music/C2V"` 리터럴 중복 금지 | `data/RecordingAudioMapping.kt` |
| MediaDeleteOutcome | 오디오 파일 삭제 결과 sealed class — `Deleted` / `NeedsConfirmation(intentSender)` / `Failed`. `AudioRepository` 인라인 정의 | `data/AudioRepository.MediaDeleteOutcome` |
| deleteAudioFile | `suspend fun deleteAudioFile(uri: Uri): MediaDeleteOutcome`. R+(API30)→createDeleteRequest NeedsConfirmation; Q(29)→delete/RecoverableSecurityException; 26-28→delete/catch Failed | `AudioRepository.deleteAudioFile` |
| observeConvertedAudioUris | `ConversionHistoryRepository`가 반환하는 `Flow<Set<String>>` — 변환 이력 audioUri 집합. AudioPickViewModel `convertedAudioUris` SSOT | `ConversionHistoryRepository.observeConvertedAudioUris` |
| `EXTRA_QUICK_RECORD` | App Shortcut 트리거 Intent Extra 키. 값 = FQCN `"com.example.convert2video.EXTRA_QUICK_RECORD"`. shortcuts.xml `android:name`과 반드시 동일. XML extra는 String `"true"`; 코드는 Boolean도 가능. 파서는 [isQuickRecordRequested]. | `MainActivity.EXTRA_QUICK_RECORD`, `shortcuts.xml` `<extra android:name="...">` |
| `quick_record` | App Shortcut ID(`res/xml/shortcuts.xml`). 런처에 「Record/녹음」 액션 노출. | `shortcuts.xml shortcutId="quick_record"` |
| `isQuickRecordRequested` | Intent extra 파서 SSOT. `getStringExtra` `"true"`(ignoreCase) **먼저**, Boolean `getBooleanExtra`는 폴백. `Bundle.get` 금지. missing/`"false"` → false. `onCreate`/`onNewIntent`는 이 함수만 사용. | `Intent.isQuickRecordRequested()` |
| `stripQuickRecordExtra` | consume 시 extra 제거 SSOT. missing이면 no-op. | `Intent.stripQuickRecordExtra()` |
| `markQuickRecordPending` | extra 확인 후 pending=true. `onCreate`/`onNewIntent`만 호출. | `MainActivity.markQuickRecordPending()` |
| `consumePendingQuickRecord` | 탭-only(`!RECORD_AUDIO`면 탭 이동 후) 또는 `start()` 후 [shouldConsumeAfterQuickStart]가 true일 때만 CAS + [stripQuickRecordExtra] + `setIntent`. 권한 있음 경로는 수락 확인 후에만 Home+Record 이동. `Failed`면 destination·extra 유지. | `MainActivity.consumePendingQuickRecord()` |
| `shouldConsumeAfterQuickStart` | `start()` 이후 consume 수락 SSOT. `after !is RecordingState.Failed`면 true. [isActiveRecordingSession]은 defer 가드 전용. | `shouldConsumeAfterQuickStart(RecordingState)` |
| `shouldDeferQuickRecord` | Quick Record 가드 SSOT. Saving / active session / Saved sticky / Review pending / [isConversionBlockingQuickRecord]면 destination·`start()`·consume 금지, pending·extra 유지. Effect 키는 Boolean만. | `shouldDeferQuickRecord(..., isReviewPending)` |
| `isConversionBlockingQuickRecord` | 변환 중·결과 다이얼로그(dismiss 전)면 Quick Record start 금지. InProgress / Success / Failed. dismiss 후 Idle이면 재시도. | `isConversionBlockingQuickRecord(ConversionUiState)` |
| `REQ_LAUNCH_TILE` | Quick Settings 타일 권한 유도 PendingIntent requestCode `90001` — [scheduledRecordingAppLaunchPendingIntent]와 쌍. [EXTRA_QUICK_RECORD] 미사용 | `record/RecordingQuickAction.kt` |
| `REQ_LAUNCH_WIDGET` | Glance QuickRecord 위젯 권한 유도 PendingIntent requestCode `90002` — Shortcut/Widget 경로 [EXTRA_QUICK_RECORD] 금지 | `record/RecordingQuickAction.kt` |
| `_pendingQuickRecord` / `quickRecordFlow` | `onNewIntent` → Compose 브리지 `MutableStateFlow<Boolean>` (**private**). `MainAppContent`는 read-only `quickRecordFlow` + [consumePendingQuickRecord]만. | `MainActivity.consumePendingQuickRecord()`, `MainAppContent(quickRecordFlow=...)` |
| `SUPPORT_EMAIL` | CS 이메일 주소 상수. **플레이스홀더** — Play 출시 전 `ContactUsIntent.kt` 단일 지점만 교체. `ContactUsIntent.kt` 단일 소스 | `internal const val SUPPORT_EMAIL` (`ui/screens/options/ContactUsIntent.kt`) |
| `buildContactUsIntent` | `mailto:SUPPORT_EMAIL` URI + UTF-8 인코딩된 `subject`·`body` 쿼리 파라미터 포함(Gmail 등 EXTRA_* 무시 방어). body LF → CRLF 변환(`crlfBody`)은 1회만 수행하고 URI(`appendQueryParameter`·이중 인코딩 방지) 와 `EXTRA_TEXT` 양쪽에 동일하게 사용. URI는 `Uri.fromParts("mailto", SUPPORT_EMAIL, null).buildUpon().appendQueryParameter(...)` 패턴(문자열 interpolation 금지). `appName`은 `context.getString(R.string.app_name)` 1회 호출 후 subject·body 양쪽 재사용. body lines: app version name+code, Android RELEASE+SDK_INT, manufacturer+model. 네트워크 호출 없음. API 30+ `<queries>` 미추가(이번 스프린트 범위 밖). `ActivityNotFoundException`·`SecurityException`·`Exception` 핸들링은 호출부(OptionsScreen) 책임 | `buildContactUsIntent(context)` (`ui/screens/options/ContactUsIntent.kt`, internal) |
| `OptionsContactUsContent` | Options CS 문의 섹션 무상태 Composable. `onEmailClick` 콜백만 받음. Composable이 직접 읽는 문자열: `options_contact_us_title`·`options_contact_us_description`·`options_contact_us_button`·`cd_options_contact_us`. `chooser_title`·`no_email_app`·`launch_failed`·`options_contact_us_section`은 **호출부(OptionsScreen stateful)** 소유. 런치는 호출부에서 `Intent.createChooser`(chooser_title). `TEST_TAG_CONTACT_US_EMAIL`(`contact_us_email_button`) testTag | `OptionsContactUsContent` (`ui/screens/options/OptionsScreen.kt`, internal) |

### 2. 주요 상수

| 이름 | 위치 | 값/역할 |
|------|------|---------|
| `CONVERSION_UNIQUE_WORK_NAME` | `video/ConversionWorkNames.kt` | `"audio_to_video_conversion"` — 수동 unique work **literal** SSOT (internal) |
| `CONVERSION_WORK_NAME` | `ConvertViewModel` companion | `CONVERSION_UNIQUE_WORK_NAME` **별칭**. `conversionState` 구독·enqueue·cancel 전용 |
| `MANUAL_CONVERSION_WORK_NAME` | `RecordingAutoConvertTrigger` | `CONVERSION_UNIQUE_WORK_NAME` **별칭** (convert import 없이). 수동 unique 조회 전용. auto unique에 재사용 금지 |
| `autoConvertWorkName` | `RecordingAutoConvertTrigger` | `auto_convert_` + Uri.encode(fileUri). **수동 unique와 별도 work name** — literal SSOT와 다른 네이밍 스킴 |
| `TRASH_PURGE_UNIQUE_WORK_NAME` | `TrashPurgeWorker` companion | `"trash_purge"` — 휴지통 만료 purge unique **periodic** work literal SSOT. `Convert2videoApplication.onCreate`에서 `enqueueUniquePeriodicWork` + `ExistingPeriodicWorkPolicy.KEEP` |
| `recordingBackupWorkName` / `KEY_*` | `RecordingBackupTrigger` | `"recording_backup_" + backupId` unique work + ACTIVE/TRASHED/REMOVE worker input key SSOT. `ExistingWorkPolicy.APPEND_OR_REPLACE`로 같은 stable backupId 이벤트만 직렬화 |
| `DEFAULT_RETENTION_DAYS` | `TrashRepository` companion | `15` — `TrashPurgeWorker`·수동 purge 기본 보존 일수 |
| `STORE_ID` | `app/build.gradle.kts` 플레이버 `buildConfigField` | `google`→`"google_play"`, `huawei`→`"huawei"`. `StoreCapabilities.forStoreId` 입력 |
| `GOOGLE_SERVICES_ENABLED` | `app/build.gradle.kts` 플레이버 `buildConfigField` | `google`→`true`, `huawei`→`false`. 코드에서 직접 읽지 말고 `StoreCapabilities`(`store/StoreCapabilities.kt`) 경유 |
| `BACKEND_BASE_URL` | `app/build.gradle.kts` `BuildConfig` | `-PbackendBaseUrl`(명시적 빈 값 포함) → `BACKEND_BASE_URL` 환경 변수 → 빈 값. trim + trailing slash 제거; release는 유효한 HTTPS non-placeholder 값만 허용 |
| `PRO_PRODUCT_ID` | `billing/BillingGateway.kt` | `pro_lifetime_unlock` 단일 non-consumable SKU. provider·UI·backend 계약에서 재선언 금지 |
| `pro_lifetime_unlock` | `PRO_PRODUCT_ID` | Google Play/Huawei 공통 Pro lifetime unlock 상품 ID |
| `entitlement` (DataStore name) | `billing/EntitlementStore.kt` | `entitlement.preferences_pb` 파일 생성. Keystore AES-256-GCM 암호화 payload만. backup/device-transfer exclude |
| `/entitlement/verify` · `/entitlement/refresh` | `backend/public/index.php` | 제한된 billing POST route. `EntitlementController::verify/refresh`. Android 호출자는 `EntitlementApiClient` (HTTPS only). refresh는 JWT `sub` ↔ body `install_id` binding 검증(불일치 409) |
| `pro_upgrade_button` / `pro_restore_button` / `pro_status_text` | `OptionsProUpgradeContent` | 안정 Compose testTag. Pro 섹션 upgrade/restore/status |
| `WorkInfoUiPhase` | `ui/shared/WorkInfoUiPhase.kt` | WorkInfo phase tier enum (internal) — Active/Succeeded/Failed/Cancelled |
| `toWorkInfoUiPhase` | `ui/shared/WorkInfoUiPhase.kt` | `WorkInfo`/`WorkInfo.State` → phase (internal). payload mapper보다 선행 |
| `appString` | `utils/AppString.kt` | `Context`/`AndroidViewModel` `@StringRes` helper (internal) |
| `requireApplication` | `utils/RequireApplication.kt` | `Context.requireApplication()` (internal) |
| `last_used_background_path` | `SettingsRepository` | DataStore string key. 미설정 null ↔ `lastUsedBackgroundPath` / `setLastUsedBackgroundPath` |
| `pending_countdown_start_elapsed_millis` | `SettingsRepository` | DataStore long key. countdown START elapsedRealtime |
| `pending_countdown_duration_minutes` | `SettingsRepository` | DataStore int key. countdown 녹음 분 (1..180) |
| `pending_countdown_recording_started_elapsed_millis` | `SettingsRepository` | DataStore long key. COUNTDOWN START Accepted 시각 |
| `ACTION_COUNTDOWN_START` / `ACTION_COUNTDOWN_STOP` | `RecordingCountdownAlarmScheduler` | `com.example.convert2video.action.COUNTDOWN_RECORDING_*` |
| `REQUEST_CODE_COUNTDOWN_START` / `REQUEST_CODE_COUNTDOWN_STOP` | `RecordingCountdownAlarmScheduler` | `-71001` / `-71002` |
| `options_recording_countdown_*` / `cd_options_recording_countdown_*` | `strings.xml` | Options Quick Timer 라벨·상태·cd (영문 리터럴 금지) |
| `KEY_BACKGROUND_PATH` | `ConversionWorker` | Worker 입력 |
| `KEY_AUDIO_URI` | `ConversionWorker` | Worker 입력 |
| `KEY_SEGMENT_START_US` | `ConversionWorker` | Range 시작 (Long, µs) — END와 쌍 |
| `KEY_SEGMENT_END_US` | `ConversionWorker` | Range 끝 (Long, µs) — START와 쌍 |
| `KEY_SEGMENT_BATCH_ID` | `ConversionWorker` | Batch ID (String) — INDEX+TOTAL와 쌍 |
| `KEY_SEGMENT_INDEX` | `ConversionWorker` | Batch 인덱스 (Int) — BATCH_ID+TOTAL와 쌍 |
| `KEY_SEGMENT_TOTAL` | `ConversionWorker` | Batch 총개수 (Int) — BATCH_ID+INDEX와 쌍 |
| `KEY_HISTORY_RECORDED` | `ConversionWorker` | success Data — 이력 insert 성공 여부 |
| `VIDEO_FRAME_RATE` | `VideoConverter` | 정지 이미지 FPS |
| `VIDEO_SHORT_SIDE_PX` | `VideoConverter` | 짧은 변 상한(px) |
| `C2V_FOLDER` | `C2vOutputNames` | `Movies/C2V` 신규 저장 폴더 |
| `LEGACY_FOLDER` | `C2vOutputNames` | `Movies/convert2video` 레거시 폴더(조회 OR) |
| `MIN_SEGMENT_DURATION_US` | `VideoSegmentPlanner` | **계획** 구간 최소 길이 60초 (`60_000_000`). export 길이와 다름(아래 SSOT) |
| `MAX_SEGMENT_COUNT` | `VideoSegmentPlanner` | 분할/배치 상한 `20` |
| `AUDIO_CLIP_SAFETY_MARGIN_US` | `VideoConverter` | export 끝에서 빼는 마진 `300_000`(0.3초). Mp3Extractor EOF 방어 |
| `effectiveClipDurationUs` | `VideoConverter` | 계획 구간에서 마진을 뺀 export 길이 helper (`end - margin` ≥ `start`) |
| `ACTION_START` / `ACTION_PAUSE_RESUME` / `ACTION_STOP` | `RecordingService` | 녹음 포그라운드 Service 액션 |
| `ACTION_START` / `ACTION_STOP` / `SERVER_PORT` / `PING_ROUTE` / `UPLOAD_ROUTE` | `PairingServerService` / `PairingProtocol` | 데스크톱 페어링 서버 액션·`47321` 포트·`/ping`(Service 동반자) 및 Bearer 인증 `/upload` route — `PING_ROUTE`는 `PairingServerService` companion, 그 외 라우트는 `PairingProtocol` SSOT |
| `PAIR_REQUEST_ROUTE` | `PairingProtocol` (internal SSOT 리터럴) + `PairingServerService` companion (동일 값 독립 재선언 — re-export 아님; 라우팅 블록은 companion 값을 직접 사용) | `"/pair/request"` |
| `WHO_AM_I_ROUTE` | `PairingProtocol` (internal SSOT 리터럴) + `PairingServerService` companion (동일 값 독립 재선언 — re-export 아님; 라우팅 블록은 companion 값을 직접 사용) | `"/whoami"` |
| `PAIRING_REQUEST_TIMEOUT_MILLIS` | `PairingProtocol` | `60_000L` (internal) |
| `PAIRING_REQUEST_MAX_BODY_BYTES` | `PairingProtocol` | `16 * 1024L` (internal) |
| `MAX_UPLOAD_BYTES` | `PairingProtocol` SSOT; `PairingServerService`는 재노출 별칭 | `500L * 1024 * 1024` |
| `NsdAdvertiser.SERVICE_TYPE` | `NsdAdvertiser` companion | `"_c2vsync._tcp"` |
| `NsdAdvertiser.MAX_REGISTRATION_ATTEMPTS` | `NsdAdvertiser` companion | `3` |
| `UPLOAD_FILE_FIELD` / `UPLOAD_ORIGINAL_FILE_NAME_FIELD` / `UPLOAD_FORMAT_FIELD` / `UPLOAD_DURATION_MS_FIELD` | `PairingProtocol` | `file` / `originalFileName` / `format` / `durationMs` (internal) |
| `UPLOAD_FORMAT_WAV` | `PairingProtocol` | `"wav"`; 불일치 → 즉시 400 (internal) |
| `EXTRA_FORMAT` | `RecordingService` | `RecordingFormat.name` (`AAC`/`WAV`) — DataStore `storageValue`와 별개, 변경 금지 |
| `recording_format` | `SettingsRepository` | DataStore key. 값 `aac`/`wav` ↔ `RecordingFormat.fromStorageValue` / `storageValue` |
| `recording_backup_folder_uri` / `KEY_RECORDING_BACKUP_FOLDER_URI` | `SettingsRepository` | 녹음 백업 SAF tree URI key. `RecordingBackupFolder.SelectionResult.Persisted`일 때만 쓰며 URI/path 로그·UI 공개 금지 |
| `recordingBackupFolderState` / `refreshRecordingBackupFolderState` / `onRecordingBackupFolderSelected` | `OptionsViewModel` | persisted read+write grant 3-state SSOT. Options 진입·ON_RESUME에서 refresh; picker 결과는 IO에서 `RecordingBackupFolder.handleSelection` 처리 후 Persisted URI만 저장 |
| `options_recording_backup_*` / `cd_options_recording_backup_*` | `strings.xml` | Options 녹음 백업 카드 상태·선택/재연결·복원 진행·info·a11y 문자열. `.nomedia` 미디어 스캔 전용, 재설치 뒤 동일 폴더 재선택을 명시 |
| `recording_backup_card` / `recording_backup_status_unavailable` / `recording_backup_status_connected` / `recording_backup_status_broken_grant` / `recording_backup_select_folder_button` / `recording_backup_reconnect_button` / `recording_backup_info_button` / `recording_backup_info_confirm_button` | `OptionsRecordingBackupContent` | 안정 Android Compose testTag. Select/Reconnect 및 info dialog open/dismiss 테스트에 사용 |
| `noiseReductionModeHot` | `SettingsRepository` companion | 프로세스 전역 hot StateFlow. `RecordingEngine.create()`가 동기적으로 읽음. 격리=`resetNoiseReductionModeHotForTests()` |
| `microphone_source` / `KEY_MICROPHONE_SOURCE` | `SettingsRepository` | DataStore string key. 값 `default`/`bluetooth` ↔ `MicrophoneSource.fromStorageValue` / `storageValue` |
| `microphoneSourceHot` | `SettingsRepository` companion | 프로세스 전역 마이크 소스 hot StateFlow. `RecordingEngine.create()`가 동기적으로 읽음. 격리=`resetMicrophoneSourceHotForTests()` |
| `drawer_microphone_source` | `strings.xml` | 드로어 5번째 행 라벨 (en: Microphone source / ko: 마이크 소스). testTag `drawer_microphone_source_item`. Convert·ConvertedVideos 행 추가 금지 |
| `microphone_source_title` / `microphone_source_default` / `microphone_source_bluetooth` / `microphone_source_description` / `microphone_source_info_title` / `microphone_source_info_body` / `microphone_source_connected_hint` / `microphone_source_not_connected_hint` / `cd_microphone_source_info` | `strings.xml` | MicrophoneSource 화면 라벨·힌트·info·cd (`options_` prefix 아님). testTag `microphone_source_segmented_control` / `microphone_source_info_button` / `microphone_source_info_confirm_button` / `microphone_source_description` / `microphone_source_connection_hint` |
| `wifi_only_upload` | `SettingsRepository` | DataStore boolean key. 기본 **`true`** ↔ `wifiOnlyUpload` / `setWifiOnlyUpload` |
| `youtube_auto_upload_enabled` | `SettingsRepository` | DataStore boolean key. 기본 `false` ↔ `youtubeAutoUploadEnabled` / `setYoutubeAutoUploadEnabled` |
| `options_youtube_auto_upload` / `options_youtube_auto_upload_description` / `options_youtube_auto_upload_info_title` / `options_youtube_auto_upload_info_body` / `cd_options_youtube_auto_upload` / `cd_options_youtube_auto_upload_info` | `strings.xml` | Options YouTube 자동변환 라벨·설명·info 다이얼로그·cd (영문 리터럴 금지; lastUsedBackgroundPath 수동 변환 배경 재사용 안내) |
| `options_recording_format_info_title` / `options_recording_format_info_body` / `cd_options_recording_format_info` | `strings.xml` | Options 녹음 포맷 info 다이얼로그·cd (`recording_format_info_button` / `recording_format_info_confirm_button`) |
| `options_noise_reduction_info_title` / `options_noise_reduction_info_body` / `cd_options_noise_reduction_info` / `options_noise_reduction_device_default_hint_available` / `options_noise_reduction_device_default_hint_unavailable` | `strings.xml` | Options 잡음 감소 info·DeviceDefault 지원 힌트 (`noise_reduction_info_button` / `noise_reduction_info_confirm_button` / `noise_reduction_device_default_hint`) |
| `options_drive_storage_used_percent` / `options_drive_storage_used_unlimited` / `options_drive_storage_unit_b` / `options_drive_storage_unit_kb` / `options_drive_storage_unit_mb` / `options_drive_storage_unit_gb` / `cd_options_drive_storage` | `strings.xml` | Options Drive 저장공간 사용량 라벨·단위·cd (코드에 GB/used 리터럴 금지; 10진 SI 1000) |
| `isYoutubeSignedInUi` | `OptionsYoutubeAccountContent` | live signed-in 게이트 — `isAuthorized && channelTitle != null` |
| `isYoutubeAutoUploadReady` | `OptionsYoutubeAccountContent` | live Switch 게이트 — `youtubeAutoUploadEnabled != null` |
| `youtube_channel_checking` | `strings.xml` | 채널 확인 중 텍스트 (loading·대기 분기 공통). 대기 분기는 인디케이터 없음 |
| `youtube_channel_loading_indicator` / `youtube_login_button` / `youtube_sign_out_button` / `youtube_retry_button` | `OptionsYoutubeAccountContent` | live testTag. 인디케이터는 `isChannelTitleLoading` 분기만 |
| `language_prompt_shown` / `KEY_LANGUAGE_PROMPT_SHOWN` | `SettingsRepository` | DataStore boolean key. 기본 `false` ↔ `languagePromptShown` / `setLanguagePromptShown` |
| `drive_auto_upload_enabled` | `SettingsRepository` | DataStore boolean key. 기본 `false` ↔ `driveAutoUploadEnabled` / `setDriveAutoUploadEnabled` |
| `isDriveAuthInFlight` | `OptionsViewModel` | Drive 동의/이메일 조회 진행 플래그. 상태 머신 A 진행 표시 SSOT |
| `driveAccountEmail` | `OptionsViewModel` | Drive 이메일. non-null = Options 로그인됨 UI 게이트 |
| `onDriveAuthorizationLaunchFailed` | `OptionsViewModel` | launcher.launch 실패 seam — in-flight 해제 + `drive_auth_incomplete` |
| `drive_auth_incomplete` / `drive_login_cancelled` / `drive_email_checking` | `strings.xml` | Options Drive Fallback·cancel·진행 표시 (drive/** 하드코딩 대신) |
| `options_language_section` / `options_language_english` / `options_language_korean` / `language_picker_title` / `language_picker_apply_failed` | `strings.xml` | Options·LanguagePicker 언어 UI (하드코딩 대신) |
| `CHANNEL_ID` | `RecordingService` | `recording_progress` (IMPORTANCE_LOW) |
| `MIN_SAVE_DURATION_MS` | `RecordingErrorCodes` | 저장 최소 녹음 길이 `5000`ms. `isTooShortForSave`가 `durationMs <=` inclusive 판정 — **Service handleStop**에서만 적용(Engine.stop 아님). user string `recording_too_short`의 '5초'와 수동 동기 |
| `isTooShortForSave` | `RecordingErrorCodes` | `(durationMs) → Boolean` — `durationMs <= MIN_SAVE_DURATION_MS`. handleStop TOO_SHORT 가드 SSOT |
| `deleteRecordingOutputOrLog` | `RecordingEngine.kt` | TOO_SHORT/INDEX_FAILED 산출 삭제. basename만 로그, 실패 시 1회 재시도. `internal` |
| `RecordingSavedWait` | androidTest `record/` | Saved 경로 대기 SSOT — `SAVED_WAIT_SLACK_MS`·`minElapsedForSavedMs`·`timeoutForSavedWaitMs`. Service/Controller/Screen/VM 테스트 공용 |
| `FILE_PROVIDER_AUTHORITY` | `C2vOutputNames` | 녹음 URI도 동일 authority 재사용 (`Music/C2V` path 추가) |
| `formatMediaDurationMs` | `ui/shared/MediaDurationFormat.kt` | UI duration 포맷 단일 함수 (`MediaDurationStyle` 분기) |
| `MAX_AMPLITUDE` | `RecordScreen.kt` | MediaRecorder maxAmplitude 상한 `32767` — LevelMeter normalize SSOT |
| `onRecordingSaved` | `RecordScreen` | Phase 3 seam `(File) -> Unit`. 성공→Home, 실패→Record 잔류+discardStickySaved. Active 재진입 Out-of-scope |
| `resolveRecordingSavedSeamAction` | `MainActivity.kt` | URI fold 정책 순수 함수 — 성공/실패 navigate·clearTerminal·feedback 플래그 SSOT |
| `applyRecordingSaved` | `ConvertViewModel` | 녹음 seam 성공 → `AudioSourceTab.Record` + setAudioList. startConversion 미호출 |
| `applyFilePickSaved` | `ConvertViewModel` | AudioPick 완료 → `AudioSourceTab.FilePick` + setAudioList. applyRecordingSaved 대칭 |
| `reportRecordingUriFailed` | `ConvertViewModel` | FileProvider 실패 메시지. **목록·탭 유지**(출처 일치). Activity는 Record 잔류+discardSticky+Toast |
| `discardStickySavedAfterUriFailure` | `RecordViewModel` | URI 실패 시 sticky Saved만 제거. seam one-shot 키는 유지(재발사 방지) |
| `RecordingRepository.contentUriFor` | `RecordingRepository` | 녹음 파일 FileProvider URI SSOT (Activity seam·`uriFor` 공유) |
| `consumeSavedCallback` | `RecordViewModel` | `onRecordingSaved` one-shot 키(**absolute path**). Toast([consumeSavedToast])와 키 저장소 독립 |
| `isSavingGate` | `RecordViewModel` | stop() 직후~Idle/Saved/Failed/Review local Saving 게이트. Review에서 false. [shouldBlockNavigationWhileRecordingSaving]가 `RecordUiState.Saving`과 OR |
| `keepRecording` / `discardRecording` | `RecordViewModel` | Review Keep(insert+Saved+seam 1회) / Discard(`TrashRepository.moveToTrash` RECORDING_AUDIO `wasIndexed=false`→Idle). UI는 Controller만(Trash/Dao 금지). Keep in-flight면 Discard no-op. Review 버튼 enabled는 `isReviewActionInFlight`의 역. `clearTerminalState`는 Review 미포함 |
| `isReviewActionInFlight` | `RecordingController` | Review Keep/Discard in-flight `StateFlow<Boolean>`. Discard/Start no-op. RecordContent Keep/Discard `enabled` 게이트 | `RecordingController.isReviewActionInFlight` |
| `recordingFormatFromFile` | `record/RecordingExtension.kt` | Review Keep extras용 확장자 → RecordingFormat. `wav`/`WAV`→WAV, `m4a`/unknown→AAC | `recordingFormatFromFile` |
| `RecordingService.isRunning` | `RecordingService` | Service occupancy (녹음 중 + Review 파일 보유 + Keep in-flight). Tile `isActiveRecordingSession`(Recording/Paused/Stopping only)과 별개 — Review는 occupancy true, active session false | `RecordingService.isRunning()` |
| `flushPendingSavedCallback` | `RecordViewModel` | Record Again 직전 pending `onRecordingSaved` seam 1회 flush. 반드시 [clearTerminalState] **앞**에 호출. 반환 `File?`(없으면 null) |
| `consumeSavedToast` | `RecordViewModel` | Saved Toast one-shot 키(**absolute path**, callback과 동일 identity·독립 저장소). UI 표시는 `File.name`만 |
| `shouldBlockNavigationWhileRecordingSaving` | `MainActivity.kt` | `(recordUiState, isSavingGate) → Boolean` — Saving 네비게이션 차단 SSOT (`isSavingGate \|\| Saving`). Listen import 칩(`navigationBlocked`)은 동일 게이트로 비활성. `onConvertAudioItems`(「...」 Convert·다중선택 Convert 칩·import)는 **이 게이트를 쓰지 않음** (의도적 비대칭) |
| `recordings_list_import_action` | `strings.xml` | Listen import chip 라벨 (en: Import file / ko: 파일 가져오기). testTag `recordings_list_import_button` |
| `drawer_converted_videos` | `strings.xml` | Home 3번째 탭 라벨 + Convert TopBar 「변환 결과」칩. **드로어 행 키가 아님**. ConvertedVideos를 드로어 행으로 추가 금지. Success 목록 버튼은 `conversion_success_view_list`(en: View list / ko: 목록 보기)를 쓰고 **이 키를 쓰지 않음** |
| `conversion_success_view_list` | `strings.xml` | Success 다이얼로그 「목록 보기」버튼. dismiss + `onNavigateToConvertedVideos`. `drawer_converted_videos`·`action_confirm`과 혼동 금지 |

**SSOT (계획 길이 ≠ export 길이):** Planner `MIN_SEGMENT_DURATION_US`는 half-open **계획** 구간 길이. Converter는 `AUDIO_CLIP_SAFETY_MARGIN_US`를 빼 `effectiveClipDurationUs` / `computeClipWindow`로 export한다. 예: 60초 계획 → 약 59.7초 export.

**Worker 세그먼트 키 규칙 (Sprint 7-2):** Range(START+END)와 Batch(BATCH+INDEX+TOTAL)는 독립. 각 세트는 **키 존재**로 none/all/partial 판정 (`getLong` 기본 0에 의존 금지). none → 전체 변환 + segment* null. all → 해당 세트 적용. partial → `Result.failure`(세그먼트 입력 오류).

**RecordingFormat SSOT:** `record/RecordingFormat.kt`에만 enum 정의. Engine/Repo/Service/Names는 import만 — 재선언 금지.

**MicrophoneSource SSOT:** `record/MicrophoneSource.kt`에만 enum 정의. Settings는 import만 — 재선언 금지. DataStore `storageValue`(`default`/`bluetooth`). 읽기=`fromStorageValue`(unknown/null→Default). Bluetooth SCO 미연결은 오류 없이 Default 폴백. `BLUETOOTH_CONNECT` 없음 — `MODIFY_AUDIO_SETTINGS`만. WAV Bluetooth 샘플레이트는 44100→16000→8000.

**RecordingScheduleRepeatMode SSOT:** `record/RecordingScheduleRepeatMode.kt`에만 enum 정의. Room `repeatMode`는 `.name` 문자열. TypeConverter 금지. 쓰기=`requireFromStorageValue`(unknown→IllegalArgumentException). 읽기/복구=`fromStorageValue`(unknown→ONCE+AppLogger.w). **동일 분 금지**(startMinuteOfDay==endMinuteOfDay). overnight(end<start) 허용. WEEKLY `daysOfWeekMask` 최소 1 bit.

**RecordingScheduleAlarmScheduler SSOT:** `record/RecordingScheduleAlarmScheduler.kt`만 AlarmManager 등록/취소·다음 START 시각. `RecordingScheduleRepository`는 Alarm/Context 금지. 요일 bit0=월..bit6=일(재선언 금지). `ScheduledRecordingReceiver` FQCN·action·extras 상수는 스케줄러 재사용(Manifest 등록). **ONCE `nextTriggerEpochMillis`:** 다음 벽시계 start만(과거면 다음날) — past→null 하지 않음. one-shot 소비/`setEnabled(false)`는 Receiver·VM. `stopTriggerEpochMillis`는 ZoneId/ZonedDateTime 벽시계 end(DST-safe; raw durationMinutes*60000 금지; spring-forward·fall-back 모두 벽시계). `register*`는 exact-alarm 권한 확인 후 `Boolean`(성공만 true); `!enabled`/nextTrigger null이면 cancel 후 false; **권한 false도 cancel stale** 후 false; **setExact 전 cancel 금지**(실패 시 기존 유지, 성공 후 stale STOP만 cancel). `MAX_SCHEDULE_ID_FOR_REQUEST_CODE` overflow → register false / cancel no-op. Receiver extras는 `hasExtra` 필수.

### 2-1. 파일명 규칙

원본 오디오 파일명(`ContentResolver` `OpenableColumns.DISPLAY_NAME` 조회) 기반이 기본, 조회 실패/공백/새니타이즈 후 빈 결과일 때만 타임스탬프로 폴백한다.

| 패턴 | 예시 |
|------|------|
| 원본 파일명 기반 | `녹음 2026-08-02 10-15-30.mp4` |
| 원본 + 세그먼트 NofM | `녹음 2026-08-02 10-15-30_1of3.mp4` |
| 폴백: `(C2V)yyyy-MM-dd_HH-mm.mp4` | `(C2V)2026-07-24_08-30.mp4` |
| 폴백 + 같은 분 충돌 시 `_n` 접미사 | `(C2V)2026-07-24_08-30_2.mp4` |
| 폴백 + 세그먼트 NofM | `(C2V)2026-07-28_13-55_1of3.mp4` |
| 폴백 + NofM + `_n` 충돌 | `(C2V)2026-07-28_13-55_1of3_2.mp4` |

`C2vOutputNames.buildDisplayName(existingNames, segmentIndex?, segmentTotal?, originalFileStem?)` — `originalFileStem`을 [sanitizeOriginalFileStem]으로 새니타이즈(확장자 제거, 파일시스템 금지 문자/제어문자 → `_`, 공백 트림, `MAX_ORIGINAL_STEM_LENGTH`(80자) 제한)한 결과가 있으면 그 값을 stem으로, 없거나(null/공백) 새니타이즈 결과가 빈 문자열이거나 `_`만 남으면 타임스탬프로 폴백. segmentIndex/segmentTotal 둘 다 non-null이면 stem 뒤에 NofM 접미사. 충돌 시 stem 기준 `_2`…`_99`. 저장 시점 TOCTOU는 `MediaStoreSaver`가 `createNewFile` 선점 + `_n` 재시도. 원본 파일명 조회(`ConversionWorker.queryOriginalFileName`)는 실패해도 변환 자체를 실패시키지 않고 null로 폴백한다.

#### 2-1b. 녹음 파일명 (`C2vRecordingNames`)

저장 디렉터리: `getExternalFilesDir(DIRECTORY_MUSIC)/C2V` (영상 `Movies/C2V`와 별개).

| 패턴 | 예시 |
|------|------|
| AAC | `(C2V)yyyy-MM-dd_HH-mm-ss.m4a` |
| WAV | `(C2V)yyyy-MM-dd_HH-mm-ss.wav` |
| 같은 초 충돌 `_n` | `(C2V)2026-08-03_10-00-00_2.m4a` |

타임스탬프는 **초 단위**. 녹음 시작 전 `claimUniqueDestFile`(`createNewFile`)로 원자적 선점.

### 2-2. Room 스키마 (v9)

| 테이블 | 엔티티 | DAO |
|--------|--------|-----|
| `backgrounds` | `BackgroundImage` | `BackgroundDao` |
| `conversion_records` | `ConversionRecord` | `ConversionRecordDao` |
| `upload_records` | `UploadRecord` | `UploadRecordDao` |
| `error_log_entries` | `ErrorLogEntry` | `ErrorLogDao` |
| `recording_records` | `RecordingRecord` | `RecordingDao` |
| `recording_schedules` | `RecordingSchedule` | `RecordingScheduleDao` |
| `trashed_items` | `TrashedItem` | `TrashedItemDao` |
| `imported_audio_records` | `ImportedAudioRecord` | `ImportedAudioDao` |

- DB 버전: **9** (v1→v2 `MIGRATION_1_2`, v2→v3 `MIGRATION_2_3`, v3→v4 `MIGRATION_3_4`, v4→v5 `MIGRATION_4_5`, v5→v6 `MIGRATION_5_6`, v6→v7 `MIGRATION_6_7`, v7→v8 `MIGRATION_7_8`, v8→v9 `MIGRATION_8_9`)
- `MIGRATION_2_3`: `conversion_records`에 `segmentBatchId`(TEXT), `segmentIndex`(INTEGER), `segmentTotal`(INTEGER) nullable ADD. 기존 row는 NULL.
- `MIGRATION_3_4`: `error_log_entries`(id/level/tag/message/stackTrace/createdAt) `CREATE TABLE IF NOT EXISTS` 신규. 기존 3개 테이블 스키마·데이터 무변경.
- `MIGRATION_4_5`: `recording_records`(id/filePath/format/durationMs/sizeBytes/createdAt) `CREATE TABLE IF NOT EXISTS` 신규. 기존 4개 테이블 스키마·데이터 무변경.
- `MIGRATION_5_6`: `recording_schedules`(id/startMinuteOfDay/endMinuteOfDay/repeatMode/daysOfWeekMask/enabled/createdAt) `CREATE TABLE IF NOT EXISTS` 신규. 기존 5개 테이블 스키마·데이터 무변경. `daysOfWeekMask` bit0=월..bit6=일(Monday-start).
- `MIGRATION_6_7`: `trashed_items` 신규, `MIGRATION_7_8`: `imported_audio_records` 신규.
- `MIGRATION_8_9`: `recording_records.backupId`는 NOT NULL DEFAULT 0으로 ADD 후 기존 row의 `id`로 backfill한다. `trashed_items.recordingBackupId`는 nullable ADD라서 legacy/review-discard 항목에는 백업 전이를 만들지 않는다.
- `fallbackToDestructiveMigration()` 사용 금지
- `ui/*.kt`에서 `ConversionRecordDao` / `UploadRecordDao` / `ErrorLogDao` / `RecordingDao` / `RecordingScheduleDao` 직접 import 금지. Repository만 경유 (`RecordingScheduleRepository`).
- `RecordingRepository`는 Engine/Service를 import하지 않음(레이어 분리). `RecordingScheduleRepository`는 Context/Alarm/파일 I/O 없음(Dao thin wrapper).
- `ErrorLogEntry`는 `utils/AppLogger.installPersistSink`를 통해 `Convert2videoApplication.onCreate()`에서 설치된 sink가 E/W 레벨 로그만 저장한다. `AppLogger.e/w/d` 43개+ 기존 호출부는 무수정 (Sprint 5-4)

### 3. UiState

**WorkInfo 2-tier:** phase = `WorkInfoUiPhase`·`toWorkInfoUiPhase()` (`ui/shared/WorkInfoUiPhase.kt`); conversion payload = `ConvertViewModel.toConversionUiState` (+ aggregate/batch); YouTube payload = `YouTubeUploadViewModel.toYouTubeUploadUiState`.

`ConversionUiState` 타입 SSOT: `ui/shared/ConversionUiState.kt` (sealed 타입만; 매핑은 ConvertViewModel).

| 상태 | 의미 |
|------|------|
| `ConversionUiState.Idle` | 대기 |
| `InProgress` | 진행 중 (`ConversionUiState.InProgress`; `Progress` 아님) |
| `Success` | 완료(저장됨). **Semantic assertion:** 확인(`action_confirm`)은 `onDismissResult`만 호출하여 Convert에 잔류한다. 탭 랜딩은 sealed 상태가 아니라 「목록 보기」(`conversion_success_view_list`) 또는 TopBar 칩(`drawer_converted_videos`)의 `onNavigateToConvertedVideos` 사이드이펙트이며, 결과는 `AppDestination.Home` + `HomeTab.ConvertedVideos`다 |
| `Failed` / `Cancelled` | 실패·취소 (코드 기존 sealed 상태) |
| `RecordUiState.Idle` | RECORD_AUDIO 허용 + Controller Idle |
| `RecordUiState.PermissionDenied` | 마이크 권한 없음 (`canRequestAgain`) |
| `RecordUiState.Recording` / `Paused` | 캡처 중·일시정지 (`elapsedMs`/`amplitude` pass-through) |
| `RecordUiState.Saving` | `RecordingState.Stopping` 매핑 (인덱싱 중) |
| `RecordUiState.Review` | Keep 전 검토 (`outputFile`, `elapsedMs`). seam/`onRecordingSaved` 없음 |
| `RecordUiState.Saved` | Keep insert 후 저장 완료 sticky (`outputFile`, `elapsedMs`) |
| `RecordUiState.Error` | `RecordingState.Failed` → 리소스 Fallback 메시지 |
| `LanguagePromptUi.Loading` | `languagePromptShown` 미확정(null) — 게이트 대기 |
| `LanguagePromptUi.Picker` | 프롬프트 미표시 + 미커밋 — `LanguagePickerScreen` |
| `LanguagePromptUi.ApplyingProgress` | apply/`setLanguagePromptShown`/recreate in-flight |
| `LanguagePromptUi.App` | 프롬프트 완료 — 본 앱 UI |
| `HomeTab.Record` | Home 3탭 셸 — 녹음 |
| `HomeTab.Listen` | Home 3탭 셸 — 듣기 |
| `HomeTab.ConvertedVideos` | Home 3탭 셸 — 변환 결과 목록 (`AppDestination` 아님) |

**RecordingState → RecordUiState (Sprint 2-1):** 활성 세션(`Recording`/`Paused`/`Stopping`/`Stopped`)은 권한 철회여도 오버레이하지 않음. sticky 터미널(`Saved` / `Review` / `Failed` 비권한)도 권한 철회여도 유지(PermissionDenied 오버레이 금지). 그 외 권한없음 → `PermissionDenied`. 권한+Idle → Idle. Recording/Paused 동형. Stopping → Saving. Review → Review (Saved/Saving 접지 금지). Saved → Saved. `Failed(PERMISSION_DENIED)`+!hasPermission → `PermissionDenied`. `Failed(PERMISSION_DENIED)`+hasPermission → Idle(stale 폐기; Controller sticky는 `clearTerminalState`/다음 `start` 권장). 기타 Failed → Error(getString). Stopped(Engine) → Saving. `start()`는 진입 시 `refreshPermission()`으로 `permissionState`를 시스템 권한과 동기화한 뒤 스냅샷으로 gate한다(stale 스냅샷 방지). 권한 콜백·onResume 동기화는 `onPermissionResult`/`refreshPermission`. Review는 `keepRecording`/`discardRecording`만 해제(`clearTerminalState` 제외).

### 4. 금지 이식 용어

코드·주석·식별자에 이전 앱 도메인 용어를 **이 기능명으로** 쓰지 않음:

`gathering`, `booking`, `meetup`, `reservation`, `my_info`, `my_page`, `host_settlement`, `lounge`

(안드로이드 `androidx.activity` 등 프레임워크 식별자는 허용.)

### 5. 등록 규칙

새 Entity·Worker·공개 상수·사용자 노출 도메인 용어를 추가하면 이 파일에 행을 추가한다.
