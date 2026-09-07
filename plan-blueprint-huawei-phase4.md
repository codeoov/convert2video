# convert2video — Huawei AppGallery Phase 4 실행 블루프린트

> 대상: Huawei AppGallery 단일 스토어 v1
>
> 실행 방식: 각 S-세션을 Codex의 `5.6luna high` 단독 작업 단위로 실행한다. 한 세션은
> 하나의 기능 흐름 또는 최대 2~3개 파일만 다룬다. 세션 완료 조건은 해당 세션의 전용
> 검증 4종을 모두 통과하고, 다음 세션으로 넘어갈 때 컴파일 가능한 상태를 유지하는 것이다.
>
> 기존 [`plan-blueprint.md`](plan-blueprint.md)는 Audio/Converted 필터 작업 문서이므로 보존한다.

## 1. Architecture Impact Report

### 현재 상태 기준

- `BillingManager`, `EntitlementManager`, `EntitlementApiClient`는 이미 존재한다.
  따라서 이번 작업은 Phase 1 구현이 아니라 **기존 Google 전용 결제 구조를 스토어 중립
  구조로 바꾸는 작업**이다.
- `app/build.gradle.kts`에는 아직 flavor가 없고, `play-services-auth`와 Play Billing이
  공용 의존성으로 선언되어 있다.
- YouTube 자동 업로드는 `video/RecordingAutoConvertTrigger.kt`에서 직접 Worker 체인을
  구성하며, Drive 자동 업로드는 `drive/DriveAutoUploadTrigger.kt`에서 별도 enqueue한다.
- 백엔드는 `config.php`를 직접 읽고, `EntitlementController::verify`는 현재 Google
  검증과 `google_play` 저장을 직접 수행한다. `.env`/`EnvLoader`는 이 플랜의 기본 경로로
  사용하지 않는다.
- `purchase_events`에는 이미 `store` 컬럼이 있지만 unique key와 조회/업데이트 로직은
  `purchase_token` 단독 기준이다.

### 영향받는 계층

| 구분 | 영향 범위 |
|---|---|
| Android build/platform | `settings.gradle.kts`, version catalog, `app/build.gradle.kts`, flavor별 BuildConfig |
| Store capability | 신규 내부 store 식별자/기능 게이트 |
| Billing/data | `BillingManager`, `EntitlementManager`, `EntitlementApiClient`, Huawei IAP adapter |
| Backend API | `EntitlementController`, Huawei verifier, config example, purchase event migration |
| UI | Options 카드, YouTube 업로드 진입점, ConvertedVideos 업로드 액션 |
| Background execution | `RecordingAutoConvertTrigger`, `DriveAutoUploadTrigger`, YouTube/Drive Worker |
| Test | variant compile, store contract, gate, backend verification, Google regression |

이 프로젝트에는 별도의 `1_atoms`~`5_pages` 디렉터리가 없다. 따라서 Atomic Design 벽은
현재의 `ui/components`와 `ui/screens` 경계로 해석하고, 이번 작업은 UI atom/molecule를
추가하지 않는다. 데이터 흐름은 다음 순서로 유지한다.

```text
Huawei verifier / purchase_events
        -> EntitlementApiClient
        -> StoreBillingGateway / EntitlementManager
        -> Options/ViewModel state
        -> Screen gate
```

### 이번 계획에서 고정하는 설계 결정

1. Huawei v1은 AppGallery IAP만 지원한다. Alipay/WeChat 직접결제와 다른 중국 스토어는 제외한다.
2. Huawei variant에서는 YouTube/Drive를 UI에 노출하지 않고, 인증·enqueue·Worker 실행도 하지 않는다.
3. v1의 안전한 컴파일을 위해 `play-services-auth`는 초기 flavor 세션에서 공용으로 유지한다.
   Huawei APK에서 GMS 바이트코드까지 제거하는 작업은 마지막 별도 source-set 세션으로 분리한다.
4. API 계약은 다음 필드를 필수로 한다: `install_id`, `store`, `purchase_token`, `product_id`.
   `store`는 클라이언트 문자열을 그대로 신뢰하지 않고 서버의 허용 store/product 조합으로 검증한다.
5. backend 설정은 현재 구조와 맞춰 `config.php`/`config.example.php`를 기준으로 한다.
   `.env`/`EnvLoader` 전환은 이번 Phase에 추가하지 않는다.
6. `purchase_events`의 store 확장을 실제로 사용하기 위해 `(store, purchase_token)` 복합 unique와
   기존 DB용 migration을 함께 둔다.
7. Huawei 환불/취소는 초기 구매 검증만으로 끝내지 않는다. entitlement refresh 시 저장된
   purchase event를 store별로 재검증하는 경로를 포함한다.

### 프로젝트 규칙 주의점

- 새 의존성 버전은 `gradle/libs.versions.toml`에만 둔다.
- Android 네트워크는 `billing/`의 OkHttp 허용 범위 안에서만 사용한다. Retrofit/Hilt는 추가하지 않는다.
- 로그는 `AppLogger`만 사용하고 JWT, purchase token, install id, Huawei secret을 로그에 남기지 않는다.
- 사용자 문자열은 `strings.xml`/`stringResource`를 사용한다.
- 새 공개 도메인 용어·상수는 `CLAUDE.md` Terminology Glossary에 등록한다.
- 각 세션 종료 시 `.\cursor\hooks\run-stop-checks.ps1`와 세션 전용 compile/test를 실행한다.
- PowerShell 명령은 `&&` 없이 한 줄씩 실행한다.

