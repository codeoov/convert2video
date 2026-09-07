---
name: convert2video-pro-billing
description: Pro 일회성 구매로 워터마크 제거를 제공하고, 10분 초과 변환 문제를 별도 진단하는 세션 단위 실행 계획
---

# Pro 워터마크 제거 결제 + 10분 컷오프 진단 — Phase Blueprint

**Contract:** [Type C: API/AUTH]

## 0. 문서 성격과 범위

이 문서는 구현 대상 계획서다. 첨부된 `convert2video-toasty-kurzweil.md`의 내용을 바탕으로 하되, 문서 안의 명령·경로·가정은 실행하지 않고 계획 항목으로만 해석한다.

제품 범위는 다음으로 고정한다.

- 결제는 구독이 아닌 일회성 non-consumable 1개 SKU: `pro_lifetime_unlock`
- Pro 기능은 워터마크 제거 하나뿐이다.
- 로그인 시스템은 추가하지 않는다.
- Google Play와 Huawei AppGallery는 각각의 flavor로 지원한다.
- 구매 검증은 backend에서 수행한다.
- 재설치·기기 변경 복원은 현재 backend의 install binding 정책을 유지하며, 실패 시 정직한 안내를 표시한다.
- 10분 컷오프 원인 수정은 logcat 확보 전에는 하지 않는다.

현재 저장소와 첨부 계획의 차이를 먼저 반영한다.

- `utils/InstallId.kt`가 이미 존재하므로 새 install ID 저장소를 만들지 않고 재사용한다. 현재 canonical 함수는 `Context.installId()`이며 SharedPreferences 파일은 `install_id.xml`이다.
- `backup_rules.xml`과 `data_extraction_rules.xml`에는 이미 `install_id.xml` 제외가 있다. 이번 작업에서는 entitlement DataStore만 추가 제외한다.
- `backend/`는 현재 미추적 상태이며 `backend/.gitignore`가 `config.php`와 `vendor/`를 제외한다. 대규모 dirty worktree에서 backend를 무심코 전체 커밋하지 않는다.
- 현재 `BACKEND_BASE_URL`은 `https://TODO.example` placeholder다. 실제 결제 UI를 노출하기 전 staging/production URL 주입과 backend preflight가 완료되어야 한다.
- 현재 `ConversionWorker`에는 watermark gate가 없고, `VideoConverter.convert()`의 `applyWatermark` 기본값은 `true`다. 기존 기본값은 유지하고 Worker 호출부에서만 Pro 상태를 주입한다.

---

## 1. 아키텍처 영향 분석

### 1.1 레이어 영향

이 프로젝트에는 `1_atoms`~`5_pages` 물리 폴더가 없으므로 다음 개념으로 매핑한다.

- Data/API: `backend/` PHP endpoint 및 Android `billing/EntitlementApiClient`
- DTO/계약: `billing/BillingGateway.kt`, `EntitlementApiResult`, 구매 상태 타입
- Repository: `billing/EntitlementRepository.kt`, `EntitlementStore.kt`
- ViewModel: `ui/screens/options/OptionsViewModel.kt`
- UI organism-equivalent: `OptionsProUpgradeContent.kt`
- UI page/screen: `OptionsScreen.kt`
- 백그라운드 domain: `video/ConversionWorker.kt`

한 Phase에서 backend → Repository → ViewModel → UI를 통째로 연결하지 않는다. 아래 순서로 하위 계층이 검증된 뒤 상위 계층으로 진행한다.

`store/build plumbing → backend contract → Android contract/API/store → Repository lifecycle → flavor gateway → Worker gate → ViewModel → UI → release verification`

### 1.2 반드시 지켜야 할 상태 규칙

1. Google `PENDING` 구매에는 Pro를 부여하지 않는다.
2. Google `PURCHASED` 구매는 backend 검증 후 acknowledgement 상태를 확인한다. 필요한 acknowledge가 성공한 뒤에만 entitlement를 부여하고 JWT를 발급한다. 이미 acknowledge된 구매는 다시 acknowledge하지 않는다.
3. 결제 완료 후 backend가 일시 실패해도 구매 token을 잃지 않는다. 미처리 token은 재시작·foreground·복원 시 재검증한다.
4. `pro_entitlement_active` boolean 하나를 권한의 단일 근거로 사용하지 않는다. 저장된 JWT의 만료 시각과 backend refresh 결과를 함께 사용한다.
5. 만료된 JWT는 offline Pro 근거로 사용하지 않는다. 네트워크 오류와 명시적 revoked/403을 구분한다.
6. backend `/entitlement/refresh`는 JWT의 `sub`와 현재 install ID를 비교해야 한다. 현재 계획의 `refresh(jwt)`만으로는 device binding 정책이 구현되지 않으므로 Android API도 `refresh(jwt, installId)`로 정의한다.
7. `InstallId.kt` 외에 UUID 생성 로직을 추가하지 않는다.
8. 결제 gateway는 process-wide single instance로 소유한다. 화면 회전이나 ViewModel 재생성으로 BillingClient가 여러 개 생기지 않게 한다.
9. `ConversionWorker`는 변환 시작 시 Pro snapshot을 한 번 읽는다. 변환 중 구매가 완료되어도 이미 시작한 작업의 watermark 정책은 바꾸지 않는다.

### 1.3 변경되는 규칙과 금지 사항