## 2. 한눈에 보는 Phase / Session 로드맵

| Phase | Session | 작업 대상 | 주요 파일:줄번호 | 디펜던시 | 병렬 가능 |
|---|---|---|---|---|---|
| P0 재기준화 | S-00 | 현재 계약 고정·실행 체크리스트 | `BillingManager.kt:39`, `EntitlementController.php:13` | 없음 | - |
| P1 Variant 기반 | S-01 | Huawei Maven/version catalog | `settings.gradle.kts:1-16`, `libs.versions.toml:1-80`, `build.gradle.kts:1-8` | S-00 | - |
| P1 Variant 기반 | S-02 | google/huawei flavor와 capability flag | `app/build.gradle.kts:10-45` | S-01 | - |
| P2 기능 차단 | S-03 | Store capability SSOT | 신규 `store/StoreCapabilities.kt`, 테스트 | S-02 | - |
| P2 기능 차단 | S-04 | Options YouTube/Drive UI gate | `OptionsScreen.kt:198`, `OptionsViewModel.kt:99` | S-03 | - |
| P2 기능 차단 | S-05 | Home/Converted/ViewModel 생성 gate | `MainActivity.kt:422-429`, `HomeScreen.kt:112`, `ConvertedVideosScreen.kt:142` | S-03 | S-04 이후 직렬 권장 |
| P2 기능 차단 | S-06 | 자동 enqueue gate | `RecordingAutoConvertTrigger.kt:179-211`, `DriveAutoUploadTrigger.kt:56-87` | S-03 | S-04/S-05와 병렬 가능 |
| P2 기능 차단 | S-07 | Worker 실행 방어·기존 작업 정리 | `YouTubeUploadWorker.kt:81`, `DriveAutoUploadWorker.kt:40` | S-06 | - |
| P3 결제 추상화 | S-08 | store-neutral billing 계약 | `BillingManager.kt:29-39`, 신규 gateway 계약 | S-02 | - |
| P3 결제 추상화 | S-09 | Google adapter와 EntitlementManager 주입 | `BillingManager.kt:39`, `EntitlementManager.kt:30-67` | S-08 | - |
| P3 API 계약 | S-10 | Android verify에 store 추가 | `EntitlementApiClient.kt:34-49`, `EntitlementManager.kt:228-248` | S-09 | - |
| P4 Backend | S-11 | store/product 검증·config 계약 | `EntitlementController.php:13-39`, `config.example.php` | S-10 | - |
| P4 Backend | S-12 | purchase event 복합키 migration | `001_init_schema.sql:1-15`, 신규 `002_*.sql` | S-11 | - |
| P4 Backend | S-13 | Huawei OAuth/주문 verifier | 신규 verifier, `public/index.php:3-20` | S-11 | - |
| P4 Backend | S-14 | refresh 재검증·환불/취소 fail-closed | `EntitlementController.php:102-140` | S-12/S-13 | - |
| P5 Huawei client | S-15 | HMS IAP 의존성/AGConnect 설정 | `libs.versions.toml`, `app/build.gradle.kts`, `.gitignore` | S-02 | - |
| P5 Huawei client | S-16 | Huawei billing adapter | 신규 `HuaweiBillingManager.kt` | S-08/S-15 | - |
| P5 Huawei client | S-17 | flavor factory와 purchase flow 연결 | 신규 flavor factory, `EntitlementManager.kt` | S-10/S-14/S-16 | - |
| P6 통합 검증 | S-18 | variant/backend/client contract tests | billing/backend/test files | S-17 | - |
| P6 통합 검증 | S-19 | Google 회귀·Huawei 수동 골든패스 | 전체 산출물 | S-18 | - |
| P7 선택 기술부채 | S-20 | GMS를 `src/google`로 완전 분리 | `youtube/`, `drive/` auth boundary | P6 | - |

S-20은 Huawei v1의 기능 정합성에는 필요하지 않지만, Huawei APK에서 GMS 코드까지 제거해야
할 때 실행하는 별도 Phase다. S-02~S-19 동안은 GMS dependency를 공용으로 두어 Huawei variant
컴파일을 깨뜨리지 않는다.

## 3. Session별 상세 실행 가이드

### P0 / S-00 — 현재 계약 고정

**목적**: 코드 수정 없이 실제 구현 상태와 세션별 종료 조건을 고정한다.

**참조 앵커**

- `app/src/main/java/com/example/convert2video/billing/BillingManager.kt:39`
- `app/src/main/java/com/example/convert2video/billing/EntitlementManager.kt:30-67`
- `app/src/main/java/com/example/convert2video/billing/EntitlementApiClient.kt:34-49`
- `backend/src/Controllers/EntitlementController.php:13-93`
- `backend/db/001_init_schema.sql:1-15`

**결과 계약**

- Google 결제는 현재 동작을 보존한다.
- Huawei 요청 body에는 `install_id`, `store`, `purchase_token`, `product_id`가 들어간다.
- Huawei에서는 YouTube/Drive 인증 함수, enqueue, Worker 실행이 모두 발생하지 않는다.
- `config.php` 기반 backend 구조를 유지한다.

**검증 4종**

1. 컴파일: 실행하지 않음. 대신 `rg -n "class BillingManager|class EntitlementManager|function verify" app backend`로 현재 계약을 기록한다.
2. 구조: `rg --files app/src/main/java backend | rg "billing|youtube|drive|Entitlement|Purchase"` 결과를 세션 로그에 보존한다.
3. 규칙: `.\cursor\hooks\run-stop-checks.ps1` 실행 전후 차이를 기록한다.
4. 런타임: Google 구매/refresh가 이번 Phase 이전과 동일한 경로임을 확인한다.

### P1 / S-01 — Huawei Maven과 version catalog 기반

**수정 파일(최대 3개)**

- `settings.gradle.kts:1-16` — Huawei Maven repository를 중앙 repository에 추가한다.
- `gradle/libs.versions.toml:1-80` — Huawei plugin/IAP 버전과 alias를 추가한다. 착수 시 Huawei 공식 문서로 버전을 재확인한다.
- `build.gradle.kts:1-8` — AGConnect plugin alias를 `apply false`로 등록한다.

**주의**: 아직 `app/build.gradle.kts`에 Huawei dependency를 넣지 않는다. 이 세션은 catalog/repository만 담당한다.

**사전 확인**

```powershell
rg -n "developer\.huawei\.com/repo|huawei|agconnect" settings.gradle.kts build.gradle.kts gradle/libs.versions.toml
rg --files | rg "agconnect-services|huawei.*billing|HuaweiBilling"
```

**검증 4종**

1. 컴파일/Gradle: `.\gradlew.bat :app:tasks --no-daemon` 또는 PowerShell에서 `.\gradlew.bat :app:tasks --no-daemon`을 실행해 catalog 해석을 확인한다.
2. 구조: `rg -n "version\s*=|implementation\(.*:[0-9]" app build.gradle.kts gradle`로 버전 하드코딩을 확인한다.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`를 실행한다.
4. 런타임: 없음. dependency resolution이 실패하면 다음 세션으로 진행하지 않는다.

### P1 / S-02 — google/huawei flavor와 capability flag

**수정 파일**

- `app/build.gradle.kts:10-45` — `store` dimension, `google`, `huawei` flavor, `STORE_ID`, `GOOGLE_SERVICES_ENABLED`를 추가한다.

**핵심 결정**

- 이 세션에서는 `play-services-auth`를 `implementation`에 유지한다. 공용 `src/main`이 GMS API를 참조하므로, dependency를 `googleImplementation`으로 옮기는 것은 S-20 source-set 분리 후에만 허용한다.
- `buildConfig = true`는 이미 존재하므로 중복 추가하지 않는다.

**사전 확인**

```powershell
rg -n "productFlavors|flavorDimensions|buildConfigField|play-services-auth|buildConfig" app/build.gradle.kts
rg -n "namespace|applicationId|applicationIdSuffix" app/build.gradle.kts
```

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:assembleGoogleDebug --no-daemon`을 실행한다. 이어서 `.\gradlew.bat :app:assembleHuaweiDebug --no-daemon`.
2. 구조: `rg -n "play-services-auth|googleImplementation|huaweiImplementation" app/build.gradle.kts`로 의존성 범위를 확인한다.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`.
4. 런타임: generated `BuildConfig`에서 Google/Huawei의 `STORE_ID`와 capability 값이 다르고, Manifest에서 찾으려 하지 않는지 확인한다.

### P2 / S-03 — Store capability SSOT

**수정 파일**

- 신규 `app/src/main/java/com/example/convert2video/store/StoreCapabilities.kt`
- 신규 `app/src/test/java/com/example/convert2video/store/StoreCapabilitiesTest.kt`

**계약**

- `isGoogleServicesAvailable`, `supportsYouTube`, `supportsDrive`를 한곳에서 제공한다.
- Screen, ViewModel, Worker가 `BuildConfig`를 직접 반복 참조하지 않는다.
- 신규 domain 용어와 공개 여부를 `CLAUDE.md` glossary에 등록한다.

**사전 확인**

```powershell
rg --files app/src/main/java | rg "StoreCapabilities|Capabilities|BuildConfig"
rg -n "BuildConfig\.GOOGLE_SERVICES_ENABLED|BuildConfig\.STORE_ID" app/src/main/java
```

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "BuildConfig\.GOOGLE_SERVICES_ENABLED|BuildConfig\.STORE_ID" app/src/main/java` 결과가 capability 파일과 build 설정에만 남는지 확인한다.
3. 규칙: `.\scripts\check-forbidden-imports.ps1`을 실행한다. 이어서 `.\scripts\check-terminology-forbidden.ps1`.
4. 런타임: Google에서는 두 capability가 true, Huawei에서는 두 capability가 false인지 순수 함수 테스트로 확인한다.

### P2 / S-04 — Options의 YouTube/Drive UI gate

**수정 파일(동일 UI/ViewModel 흐름 2개)**

- `app/src/main/java/com/example/convert2video/ui/screens/options/OptionsScreen.kt:198-217, 669-686`
- `app/src/main/java/com/example/convert2video/ui/screens/options/OptionsViewModel.kt:99-173, 650-705`

**작업**