- `CLAUDE.md`의 backend 범위와 OkHttp 허용 패키지를 billing까지 갱신한다. 이 문서 갱신은 Android billing API 코드를 시작하기 전에 완료한다.
- `video/`는 `ui.screens.*`를 import하지 않는다. `billing/EntitlementRepository`만 참조한다.
- UI는 BillingClient, EntitlementApiClient, DataStore를 직접 호출하지 않는다.
- 구매 token, JWT, Authorization header, 전체 install ID를 로그에 남기지 않는다.
- 사용자 문자열에 HTTP 상태 코드·예외 메시지·stack trace를 노출하지 않는다.
- `PRO_PRODUCT_ID`, store ID, DataStore key, API route는 각각 한 곳의 SSOT로만 정의한다.

---

## 2. 전체 Phase 로드맵

각 Phase는 한 세션에서 독립적으로 실행할 수 있는 단위다. 한 Phase 완료 후 해당 Gate가 PASS가 아니면 다음 Phase로 넘어가지 않는다.

| Phase | 작업 대상(레이어) | 변경 상한 | 디펜던시 | 주요 참조 파일:줄번호 | 병렬 가능 |
|---|---|---:|---|---|---|
| P0 | 실기기 진단/요구사항 고정 | 코드 0 | 없음 | `ConversionWorker.kt:60-101`, `AndroidManifest.xml` FGS 선언 | 다른 Phase와 병렬 금지 |
| P1 | 프로젝트 규칙·Gradle·dependency plumbing | 3 files | P0의 범위 고정 | `CLAUDE.md:65-70,101-105,314-328,378-382,402-404`, `libs.versions.toml:1-70`, `app/build.gradle.kts:5-50,126` | - |
| P1b | store capability SSOT | 2 files | P1 | `StoreCapabilities.kt:6-39`, `StoreCapabilitiesTest.kt` | - |
| P2 | backend entitlement contract/security | 3 files | P1b | `backend/public/index.php:20-24`, `EntitlementController.php:11-357`, `EntitlementJwtIssuer.php:1-164` | - |
| P3 | backend 테스트·운영 preflight | 2-3 files | P2 | `config.php:53-126`, `001_init_schema.sql:1-45`, 기존 `GoogleCertsVerifierTest.php` | - |
| P4 | Android 공통 계약·API·secure store | 3 files | P1b, P2 | `InstallId.kt:16-24`, `TelemetryApiClient.kt:19-106`, `PairingTokenStore.kt:98-148` | - |
| P5 | EntitlementRepository와 lifecycle | 2 files | P4 | `Convert2videoApplication.kt:23-94`, `SettingsRepository.kt` hot-cache 패턴 | - |
| P6 | Google Billing flavor gateway | 2-3 files | P4, P5 | `app/src/google/.../YouTubeAuthManager.kt`, `app/build.gradle.kts:126` | - |
| P7 | Huawei IAP flavor gateway | 3 files | P4, P5 | `app/src/huawei/.../*GatewayHuawei.kt`, `settings.gradle.kts:1-22` | P6 이후 |
| P8 | Repository/API/gateway 상태 테스트 | 2-3 files | P5-P7 | `app/src/test`, `app/src/testGoogle`, `app/src/testHuawei` | - |
| P9 | Worker watermark gate | 2 files | P5, P8 | `ConversionWorker.kt:60-101`, `VideoConverter.kt:340-347,425-432` | - |
| P10 | Options ViewModel state/commands | 2 files | P5, P8 | `OptionsViewModel.kt:93-116,908-965,1375-1390` | - |
| P11 | Options l10n resources | 2 files | P10 계약 | `strings.xml`, `values-ko/strings.xml` | P10 완료 후 |
| P12 | Pro UI organism-equivalent | 2 files | P10, P11 | `OptionsScreen.kt:1076-1495`의 무상태 content 패턴 | - |
| P13 | OptionsScreen wiring/ActivityResult | 2 files | P10-P12 | `OptionsScreen.kt:198-435,706-748` | - |
| P14 | 백업 규칙·문서·최종 release gate | 3 files + 문서 검증 | P1-P13 | `backup_rules.xml:8-13`, `data_extraction_rules.xml:7-23`, `CLAUDE.md:230-506` | - |

---

## 3. Phase별 상세 실행 가이드

### P0 — 10분 컷오프 재현과 범위 고정

대상은 실기기 데이터 수집뿐이며 코드 수정은 없다.

사전 확인:

- `adb devices`에서 Galaxy S22 Ultra가 연결되어 있는지 확인한다.
- `ConversionWorker`의 foreground 진입과 Media3 호출 위치를 확인한다.
- 화면 켜짐/꺼짐, foreground/background, 배터리 최적화 상태를 재현 조건으로 기록한다.

캡처는 저장소를 오염시키지 않도록 `%TEMP%` 아래에 둔다.

```powershell
$reproLog = Join-Path $env:TEMP 'convert2video-conversion-10min-repro.log'
adb logcat -c
adb logcat -b all -v threadtime > $reproLog
# 실패 지점을 지난 뒤 실행 중인 adb logcat에 Ctrl+C
rg -n -i 'ExportException|OutOfMemoryError|lowmemorykiller|am_kill|Killing|OOM|ANR|foreground service|FGS|Transformer|MediaCodec|died|has died|SEM_|PowerGenie|ProcessManager' $reproLog
```

전용 Gate:

- 컴파일: 실행하지 않음(코드 변경 없음).
- 구조 체크: `git status --short`로 로그가 저장소 안에 생기지 않았는지 확인한다.
- 문자열/금지 용어: 실행하지 않음.
- 런타임: 실패 시각, 화면 상태, 배터리 최적화 여부, 마지막 UI 상태, 알림 생존 여부를 기록한다.

종료 조건:

- OS/OEM kill, Media3/MediaCodec 오류, 앱 자체 예외 중 하나로 후속 가설을 좁히거나 “로그상 미확정”으로 명시한다.
- 로그 없이 `ConversionWorker`나 `VideoConverter`를 수정하지 않는다.

### P1 — 규칙·Gradle·store capability 선행 정리

대상 파일:

- `CLAUDE.md:65-70,101-105,314-328,378-382,402-404` — billing backend와 OkHttp 허용 범위, `billing/` 폴더, 양쪽 flavor 지원, glossary 등록 기준을 먼저 반영한다.
- `gradle/libs.versions.toml:1-70` — `billing-ktx`, `huawei-iap` 버전을 실제 사용 시점에 공식 release 기준으로 고정한다.
- `app/build.gradle.kts:5-50,126` — `googleImplementation`/`huaweiImplementation` 스코프, Huawei AGConnect 적용 여부, backend URL 주입 경로를 명시한다.

주의:

- `app/build.gradle.kts:49`의 `https://TODO.example`는 결제 release에서 허용하지 않는다. local/staging/production 값을 BuildConfig 또는 안전한 Gradle property로 분리한다.
- `applicationId`와 backend `ANDROID_PACKAGE_NAME`이 일치하는지 확인한다.
- PBL 버전은 임의의 “8.x/9.x” 범위가 아니라 실제 선택한 한 버전으로 기록한다.
- AGConnect plugin은 Huawei 빌드에서 실제로 필요한지 확인하고, 필요한 경우 `agconnect-services.json`의 위치·ignore·flavor 적용을 구체화한다.

검증:

```powershell
.\gradlew.bat :app:assembleGoogleDebug --no-daemon
.\gradlew.bat :app:assembleHuaweiDebug --no-daemon
rg -n 'retrofit2|dagger\.hilt|gson|okhttp3' app\src\main\java
.\scripts\check-forbidden-imports.ps1
.\scripts\check-terminology-forbidden.ps1
```

런타임/운영 Gate:

- placeholder backend URL로는 결제 버튼을 노출하지 않는 정책을 정한다.

### P1b — StoreCapabilities capability SSOT

대상 파일:

- `app/src/main/java/com/example/convert2video/store/StoreCapabilities.kt:6-39` — `supportsBilling`을 추가한다. Google/Huawei는 true, unknown store는 false다.
- `app/src/test/java/com/example/convert2video/store/StoreCapabilitiesTest.kt:1` — Google/Huawei/unknown capability 조합을 고정한다.