- Huawei에서 YouTube/Drive 카드, switch, auth launcher 준비가 모두 발생하지 않게 한다.
- DataStore 값을 읽는 것과 UI 표시를 혼동하지 않는다. Huawei에서 기존 설정값이 남아 있어도 enqueue가 되지 않아야 한다.
- Google flavor의 기존 loading/authorization state와 testTag를 보존한다.

**사전 확인**

```powershell
rg -n "OptionsYoutubeAccountContent|OptionsDriveAccountContent|youtubeAutoUploadEnabled|driveAutoUploadEnabled" app/src/main/java/com/example/convert2video/ui/screens/options
rg -n "viewModel\(|rememberLauncherForActivityResult|GoogleDriveAuthManager|YouTubeAuthManager" app/src/main/java/com/example/convert2video/ui/screens/options
```

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "GoogleDriveAuthManager|YouTubeAuthManager" app/src/main/java/com/example/convert2video/ui/screens/options`로 Huawei 조건 밖 호출이 남지 않는지 확인한다.
3. 규칙: `.\scripts\check-logging-forbidden.ps1`을 실행한다. 이어서 `.\scripts\check-terminology-forbidden.ps1`.
4. 런타임: Huawei Options에 두 카드가 없고 auth launcher가 실행되지 않음, Google Options는 기존과 동일함을 확인한다.

### P2 / S-05 — Home/Converted의 ViewModel 및 액션 gate

**수정 파일(호출부 3개)**

- `app/src/main/java/com/example/convert2video/MainActivity.kt:422-429, 969`
- `app/src/main/java/com/example/convert2video/ui/screens/home/HomeScreen.kt:112`
- `app/src/main/java/com/example/convert2video/ui/screens/converted_videos/ConvertedVideosScreen.kt:142, 1052, 1763`

**작업**

- Huawei에서 `YouTubeUploadViewModel`이 기본 `viewModel()`으로 생성되지 않게 한다.
- 업로드 버튼, chip, callback은 화면에 표시되지 않으며, callback이 호출되어도 no-op이 되도록 방어한다.
- `ConvertedVideos`의 변환/재생/삭제 기능은 유지하고 업로드만 제거한다.

**사전 확인**

```powershell
rg -n "YouTubeUploadViewModel|youTubeViewModel|onUpload|upload" app/src/main/java/com/example/convert2video/MainActivity.kt app/src/main/java/com/example/convert2video/ui/screens/home app/src/main/java/com/example/convert2video/ui/screens/converted_videos
```

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "YouTubeUploadViewModel|YouTubeUploadWorker" app/src/main/java/com/example/convert2video/ui`로 Huawei 조건 없는 생성/참조를 확인한다.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`.
4. 런타임: Huawei Home/Converted에 업로드 UI가 없고, Google에서는 기존 업로드 흐름이 유지된다.

### P2 / S-06 — 자동 enqueue gate

**수정 파일(자동화 흐름 2개)**

- `app/src/main/java/com/example/convert2video/video/RecordingAutoConvertTrigger.kt:44-47, 117-219`
- `app/src/main/java/com/example/convert2video/drive/DriveAutoUploadTrigger.kt:33-87`

**작업**

- YouTube auto-convert chain과 Drive auto-upload enqueue의 가장 앞에서 capability를 확인한다.
- Huawei에서는 설정값이나 authorization 상태를 읽기 전에 return한다.
- Google의 기존 `isYoutubeAuthorized`, `driveAutoUploadEnabled`, wifi constraint 동작은 유지한다.

**사전 확인**

```powershell
rg -n "beginUniqueWork|DriveAutoUploadWorker|YouTubeUploadWorker|isYoutubeAuthorized|driveAutoUploadEnabled" app/src/main/java/com/example/convert2video/video/RecordingAutoConvertTrigger.kt app/src/main/java/com/example/convert2video/drive/DriveAutoUploadTrigger.kt
```

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "BuildConfig|StoreCapabilities" app/src/main/java/com/example/convert2video/video/RecordingAutoConvertTrigger.kt app/src/main/java/com/example/convert2video/drive/DriveAutoUploadTrigger.kt`.
3. 규칙: `.\scripts\check-forbidden-imports.ps1`을 실행한다. 이어서 `.\scripts\check-logging-forbidden.ps1`.
4. 런타임: Huawei 설정이 true이고 Google auth preference가 남아 있어도 두 enqueue가 0회인지 확인한다.

### P2 / S-07 — Worker 실행 방어와 기존 작업 정리

**수정 파일(Worker 흐름 2개)**

- `app/src/main/java/com/example/convert2video/youtube/YouTubeUploadWorker.kt:81-110`
- `app/src/main/java/com/example/convert2video/drive/DriveAutoUploadWorker.kt:40-80`

**작업**

- Worker가 외부에서 직접 실행되거나 flavor 전환 전에 큐된 경우에도 Huawei에서는 네트워크/auth를 시도하지 않고 성공적 skip 또는 명시적 no-op으로 끝낸다.
- 알림/실패 toast가 Huawei에 노출되지 않게 한다.
- 기존 WorkManager 작업을 앱 시작 시 무조건 삭제하지 말고, Huawei variant에서만 feature tag/name 기준으로 정리한다. 사용자 데이터나 변환 작업은 건드리지 않는다.

**사전 확인**

```powershell
rg -n "class YouTubeUploadWorker|class DriveAutoUploadWorker|WorkManager|setContentTitle|AuthManager|ApiClient" app/src/main/java/com/example/convert2video/youtube/YouTubeUploadWorker.kt app/src/main/java/com/example/convert2video/drive/DriveAutoUploadWorker.kt
```

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "GoogleDriveAuthManager|YouTubeAuthManager|GoogleDriveApiClient|YouTubeApiClient" app/src/main/java/com/example/convert2video/youtube/YouTubeUploadWorker.kt app/src/main/java/com/example/convert2video/drive/DriveAutoUploadWorker.kt`.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`.
4. 런타임: 기존 queued upload가 있는 상태에서 Huawei APK를 실행해 auth/network/notification이 발생하지 않는지 확인한다.

### P3 / S-08 — Store-neutral billing 계약

**수정 파일(계약 2~3개)**

- `app/src/main/java/com/example/convert2video/billing/BillingManager.kt:29-39, 190-218` — 기존 Google event를 store-neutral event로 분리/adapter 가능하게 만든다.
- 신규 `app/src/main/java/com/example/convert2video/billing/StoreBillingGateway.kt`
- 신규 `app/src/test/java/com/example/convert2video/billing/StoreBillingGatewayTest.kt`

**계약**

- `connect`, `queryProduct`, `launchPurchase`, `purchaseEvents`, `acknowledge`의 공통 의미를 정의한다.
- `BillingPurchaseEvent.Purchased`에는 `store`를 중복 저장하지 말고 gateway가 제공하는 `StoreId`와 함께 상위 계층에서 전달할 수 있게 한다.
- Huawei SDK 타입이 `billing` 공용 패키지로 새지 않게 한다.

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "com\.huawei|com\.android\.billingclient" app/src/main/java/com/example/convert2video/billing/StoreBillingGateway.kt` 결과가 비어 있어야 한다.
3. 규칙: `.\scripts\check-forbidden-imports.ps1`을 실행한다. 이어서 `.\scripts\check-terminology-forbidden.ps1`.
4. 런타임: fake gateway로 Purchased/Cancelled/Failed가 EntitlementManager가 소비할 수 있는 동일 이벤트 계약인지 확인한다.

### P3 / S-09 — Google adapter와 EntitlementManager 주입

**수정 파일(기존 결제 흐름 2개)**

- `app/src/main/java/com/example/convert2video/billing/BillingManager.kt:39-218`
- `app/src/main/java/com/example/convert2video/billing/EntitlementManager.kt:30-67, 143-166`

**작업**

- 현재 concrete `BillingManager`를 `StoreBillingGateway` 구현체로 감싼다.
- `EntitlementManager`가 concrete Google BillingManager를 직접 생성하지 않게 한다.
- Google flavor의 기본 factory는 기존 동작을 그대로 반환한다.
- lifecycle/close와 `initialize()`의 restore purchase 동작을 보존한다.

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`.
2. 구조: `rg -n "BillingManager\(application\)|private val billing" app/src/main/java/com/example/convert2video/billing/EntitlementManager.kt`로 직접 생성이 factory/injection 밖에 남지 않는지 확인한다.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`.
4. 런타임: Google 구매, restore, acknowledge, refresh가 기존과 동일하게 동작한다.

### P3 / S-10 — Android entitlement API에 store 추가

**수정 파일(클라이언트 계약 2~3개)**

- `app/src/main/java/com/example/convert2video/billing/EntitlementApiClient.kt:34-49`
- `app/src/main/java/com/example/convert2video/billing/EntitlementManager.kt:228-248`
- 신규/갱신 `app/src/test/java/com/example/convert2video/billing/EntitlementApiClientTest.kt`

**API body**

```json
{
  "install_id": "<client install id>",
  "store": "google_play|huawei",
  "purchase_token": "<opaque token>",
  "product_id": "pro_lifetime_unlock"
}
```

**작업**

- token/JWT/install id는 로그에 남기지 않는다.
- `store`는 gateway가 제공하며 UI나 임의 문자열에서 받지 않는다.
- 서버가 반환하는 JWT의 store claim을 추가할 경우 기존 JWT 검증이 알 수 없는 claim 때문에 실패하지 않는지 확인한다.

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileDebugKotlin --no-daemon`을 실행한다. 이어서 `.\gradlew.bat :app:testDebugUnitTest --no-daemon`.
2. 구조: `rg -n "purchase_token|install_id|product_id|store|jwt" app/src/main/java/com/example/convert2video/billing/EntitlementApiClient.kt`에서 민감값 로그가 없는지 확인한다.
3. 규칙: `.\scripts\check-logging-forbidden.ps1`을 실행한다. 이어서 `.\scripts\check-forbidden-imports.ps1`.
4. 런타임: Google/Huawei 각각 올바른 store가 전달되고, store 누락/unknown 값은 서버 오류로 처리된다.

### P4 / S-11 — Backend store/product 검증과 설정 계약

**수정 파일(최대 3개)**

- `backend/src/Controllers/EntitlementController.php:13-39, 68-93`
- `backend/config.example.php:1-25`
- `.env.example:1-15`는 이번 세션에서 수정하지 않는다. 기존 `config.php` 경로가 SSOT이므로 문서에서 `.env` 전제를 제거한다.

**작업**