검증:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*StoreCapabilitiesTest' --no-daemon
rg -n 'supportsBilling|GOOGLE_PLAY_STORE_ID|HUAWEI_STORE_ID' app\src\main\java app\src\test
.\scripts\check-forbidden-imports.ps1
```

런타임 Gate:

- unknown store는 결제 UI를 노출하지 않는다.
- Screen/VM이 `BuildConfig.STORE_ID`를 직접 읽지 않고 capability SSOT만 사용한다.

### P2 — backend 검증·refresh·Google acknowledge 계약 [Type C: API/AUTH]

대상 파일은 정확히 3개로 제한한다.

- `backend/src/Controllers/EntitlementController.php:11-115,118-357,361-664` — verify/refresh 입력 검증·상태 머신을 고친다.
- `backend/src/EntitlementJwtIssuer.php:1-173` — claim·expiry·product 검증을 유지하고 refresh binding helper를 추가한다.
- `backend/src/PlayDeveloperApiClient.php:1-48` — Google non-consumable acknowledge API를 추가한다. acknowledge는 backend 단일 경로로 고정한다.

#### [Improvement Architecture Report]

이번 Phase는 Android `data/`·`ui/`·`video/` 계층을 수정하지 않고, 제한된 서버 예외인 `backend/`의 API/AUTH 경계만 다룬다. `data/`의 Room/entitlement 저장 모델, `ui/`의 Options 화면, `video/`의 Worker는 후속 Phase가 소비할 계약의 참조 대상일 뿐 P2 구현 대상이 아니다. `EntitlementController`가 요청 인증·provider 상태·DB 상태 전이를 조율하고, `EntitlementJwtIssuer`가 JWT claim/binding을 단일 소유하며, `PlayDeveloperApiClient`가 Google acknowledge outbound 호출을 단일 소유하도록 배치한다. Room schema·Worker 키·Android 권한·클라이언트 파일은 변경하지 않는다.

#### 구현 범위와 변경 파일 고정

P2에서 변경하는 PHP 파일은 정확히 다음 세 개뿐이다.

- `backend/src/Controllers/EntitlementController.php:11-115,118-357,361-664` — verify/refresh 입력 검증, 인증·binding, provider 상태 머신, DB 전이와 JWT 발급 순서를 고정한다.
- `backend/src/EntitlementJwtIssuer.php:1-173` — 기존 claim·expiry·product 검증을 유지하고 `isBoundToInstall` binding helper를 추가한다.
- `backend/src/PlayDeveloperApiClient.php:1-48` — Google non-consumable acknowledge POST를 추가하고 HTTP 결과를 반환한다.

`backend/public/index.php`의 기존 route 등록(`POST /entitlement/verify`, `POST /entitlement/refresh`)은 참조만 하며 수정하지 않는다. `backend` 테스트 파일, `backend/db` schema/migration, `config.php`/기타 backend 파일, Android `app/**`, l10n, DTO, Repository, ViewModel, UI, Worker 및 다른 모든 파일은 P2 구현 범위 밖이다. P2에는 schema 변경이 없으므로 migration/version bump도 없다.

#### 확정 구현 계약

- `POST /entitlement/verify` 요청 JSON은 `{install_id, store, product_id, purchase_token}`이다. `POST /entitlement/refresh`는 `Authorization: Bearer <JWT>`와 정확히 `{"install_id":"<canonical UUID>"}` JSON body만 받으며, refresh body에 다른 entitlement 입력을 요구하지 않는다.
- verify와 refresh 모두 malformed JSON은 HTTP 400으로 반환한다. `install_id` 누락, non-string, canonical UUID 형식 오류는 HTTP 422로 반환한다.
- refresh의 Authorization header 누락/형식 오류 및 malformed/invalid JWT는 HTTP 401이다. JWT payload가 유효하더라도 body `install_id`가 JWT `sub`와 다르면 HTTP 409이며, 응답은 정확히 `error='Install ID does not match token subject'`이다.
- `EntitlementJwtIssuer::isBoundToInstall(array $payload,string $installId): bool`를 추가한다. Controller는 refresh에서 이 helper를 사용해 binding을 판정하며, `sub`를 읽어 바로 JWT를 발급하는 우회 경로를 두지 않는다.
- Google `purchaseState`는 `0 = PURCHASED/구매 완료`, `1 = CANCELED/취소·revoked`, `2 = PENDING/보류 중`으로 해석한다. 허용되지 않은 값이나 필드 형식 오류는 malformed provider 결과로 취급한다.
- `PENDING`은 verify와 refresh에 동일하게 적용한다. 기존 DB active row가 있으면 그대로 유지하고, 새 active row를 insert하거나 active로 update하지 않는다. acknowledge 호출과 JWT 발급도 금지하며 HTTP 402로 반환한다.
- Google `PURCHASED`에 대해 `acknowledgementState === 0`일 때만 `PlayDeveloperApiClient`의 `POST https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/products/{productId}/tokens/{purchaseToken}:acknowledge`를 body `{}`로 호출한다. `acknowledgementState === 1`이면 호출을 skip하고 이미 acknowledge된 상태로 통과시킨다. acknowledge의 모든 2xx 응답은 성공이다.
- acknowledge 성공(또는 state 1 skip)이 확인되기 전에는 DB active write와 JWT 발급을 수행하지 않는다. acknowledge 실패는 HTTP 503이며 응답은 정확히 `error='Purchase acknowledgement service unavailable'`이다. 실패 시 기존 active row를 보존하고 새 JWT를 발급하지 않는다.
- provider 오류 정책은 verify와 refresh에 동일하다. HTTP 408/429, network error, timeout, 5xx, provider 응답 malformed는 HTTP 503이다. 그 밖의 비일시적 provider 4xx는 HTTP 402이다. acknowledge endpoint 자체의 비-2xx는 위 acknowledge 실패 규칙에 따라 HTTP 503으로 고정한다.
- revoked/refunded provider 결과는 해당 row의 상태를 revoked/refunded로 반영할 수 있으나, 같은 install에 다른 active entitlement가 남아 있으면 그 entitlement를 근거로 JWT 발급이 가능하다. revoked/refunded가 유일한 active entitlement라면 HTTP 403이며 JWT를 발급하지 않는다. 이 판정도 verify와 refresh에 동일하게 적용한다.
- 정상 active 응답은 기존 `Response::success()` 계약인 `{ok:true,data:{jwt,expires_at}}`를 따른다. 동일 `(store,purchase_token)` 재요청은 idempotent하되, 매 요청에서도 provider 상태와 acknowledge 순서를 다시 준수한다.

검증:

```powershell
php -l backend\src\Controllers\EntitlementController.php
php -l backend\src\EntitlementJwtIssuer.php
php -l backend\src\PlayDeveloperApiClient.php
rg -n 'refresh\(|install_id|isBoundToInstall|acknowledgementState|purchaseState|:acknowledge|Purchase acknowledgement service unavailable|Install ID does not match token subject' backend\src\Controllers backend\src\EntitlementJwtIssuer.php backend\src\PlayDeveloperApiClient.php
git diff --name-only -- plan-blueprint-pro-billing.md
```

구조/보안 체크:

- `purchase_token`, JWT, Authorization header, service-account credential 및 전체 install ID를 로그에 남기지 않는다.
- provider 검증과 Google acknowledge 성공 확인 전에는 DB active 처리·JWT 발급을 금지한다. pending에서는 기존 active row를 건드리지 않는다.
- `data/`·`ui/`·`video/`·Android app 및 테스트/schema 파일이 P2 diff에 들어오지 않는지 확인한다.

런타임 Gate:

- 정상 PURCHASED(state 0, ack state 0/1), pending(state 2), canceled/revoked(state 1), refunded, 기존 active row 보존, 다른 active entitlement 존재/부재, 다른 install ID, malformed JSON, 401/402/403/409/422/503 provider 결과를 verify와 refresh 양쪽에서 확인한다.



### P3 — backend 테스트와 운영 preflight

대상:

- `backend/EntitlementControllerTest.php` 신규 — provider/DB seam을 사용해 token binding, product mismatch, refresh, revoke, acknowledge retry를 테스트한다.
- `backend/config.example.php:6-22` — 실제 배포에 필요한 모든 환경 변수와 package/SKU 계약을 문서화한다.
- 필요할 때 `backend/db/001_init_schema.sql:1-45` — index/status/acknowledge 기록이 필요하다면 migration을 추가한다. schema 변경이 없으면 파일을 건드리지 않는다.

검증:

```powershell
php -l backend\EntitlementControllerTest.php
php backend\EntitlementControllerTest.php
Get-Content backend\.gitignore
git check-ignore -v backend\config.php
git status --short -- backend
```

필수 테스트 케이스:

- 동일 `(store,purchase_token)` 재요청은 중복 entitlement를 만들지 않는다.
- Google/Huawei 같은 SKU라도 store가 다르면 서로의 token을 인정하지 않는다.
- `refresh`는 현재 install ID가 JWT subject와 다르면 성공하지 않는다.
- provider 오류와 구매 거절을 503/402로 혼동하지 않는다.
- RTDN은 중복·malformed payload에서도 raw token/body를 저장하지 않는다.

운영 Gate:

- DB, Google service account, Huawei credential, JWT private key, Pub/Sub OIDC 검증 설정을 staging에서 preflight한다.
- backend endpoint가 HTTPS이고 Android release URL이 placeholder가 아닌지 확인한다.

### P4 — Android 공통 계약·API·secure store

대상 파일:

- `app/src/main/java/com/example/convert2video/billing/BillingGateway.kt:1` 신규
- `app/src/main/java/com/example/convert2video/billing/EntitlementApiClient.kt:1` 신규
- `app/src/main/java/com/example/convert2video/billing/EntitlementStore.kt:1` 신규

계약:

- `PRO_PRODUCT_ID`는 `BillingGateway.kt` 한 곳에서만 정의한다.
- purchase 상태는 `Purchased`, `Pending`, `UserCancelled`, `AlreadyOwned`, `Failed`를 구분한다. `Pending`을 `UserCancelled`나 `Purchased`로 합치지 않는다.
- gateway는 `queryProductDetails`, `launchPurchase`, `queryOwnedPurchases`, `resultFromActivityResult`, `close`를 제공한다. Google acknowledge는 gateway가 아니라 backend 단일 경로로 수행한다.
- `queryOwnedPurchases` 결과에는 product ID, purchase token, purchase state, acknowledged 여부를 보존한다. token만 반환하지 않는다.
- `EntitlementApiClient.refresh(jwt, installId)`로 정의한다.
- HTTP 응답은 `Success(jwt, expiresAtEpochSeconds)`와 `Failure(kind, httpCode)`로 매핑한다. 서버 원문 메시지를 UI에 직접 전달하지 않는다.
- `EntitlementStore`는 `entitlement.preferences_pb`에 저장하되 JWT는 PairingTokenStore와 동일한 Keystore AES-GCM 계열로 보호한다. 백업 제외는 P14에서 처리한다.
- `active`는 캐시된 표시 상태일 뿐 JWT/expiry/provider 결과를 대체하지 않는다.
- install ID는 `Context.installId()`만 사용한다. 별도 UUID/DataStore key를 만들지 않는다.

사전 확인:

```powershell
rg -n 'fun Context\.installId|PREFS_NAME|pairing_token\.preferences_pb|AES/GCM|OkHttpClient' app\src\main\java
rg --files app\src\main\java\com\example\convert2video\billing
```

검증:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*EntitlementApiClientTest' --no-daemon
.\scripts\check-forbidden-imports.ps1
.\scripts\check-logging-forbidden.ps1
```

런타임 Gate:

- malformed JSON, 401, 402, 403, 409, 503, timeout, cancellation이 각각 안전한 `Failure`로 끝난다.
- JWT/token/URL/Authorization header가 AppLogger에 기록되지 않는다.

### P5 — EntitlementRepository와 lifecycle

대상 파일:

- `app/src/main/java/com/example/convert2video/billing/EntitlementRepository.kt:1` 신규
- `app/src/main/java/com/example/convert2video/Convert2videoApplication.kt:23-94`

구현 계약:

- repository는 process-wide singleton/factory를 제공해 OptionsViewModel과 ConversionWorker가 같은 hot state를 본다.
- `isProHot: StateFlow<Boolean?>`는 null=초기 확인 중, true/false=검증된 local snapshot이다.
- `isProSnapshot()`은 IO에서 저장값을 읽고 expiry를 확인한다. 읽기 실패는 false다.
- 구매/복원은 mutex로 직렬화한다. 동시에 두 purchase verify가 발생하지 않는다.
- purchase 순서는 `store purchase → provider state check → backend verify+acknowledge → entitlement 저장`으로 고정한다. backend verify가 성공하기 전에는 Pro를 저장하지 않는다.
- backend 일시 오류는 미처리 token/JWT를 보존해 재시도한다. 403 revoked/invalid는 active를 즉시 false로 만든다. 만료 후 refresh 실패는 false로 fail-closed한다.
- 앱 시작/ProcessLifecycle foreground에서 만료 임박 JWT를 refresh한다. “구매 복원 버튼을 눌러야만 refresh”가 유일한 경로가 되지 않게 한다.
- refresh에는 현재 `installId()`를 전달한다.

검증:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*EntitlementRepositoryTest' --no-daemon
rg -n 'EntitlementRepository|isProHot|isProSnapshot|refresh|installId|pro_entitlement' app\src\main\java
.\scripts\check-forbidden-imports.ps1
```

런타임 Gate:

- cold start, rotation, process death, offline, expired JWT, revoked purchase, retry 중 cancellation을 확인한다.
- Application warm-up instance와 Worker/VM instance가 동일 singleton state를 관찰한다.

### P6 — Google Play Billing gateway

대상:

- `app/src/google/java/com/example/convert2video/billing/GoogleBillingGateway.kt:1` 신규
- `app/src/google/java/com/example/convert2video/billing/BillingGatewayFactory.kt:1` 신규
- 테스트가 필요하면 `app/src/testGoogle/.../GoogleBillingGatewayTest.kt`를 이 Phase의 세 번째 파일로 제한한다.

필수 동작:

- BillingClient는 process-wide 한 개만 연결한다.
- `enablePendingPurchases`를 현재 PBL API에 맞게 설정한다.
- `queryProductDetailsAsync`로 `PRO_PRODUCT_ID`를 조회하고 실제 `ProductDetails`/offer로 BillingFlow를 만든다.
- `PENDING`은 entitlement를 만들지 않고 UI에 대기 상태를 전달한다.
- `PURCHASED`만 backend verify로 보낸다.
- backend verify가 non-consumable acknowledge를 정확히 한 번 수행하고, 이미 acknowledged면 생략한다.
- `ITEM_ALREADY_OWNED`는 query로 실제 owned purchase를 다시 찾아 `AlreadyOwned`로 변환한다.
- `SERVICE_DISCONNECTED`는 재연결 후 재시도할 수 있다.
- 앱 foreground/resume 때 owned purchase를 조회해 미처리 token과 pending→purchased 전환을 처리한다.

검증:

```powershell
.\gradlew.bat :app:testGoogleDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleGoogleDebug --no-daemon
rg -n 'ProductDetails|queryProductDetails|PENDING|acknowledge|ITEM_ALREADY_OWNED|SERVICE_DISCONNECTED' app\src\google
```

런타임 Gate:

- test license에서 정상 구매, pending 결제, 취소, 이미 구매, 네트워크 끊김 후 재시작을 확인한다.
- 같은 process에서 BillingClient가 두 개 생성되지 않는지 확인한다.

### P7 — Huawei IAP gateway

대상:

- `app/src/huawei/java/com/example/convert2video/billing/HuaweiBillingGateway.kt:1` 신규
- `app/src/huawei/java/com/example/convert2video/billing/BillingGatewayFactory.kt:1` 신규
- `app/src/huawei/java/com/example/convert2video/billing/HuaweiPurchaseResultParser.kt:1` 신규

필수 동작:

- Huawei product detail/owned purchase 조회 API를 실제 선택 SDK 버전에 맞게 확인한다.
- `createPurchaseIntent`의 PendingIntent를 `NeedsResolution`으로 전달한다.
- ActivityResult는 parser 순수 함수로 변환하고, 취소·실패·성공·중복 구매를 구분한다.
- Huawei token도 반드시 backend verify 후에만 Pro로 만든다.
- `agconnect-services.json`은 절대 커밋하지 않고 flavor/build 설정과 ignore를 검증한다.

검증:

```powershell
.\gradlew.bat :app:testHuaweiDebugUnitTest --no-daemon
.\gradlew.bat :app:assembleHuaweiDebug --no-daemon
rg -n 'PendingIntent|PurchaseResultInfo|createPurchaseIntent|parsePurchaseResult' app\src\huawei
```

런타임 Gate:

- Huawei sandbox에서 구매 시트, 취소, 결과 재진입, 앱 재시작 후 owned query를 확인한다.
- Huawei flavor APK에 Google 인증 구현/Google 서비스 의존성이 들어가지 않는지 확인한다.

### P8 — 상태 조합 테스트 고정

대상은 최대 3개 테스트 파일이다.

- `app/src/test/.../EntitlementApiClientTest.kt`
- `app/src/test/.../EntitlementRepositoryTest.kt`
- `app/src/testGoogle` 또는 `app/src/testHuawei`의 gateway/parser 테스트

테스트 표:

| 입력 | 기대 상태 | Pro |
|---|---|---|
| PURCHASED + verify 성공 | Active | true |
| PENDING | Pending | false |
| User cancelled | Cancelled | false |
| verify 409 | Restore unavailable | false 또는 기존 유효 snapshot 유지 정책에 따름 |
| refresh 403 revoked | Revoked | false |
| refresh 503 + JWT 미만료 | Cached active | true |
| refresh 503 + JWT 만료 | Unknown/expired | false |
| acknowledge 실패 | Retry pending | 새 entitlement를 잃지 않음 |

검증:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:testGoogleDebugUnitTest --no-daemon
.\gradlew.bat :app:testHuaweiDebugUnitTest --no-daemon
rg -n 'assert|PENDING|409|403|503|acknowledge|expired|revoked' app\src\test app\src\testGoogle app\src\testHuawei
```

종료 조건:

- 네트워크 실패를 구매 취소로 잘못 표시하지 않는다.
- 미acknowledged purchase가 다음 query에서 다시 처리된다.

### P9 — ConversionWorker watermark gate

대상:

- `app/src/main/java/com/example/convert2video/video/ConversionWorker.kt:60-101`
- `app/src/test/java/com/example/convert2video/video/ConversionWorkerWatermarkGateTest.kt:1` 신규

구현:

- foreground 설정과 입력 검증 뒤, export 전에 `EntitlementRepository.isProSnapshot()`을 한 번 읽는다.
- 기존 두 `videoConverter.convert()` 호출 모두 `applyWatermark = !isPro`를 명시한다.
- entitlement 읽기 실패는 false로 처리하여 watermark를 유지한다.
- `VideoConverter.kt:340-347,425-432`의 기본값 true와 watermark 구현은 바꾸지 않는다. 주석만 호출부 gate 완료 상태에 맞게 갱신한다.

검증:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*ConversionWorkerWatermarkGateTest' --no-daemon
rg -n -C 4 'videoConverter\.convert|applyWatermark|isProSnapshot' app\src\main\java\com\example\convert2video\video
.\scripts\check-forbidden-imports.ps1
```

런타임 Gate:

- Pro 전/후 동일 입력에서 watermark 유무를 확인한다.
- segment와 full-file 변환 모두 같은 gate를 따른다.
- 구매 직후 이미 실행 중인 Worker의 상태가 중간에 바뀌지 않는다.

### P10 — OptionsViewModel 상태와 명령

대상:

- `app/src/main/java/com/example/convert2video/ui/screens/options/OptionsViewModel.kt:93-116,908-965,1375-1390`
- `app/src/test/java/com/example/convert2video/ui/screens/options/OptionsViewModelBillingTest.kt:1` 신규

구현:

- `proStatus: StateFlow<Boolean?>`, `isPurchaseInFlight`, `isRestoreInFlight`를 제공한다.
- `purchasePro(activity)`, `restorePurchase()`, `onPurchaseActivityResult(resultCode, data)`를 제공한다.
- gateway/repository/API/DataStore는 ViewModel 밖에서 소유한다.
- Huawei resolution 요청은 `SharedFlow<IntentSenderRequest>`로 전달한다.
- 결과 메시지는 resource ID 기반으로 만든다. HTTP code/raw exception/토큰은 UI에 전달하지 않는다.
- lifecycle에서 `onCleared()` 시 gateway close를 중복 호출하지 않도록 ownership을 명확히 한다.

검증:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*OptionsViewModelBillingTest' --no-daemon
rg -n 'proStatus|purchasePro|restorePurchase|onPurchaseActivityResult|rawMessage|purchaseToken' app\src\main\java\com\example\convert2video\ui\screens\options\OptionsViewModel.kt
.\scripts\check-logging-forbidden.ps1
```

런타임 Gate:

- 이중 구매 탭, 복원 중 재탭, Huawei 취소, backend 409/503, rotation을 확인한다.

### P11 — Pro 문자열 리소스

대상:

- `app/src/main/res/values/strings.xml:1` 인접 options key
- `app/src/main/res/values-ko/strings.xml:1` 동일 key

필수 문자열:

- title, localized price 설명, upgrade button, active/inactive status
- purchase in progress, pending, cancelled, failed
- restore button, restore success, restore unavailable
- 네트워크/서버 오류의 정중한 fallback
- “구매 복원은 동일 설치 정책에 따라 제한될 수 있음” 안내

문자열에 `409`, `JWT`, `token`, stack trace를 넣지 않는다. Google/Huawei flavor에서 상품 이름·가격 표시는 ProductDetails에서 가져오는 실제 값과 충돌하지 않게 한다.

검증:

```powershell
rg -n 'options_pro_|pro_|purchase|restore' app\src\main\res\values\strings.xml app\src\main\res\values-ko\strings.xml
.\scripts\check-terminology-forbidden.ps1
```

런타임 Gate:

- 영어/한국어에서 버튼·상태·오류 문장이 모두 표시된다.
- pending과 cancelled가 같은 문구로 뭉개지지 않는다.

### P12 — Options Pro UI component

대상:

- `app/src/main/java/com/example/convert2video/ui/screens/options/OptionsProUpgradeContent.kt:1` 신규
- `app/src/androidTest/java/com/example/convert2video/ui/screens/options/OptionsProUpgradeContentTest.kt:1` 신규

구현:

- 기존 `OptionsYoutubeAccountContent`/`OptionsDriveAccountContent`처럼 무상태 Compose content로 만든다.
- `StoreCapabilities.current.supportsBilling`은 호출부에서 gate하고 content는 상태와 callback만 받는다.
- testTag는 `pro_upgrade_button`, `pro_restore_button`, `pro_status_text`로 고정한다.
- null 상태는 확인 중, pending은 결제 처리 중, false는 inactive, true는 active로 구분한다.
- 이미 Pro면 upgrade 버튼을 비활성화하되 restore는 정책에 맞게 제공한다.

검증:

```powershell
.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon
.\gradlew.bat :app:compileHuaweiDebugKotlin --no-daemon
rg -n 'Text\s*\(\s*"|pro_upgrade_button|pro_restore_button|pro_status_text' app\src\main\java\com\example\convert2video\ui\screens\options
.\scripts\check-logging-forbidden.ps1
```

런타임 Gate:

- null/false/pending/true 상태 전환이 UI에서 명확하다.
- 버튼 callback 외에 UI가 결제 SDK를 직접 호출하지 않는다.

### P13 — OptionsScreen wiring와 Huawei ActivityResult

대상:

- `app/src/main/java/com/example/convert2video/ui/screens/options/OptionsScreen.kt:198-435,706-748`
- `app/src/androidTest/java/com/example/convert2video/ui/screens/options/OptionsScreenBillingTest.kt:1` 신규

구현:

- billing capability가 true인 flavor에서만 Pro section을 노출한다.
- `rememberLauncherForActivityResult(StartIntentSenderForResult())`를 Huawei resolution용으로 등록한다.
- launcher 결과는 `resultCode`와 `data`를 함께 ViewModel에 전달한다.
- YouTube/Drive launcher와 billing launcher를 같은 flow로 합치지 않는다.
- Activity가 없는 context, launcher 예외, recomposition 중 중복 launch를 안전하게 처리한다.

검증:

```powershell
.\gradlew.bat :app:testGoogleDebugUnitTest --no-daemon
.\gradlew.bat :app:testHuaweiDebugUnitTest --no-daemon
rg -n 'supportsBilling|StartIntentSenderForResult|pro_upgrade|pro_restore|onPurchaseActivityResult' app\src\main\java\com\example\convert2video\ui\screens\options\OptionsScreen.kt
.\scripts\check-forbidden-imports.ps1
```

런타임 Gate:

- Google은 purchase sheet가 열리고 callback이 한 번만 처리된다.
- Huawei는 PendingIntent → ActivityResult → parser → verify 순서가 유지된다.
- 앱 회전·백그라운드 복귀 후 in-flight 상태가 고착되지 않는다.

### P14 — 백업·문서·최종 release gate

대상:

- `app/src/main/res/xml/backup_rules.xml:8-13`
- `app/src/main/res/xml/data_extraction_rules.xml:7-23`
- `CLAUDE.md:230-506,700-760`의 billing folder/role/glossary 및 backend 범위 동기화

백업:

- `file/datastore/entitlement.preferences_pb`를 cloud backup과 device transfer 양쪽에서 제외한다.
- 기존 `install_id.xml`, `pairing_token.preferences_pb` 제외는 유지한다.
- JWT가 포함된 파일이 다른 경로로 백업되지 않는지 확인한다.

문서 동기화:

- Product Scope의 “analytics/crash만 backend 예외” 문구를 entitlement verify/refresh와 RTDN까지 정확히 갱신한다.
- Core/Anti-Repeat의 OkHttp 허용 목록에 `billing/`을 추가한다.
- Folder Structure와 역할 표에 `billing/`, flavor gateway, `EntitlementRepository`, `EntitlementStore`를 추가한다.
- Glossary에 route, key, SKU, capability, 상태 타입을 등록한다.
- `CLAUDE.md`의 한 줄 요약과 관련된 모든 중복 문구를 함께 갱신한다.

최종 검증:

```powershell
.\gradlew.bat :app:assembleGoogleDebug --no-daemon
.\gradlew.bat :app:assembleHuaweiDebug --no-daemon
.\gradlew.bat :app:testDebugUnitTest --no-daemon
.\gradlew.bat :app:testGoogleDebugUnitTest --no-daemon
.\gradlew.bat :app:testHuaweiDebugUnitTest --no-daemon
.\scripts\check-forbidden-imports.ps1
.\scripts\check-logging-forbidden.ps1
.\scripts\check-terminology-forbidden.ps1
.\scripts\verify-harness-setup.ps1
git diff --check
git status --short
```

최종 수동 시나리오:

1. Google license tester로 정상 구매한다.
2. 구매 직후 Pro 상태와 watermark 제거 결과를 확인한다.
3. 앱을 강제 종료하고 다시 열어 상태를 확인한다.
4. offline 상태에서 만료 전 JWT는 기존 정책대로 동작하고, 만료 후에는 fail-closed인지 확인한다.
5. pending 구매는 Pro를 부여하지 않는지 확인한다.
6. backend revoke/403 후 다음 refresh에서 watermark가 다시 적용되는지 확인한다.
7. 다른 install ID에서 restore를 시도했을 때 계획한 안내 문구가 표시되는지 확인한다.
8. Huawei sandbox에서 동일 시나리오를 수행한다.
9. 10분 초과 변환은 P0에서 확보한 조건으로 재현하고, 결제 기능과 독립적으로 결과를 기록한다.

---

## 4. Gate 선언 규칙

다음 세 조건이 모두 충족되기 전에는 “완료”라고 선언하지 않는다.

- 해당 Phase의 전용 테스트/compile이 PASS
- `run-stop-checks.ps1` 또는 개별 forbidden/logging/terminology 검사가 PASS
- 해당 Phase의 런타임 edge case가 확인되었거나, 외부 계정·실기기 의존으로 보류 사유가 문서화됨

특히 다음은 별도 보류 항목으로 남긴다.

- Huawei developer account/AppGallery 상품 설정 미완료
- Google Play product/license tester/service account 설정 미완료
- backend production URL·DB·JWT key 미배포
- P0 logcat으로도 10분 컷오프 원인이 확정되지 않음

공식 결제 생명주기 참고:

- https://developer.android.com/google/play/billing/lifecycle/one-time
- https://developer.android.com/google/play/billing/integrate
- https://developer.android.com/google/play/billing/errors

수정은 Cursor AI를 통해 진행할 예정입니다. 각 Sprint별로 단독 가동하기 좋게 프로젝트 룰 및 아토믹 디자인(Atomic Design) 단위를 반영한 플랜 작성이 완료되었습니다.