- `store`를 필수 입력으로 파싱하고 `google_play`/`huawei` 외 값은 422로 거절한다.
- store별 허용 product id를 서버 설정에서 가져오며, 클라이언트가 보낸 product id를 무조건 신뢰하지 않는다.
- Google path는 기존 Google Developer API를 유지하고, Huawei path는 아직 verifier 미완료 시 503/feature unavailable로 fail-closed한다.
- verify 샘플 payload에는 반드시 `install_id`를 포함한다.

**사전 확인**

```powershell
rg -n "install_id|purchase_token|product_id|google_play|store|play_product_id" backend/src/Controllers/EntitlementController.php backend/config.example.php
rg -n "EnvLoader::load|require .*config\.php" backend
```

**검증 4종**

1. 컴파일/문법: `php -l backend/src/Controllers/EntitlementController.php`을 실행한다. 이어서 `php -l backend/config.example.php`.
2. 구조: `rg -n "SELECT.*purchase_events|WHERE purchase_token|google_play" backend/src/Controllers/EntitlementController.php`로 store 없는 조회/하드코딩을 표시한다.
3. 규칙: `rg -n "purchaseToken|purchase_token|HUAWEI_APP_SECRET|private_key" backend/src --glob '*.php'` 결과가 로그 출력문에 쓰이지 않는지 확인한다.
4. 런타임: malformed/missing/unknown store, wrong product, missing install id가 모두 fail-closed인지 확인한다.

### P4 / S-12 — purchase_events 복합키 migration

**수정 파일**

- `backend/db/001_init_schema.sql:1-15` — 신규 설치용 unique 정의를 `(store, purchase_token)`으로 정리한다.
- 신규 `backend/db/002_purchase_events_store_token.sql` — 기존 DB에서 단독 unique를 제거하고 복합 unique를 추가한다.

**작업**

- 기존 row의 `store` 기본값 `google_play`를 보존한다.
- migration은 destructive table rebuild가 아니라 ALTER와 사전 중복 확인을 포함한다.
- Controller의 pre-check/row lock/upsert도 동일한 `(store, purchase_token)` identity를 사용하도록 S-11 또는 이 세션에서 연결한다.

**검증 4종**

1. 컴파일/문법: `php -l backend/src/Controllers/EntitlementController.php`을 실행한다. migration SQL은 staging MySQL에서 실행한다.
2. 구조: `rg -n "UNIQUE|purchase_token|store" backend/db/001_init_schema.sql backend/db/002_purchase_events_store_token.sql`.
3. 규칙: `rg -n "DROP TABLE|TRUNCATE|DROP DATABASE" backend/db/002_purchase_events_store_token.sql` 결과가 없어야 한다.
4. 런타임: 기존 Google row 보존, 같은 store+token 재검증은 update, 다른 install id는 409, 다른 store token은 별도 identity로 동작하는지 확인한다.

### P4 / S-13 — Huawei OAuth와 주문 verifier

**수정 파일(backend 구현 흐름 최대 3개)**

- 신규 `backend/src/HuaweiIapVerifier.php`
- `backend/src/Controllers/EntitlementController.php:37-93`
- `backend/public/index.php:3-20`

**작업**

- Huawei 공식 문서 기준 client credentials/access-token 발급과 purchase token 검증을 구현한다.
- App ID/secret과 endpoint는 `config.php`에서 읽고, 실패 시 secret/token/response body를 로그나 사용자 응답에 노출하지 않는다.
- verifier는 정상/환불/취소/네트워크 오류를 구분된 내부 결과로 반환한다.
- `dataSignature`를 서버 검증에 사용할지 Huawei API 요구사항 기준으로 확정한다. 단순히 클라이언트 서명을 신뢰하지 않는다.

**검증 4종**

1. 컴파일/문법: `php -l backend/src/HuaweiIapVerifier.php`을 실행한다. 이어서 `php -l backend/src/Controllers/EntitlementController.php`을 실행한다. 이어서 `php -l backend/public/index.php`.
2. 구조: `rg -n "HUAWEI|curl|purchaseToken|purchase_token|dataSignature|client_credentials" backend/src/HuaweiIapVerifier.php backend/src/Controllers/EntitlementController.php`.
3. 규칙: `rg -n "echo|var_dump|print_r|error_log" backend/src/HuaweiIapVerifier.php backend/src/Controllers/EntitlementController.php` 결과에 secret/token/body가 연결되지 않는지 확인한다.
4. 런타임: Huawei sandbox에서 정상 구매/환불/취소/timeout을 각각 검증하고, 외부 API 오류는 entitlement 발급으로 이어지지 않는지 확인한다.

### P4 / S-14 — refresh 재검증과 entitlement lifecycle

**수정 파일**

- `backend/src/Controllers/EntitlementController.php:102-140`
- `backend/src/EntitlementJwtIssuer.php:3-28` — store claim 또는 store lookup 계약을 반영한다.

**작업**

- refresh 시 `install_id + product_id`로 active purchase event를 찾고, 해당 row의 store에 맞춰 재검증한다.
- Huawei 환불/취소 응답이면 row를 revoked/refunded로 바꾸고 JWT를 발급하지 않는다.
- Google RTDN의 기존 동작을 깨지 않는다.
- JWT에 store claim을 넣는다면 Android `EntitlementJwt` 검증은 알 수 없는 claim을 허용하고, store mismatch는 서버/manager에서 fail-closed한다.

**검증 4종**

1. 컴파일/문법: `php -l backend/src/Controllers/EntitlementController.php`을 실행한다. 이어서 `php -l backend/src/EntitlementJwtIssuer.php`을 실행한다. 이어서 `.\gradlew.bat :app:testDebugUnitTest --no-daemon`.
2. 구조: `rg -n "status = 'active'|revoked|refunded|product_id|store" backend/src/Controllers/EntitlementController.php backend/src/EntitlementJwtIssuer.php`.
3. 규칙: `.\scripts\check-logging-forbidden.ps1`을 실행한다. 이어서 `rg -n "jwt|purchase_token|install_id" backend/src --glob '*.php'`로 민감 로그를 점검한다.
4. 런타임: active 구매 refresh 성공, Huawei refund/void refresh 거절, 만료 JWT refresh 실패 시나리오를 확인한다.

### P5 / S-15 — HMS IAP 의존성과 AGConnect 설정

**수정 파일(최대 3개)**

- `gradle/libs.versions.toml:1-80`
- `app/build.gradle.kts:45-84`
- `.gitignore:1-30` 또는 `app/.gitignore`

**작업**

- `huaweiImplementation`에만 HMS IAP를 추가한다.
- AGConnect plugin/json 배치·적용 방식은 Huawei 공식 문서를 확인한 후 결정한다. 조건부 plugin 적용이 실제 plugin 요구사항과 맞지 않으면 문서 스케치를 그대로 따르지 않는다.
- `agconnect-services.json`은 ignore하고, 비밀값 없는 example만 커밋한다.

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:assembleGoogleDebug --no-daemon`을 실행한다. 이어서 `.\gradlew.bat :app:assembleHuaweiDebug --no-daemon`.
2. 구조: `rg -n "huaweiImplementation|huawei-iap|agconnect-services" app/build.gradle.kts gradle/libs.versions.toml .gitignore app/.gitignore`.
3. 규칙: `rg -n "client_secret|app_secret|private_key" --glob '!*.example*' --glob '!*.md' .`로 실제 secret이 추적되지 않는지 확인한다.
4. 런타임: Huawei 기기에서 IAP environment unavailable을 사용자 친화적 실패로 처리하고 Google variant에 HMS 초기화가 생기지 않는지 확인한다.

### P5 / S-16 — Huawei billing adapter

**수정 파일(단일 컴포넌트 흐름 최대 3개)**

- 신규 `app/src/huawei/java/com/example/convert2video/billing/HuaweiBillingManager.kt`
- 신규 `app/src/huawei/java/com/example/convert2video/billing/HuaweiPurchaseResultParser.kt`
- 신규 `app/src/androidTest/java/com/example/convert2video/billing/HuaweiBillingManagerAndroidTest.kt` 또는 pure parser test

**작업**

- Huawei IAP SDK 타입은 Huawei flavor source set 안에만 둔다.
- environment check, product query, purchase PendingIntent, activity result parsing, purchase token extraction을 gateway 계약으로 변환한다.
- `priceType = 1` 같은 값은 SDK 문서 확인 후 상수화하고, 성공 결과에서 product id와 token이 모두 없으면 실패 처리한다.
- `dataSignature`/order id는 서버 계약에 필요한 범위만 전달한다. 민감값 로그는 금지한다.

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:compileHuaweiDebugKotlin --no-daemon`을 실행한다. 이어서 `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`.
2. 구조: `rg -n "com\.huawei|Iap\.getIapClient|PurchaseIntentReq|PurchaseResultInfo" app/src/huawei app/src/main`에서 Huawei import가 main에 나오지 않는지 확인한다.
3. 규칙: `.\scripts\check-forbidden-imports.ps1`을 실행한다. 이어서 `.\scripts\check-logging-forbidden.ps1`.
4. 런타임: sandbox 정상 구매, 사용자 취소, 결제 실패, malformed result, IAP environment unavailable을 확인한다.

### P5 / S-17 — flavor factory와 purchase flow 연결

**수정 파일(최대 3개)**

- 신규 `app/src/google/java/com/example/convert2video/billing/BillingManagerFactory.kt`
- 신규 `app/src/huawei/java/com/example/convert2video/billing/BillingManagerFactory.kt`
- `app/src/main/java/com/example/convert2video/billing/EntitlementManager.kt:30-67, 143-248`

**작업**

- 두 flavor factory가 같은 `StoreBillingGateway` 계약을 반환한다.
- `EntitlementManager`는 factory로부터 gateway를 받고, purchase verify 요청에 해당 store를 전달한다.
- Huawei 성공 결과가 동일한 JWT 저장/Pro state 전환 경로를 사용한다.
- `BillingManager` 인스턴스 수명과 `close()`를 application scope에 맞게 유지한다.

**검증 4종**

1. 컴파일: `.\gradlew.bat :app:assembleGoogleDebug --no-daemon`을 실행한다. 이어서 `.\gradlew.bat :app:assembleHuaweiDebug --no-daemon`.
2. 구조: `rg -n "BillingManagerFactory|StoreBillingGateway|HuaweiBillingManager|BillingManager\(" app/src/main app/src/google app/src/huawei`.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`.
4. 런타임: Google/Huawei 구매 성공이 각각 `store`를 다르게 전송하면서 동일한 EntitlementState.Pro로 전환되는지 확인한다.

### P6 / S-18 — 통합 contract tests

**수정 파일**

- 신규/갱신 `app/src/test/java/com/example/convert2video/billing/EntitlementStoreContractTest.kt`
- 신규/갱신 `backend/EntitlementControllerTest.php` 또는 현재 backend 테스트 러너에 맞는 위치
- 필요 시 `app/src/androidTest/.../StoreCapabilityUiTest.kt`

**필수 케이스**

- missing/unknown store 거절
- Google product + Huawei store mismatch 거절
- 동일 store/token 재검증 idempotency
- 다른 install id의 token conflict
- Huawei 정상/환불/취소/검증 API timeout
- Huawei에서 Options/업로드 UI/trigger/Worker가 모두 비활성

**검증 4종**

1. 컴파일/테스트: `.\gradlew.bat :app:testDebugUnitTest --no-daemon`을 실행한다. 이어서 `.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon`을 실행한다. backend 테스트 러너를 프로젝트 방식으로 실행한다.
2. 구조: `rg -n "success_|failure_|exception_" app/src/test app/src/androidTest backend --glob '*Test*'`로 테스트 명명 규칙을 확인한다.
3. 규칙: `powershell -ExecutionPolicy Bypass -File .\cursor\hooks\run-stop-checks.ps1`.
4. 런타임: 실제 Huawei sandbox token은 테스트 로그/fixture에 커밋하지 않는다.

### P6 / S-19 — Google 회귀와 Huawei 골든패스

**수정 파일**: 코드 수정 없음. 실패 시 해당 실패를 만든 이전 세션으로 되돌아가 수정한다.

**검증 순서**

1. `.\gradlew.bat :app:assembleGoogleDebug --no-daemon`
2. `.\gradlew.bat :app:testDebugUnitTest --no-daemon`
3. `.\gradlew.bat :app:assembleHuaweiDebug --no-daemon`
4. `.\cursor\hooks\run-stop-compile.ps1`
5. `.\cursor\hooks\run-stop-checks.ps1`

**런타임 골든패스**

- Google: Options YouTube/Drive 로그인, 자동 변환 후 YouTube/Drive enqueue, 구매/restore/refresh.
- Huawei: Options 카드 없음, Converted 업로드 액션 없음, 녹음 후 YouTube/Drive enqueue 없음, 로컬 변환/저장 정상, Huawei sandbox 구매 후 Pro 전환.
- Huawei에서 Google APK의 기존 DataStore/WorkManager 상태가 남아 있는 업데이트 시나리오를 별도로 확인한다.

### P7 / S-20 — 선택 기술부채: GMS를 Google source set으로 완전 분리

**전제**: S-19까지 기능적으로 안정화된 뒤 실행한다. Huawei AppGallery 심사 또는 APK 크기
요구가 없으면 v1 이후로 미룰 수 있다.

**분할 원칙**

- YouTube auth boundary와 Drive auth boundary를 별도 세션으로 나눈다.
- 공용 main에는 store-neutral auth interface만 두고, Google 구현은 `src/google`, Huawei는 no-op 구현을 둔다.
- 한 번에 `play-services-auth`를 제거하지 말고 YouTube → Google compile → Drive → Google compile 순서로 진행한다.

**완료 조건**

- `play-services-auth`가 `googleImplementation`에만 존재한다.
- `assembleHuaweiDebug` 성공과 Huawei APK dependency 확인에서 Google auth runtime이 빠진다.
- Huawei UI/trigger/Worker no-op 계약은 S-03~S-07과 동일하게 유지된다.

## 4. 세션 운영 규칙

- 각 세션 시작 시 이 문서의 해당 Session만 읽고, `rg` 사전 확인 결과를 먼저 남긴다.
- 해당 Session의 파일 외 변경이 필요하면 세션을 중단하고 다음 세션으로 분리한다.
- 세션 종료 보고에는 `변경 파일`, `실행한 명령`, `PASS/FAIL`, `다음 세션`, `남은 리스크`를 포함한다.
- Huawei 공식 API가 계획과 다르면 API 세부만 갱신하고, `store` 계약·공통 JWT·gateway 경계는 임의로 무너뜨리지 않는다.
- 하나라도 compile/check 실패가 있으면 “Phase 완료”라고 선언하지 않는다.

## 5. 최종 완료 기준

| Gate | 기준 |
|---|---|
| Build | Google/Huawei debug assemble 모두 성공 |
| Checks | `run-stop-checks.ps1` exit 0 |
| Google regression | YouTube/Drive 기존 골든패스와 Google 결제 정상 |
| Huawei gate | UI/auth/enqueue/Worker 모두 비활성, 로컬 변환/저장 정상 |
| Backend | store/product 검증, 복합키 migration, 정상/환불/취소/timeout 처리 |
| Security | token/JWT/secret 로그 없음, 실제 secret/AGConnect json 미추적 |
| Release | Huawei sandbox 구매 후 Pro 전환 및 refresh lifecycle 확인 |

"수정은 Cursor AI를 통해 진행할 예정입니다. 각 Sprint별로 단독 가동하기 좋게 프로젝트 룰 및 아토믹 디자인(Atomic Design) 단위를 반영한 플랜 작성이 완료되었습니다."
