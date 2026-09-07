# 계획: 무료 Pro Google Play 출시 전 정리 + 후속 구조 리팩토링

## [1] 아키텍처 영향 분석 (Architecture Impact Report)

### 출시 전 결론

현재 가장 위험한 문제는 거대 클래스가 아니라 출시 설정과 제품 배선이다. 다음 항목은 구조 리팩토링보다 먼저 처리해야 한다.

- `applicationId`는 `com.convert2video`로 확정하고 Kotlin `namespace`/패키지 `com.example.convert2video`와 분리한다. 기존 서명 키와 Play Console 앱 등록을 함께 확인한다.
- 릴리스 signing 설정이 없으므로 Play App Signing/업로드 키 전략을 정한다. 키 비밀번호와 파일은 저장소에 넣지 않는다.
- 사용자는 유료 결제를 넣지 않기로 했다. 따라서 `BillingManager`의 SKU와 entitlement backend를 출시 경로에서 제거하거나 비활성화하고, Pro 기능의 접근 정책을 “모든 사용자 허용”으로 통일한다. 구매 UI만 숨기고 내부 게이트를 남기는 방식은 금지한다.
- `BACKEND_BASE_URL`의 `TODO` 값은 유료 entitlement·analytics·crash 업로드가 출시 범위에 남아 있을 때만 실주소로 바꾼다. 무료 출시에서 해당 backend를 사용하지 않는다면 호출 경로를 명시적으로 끄고 placeholder가 실행되지 않게 한다.
- `error_log_entries`는 무제한 누적되지 않도록 최신 N건 보존 정책을 추가한다.
- 릴리스 로그는 `AppLogger`의 debug/info를 `BuildConfig.DEBUG`로 제한한다.

### 영향받는 레이어

- Android 빌드/배포 설정: `app/build.gradle.kts`, signing 및 flavor 정책
- Data/서비스: Billing·entitlement 접근 게이트, ErrorLog DAO/Repository
- ViewModel/Screen: Options와 결제·Desktop Sync 게이트, Contact Us 설정
- UI 구조: Options, Converted Videos, MainActivity, Recordings List의 대형 Composable 분리
- 서비스 구조: `PairingServerService`의 FGS lifecycle·Ktor route·pairing state·upload handler 분리

Atomic Design 폴더는 현재 `ui/components`와 `ui/screens/<domain>` 규칙을 유지한다. 기존 프로젝트는 전통적인 `atoms~pages` 디렉터리를 사용하지 않으므로 새 컴포넌트는 `ui/components/controls` 또는 해당 화면 도메인에 둔다.

### 지켜야 할 프로젝트 규칙

- `CLAUDE.md`의 Product Scope와 WorkManager 2-tier 상태 매핑을 변경하지 않는다.
- `video/`가 `ui.screens.*`를 import하지 않게 유지한다.
- Room은 Repository를 경유하고, UI에서 Dao를 직접 호출하지 않는다.
- `android.util.Log`는 `AppLogger` 외에서 사용하지 않는다.
- 사용자 문자열은 리소스로 관리하고, 새 공개 상수·도메인 용어는 `CLAUDE.md` glossary에 등록한다.
- 네트워크는 기존 `youtube/`, `drive/`, `billing/`, `analytics/`의 제한된 OkHttp 용도만 유지한다. 무료 출시로 제거하는 경로는 호출부까지 확인 후 삭제한다.
- 기존의 uncommitted 작업은 Harness가 커밋 단위로 보존한다. 이 문서는 파일을 대량 이동하기 전에 기준 커밋/리뷰 가능한 상태가 있음을 전제로 한다.

## [2] 🚀 한 눈에 보는 스프린트 로드맵

| Sprint | 작업 대상 (레이어) | 디펜던시 | 참조 파일:줄번호 | 병렬 가능 |
|--------|-------------------|----------|-----------------|-----------|
| S-01 | 현재 작업 트리 기준점·릴리스 범위 확인 | 없음 | `app/build.gradle.kts:27-46`, `CLAUDE.md:Product Scope` | - |
| S-02 | 무료 Pro 정책 및 Billing/entitlement 호출 경로 정리 (Data/VM/UI) | S-01 | `BillingManager.kt:30`, `EntitlementManager.kt:238-266`, `OptionsViewModel.kt` | S-03과 병렬 |
| S-03 | Google Play 릴리스 설정·패키지·서명·불필요 Huawei 경로 결정 (Build) | S-01 | `app/build.gradle.kts:12-46`, `gradle/libs.versions.toml:39,84,91` | S-02와 병렬 |
| S-04 | 운영 로그 제한 및 ErrorLog 상한 (Data/utility) | S-01 | `AppLogger.kt:56-72`, `ErrorLogDao.kt:11-15`, `AppDatabase.kt:80-95` | S-02와 병렬 |
| S-05 | placeholder·l10n·릴리스 정책 검증 (UI/config) | S-02,S-03 | `ContactUsIntent.kt:11`, `values/strings.xml`, `values-ko/strings.xml` | S-04와 병렬 |
| S-06 | 릴리스 통합 검증·Play 정책 체크 | S-02~S-05 | `AndroidManifest.xml:7-40`, `.cursor/hooks/run-stop-checks.ps1` | - |
| S-07 | Options 화면/VM 분리 (UI/ViewModel) | S-06 | `OptionsScreen.kt:204`, `OptionsViewModel.kt`, `ui/components/controls` | S-08과 병렬 |
| S-08 | Converted Videos 상태·다이얼로그·행 렌더링 분리 (UI) | S-06 | `ConvertedVideosScreen.kt:142`, `ConvertedVideosScreen.kt:1115` | S-07과 병렬 |
| S-09 | MainActivity와 Recordings List 화면 분리 (UI/navigation) | S-06 | `MainActivity.kt:416`, `RecordingsListScreen.kt:154,592` | S-07과 병렬 |
| S-10 | Desktop Sync 서비스 책임 분리 및 LAN 보안 점검 (Service) | S-06 | `PairingServerService.kt:400-425,702`, `DesktopSyncController.kt:149-157` | S-07~S-09와 병렬 |
| S-11 | 공통 UI·네트워크·deprecated 정리 및 회귀 검증 | S-07~S-10 | `RecordScreen.kt:1058`, `YouTubeUploadWorker.kt:131` | - |

## [3] 🛠️ 스프린트별 상세 실행 가이드 & 검증 포인트

### S-01. 기준점과 무료 출시 범위 고정

1. 수정/이동 파일 목록
   - 코드 변경 없음. `app/build.gradle.kts:27-46`, `CLAUDE.md` Product Scope, `git status`를 기준 자료로 기록한다.
2. 동시 확인 파일
   - 215개 수준의 변경/미추적 파일 중 앱 소스가 의도한 출시 범위인지 Harness 커밋 단위로 확인한다.
3. 사전 확인 체크리스트
   - `rg --files app/src/main | rg "Billing|Entitlement|Options|Pairing|ErrorLog"`
   - `rg -n "PRO_LIFETIME|isPro|entitlement|BACKEND_BASE_URL|support@|TODO\.example" app`
   - 기존 `plan-blueprint.md`는 덮어쓰지 않는다.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-forbidden-imports.ps1`
   - 문자열/용어: `rg -n 'TODO\.example|pro_lifetime_unlock|support@.*example|gathering|booking|meetup|reservation|my_info|my_page' app`
   - 런타임: 무료 사용자가 결제 화면 없이 Home → Listen → Convert → Converted Videos 골든패스를 완료하는 정책을 테스트 케이스로 확정한다.

### S-02. 무료 Pro 정책으로 Billing/entitlement 경로 정리

1. 수정/이동 파일 목록
   - `app/src/main/java/com/example/convert2video/billing/BillingManager.kt:30`의 SKU 의존성 제거 또는 출시 variant에서 제외
   - `app/src/main/java/com/example/convert2video/billing/EntitlementManager.kt:238-266`의 구매/검증 게이트를 무료 정책에 맞게 제거
   - `app/src/main/java/com/example/convert2video/ui/screens/options/OptionsViewModel.kt` 및 실제 paywall 호출부는 한 스프린트에 2~3개 파일씩 나눠 연결
2. 동시 확인 파일
   - `rg -n "BillingManager|EntitlementManager|isPro|Paywall|purchase|verifyPurchase|serverState" app/src/main`
   - `DesktopSyncController`와 Options의 Desktop Sync 게이트가 남아 있지 않은지 확인한다.
3. 사전 확인 체크리스트
   - 결제 라이브러리 제거 전 참조가 0인지 확인한다. 남은 참조가 있으면 먼저 adapter를 만든다.
   - `billing/` backend endpoint를 삭제할지 유지할지 결정한다. 유지할 경우 앱에서 호출되지 않아야 한다.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-forbidden-imports.ps1`
   - 문자열/용어: `rg -n 'PRO_LIFETIME|paywall|purchase|entitlement|gathering|booking|meetup|reservation|my_info|my_page' app/src/main`
   - 런타임: 결제 미설정·네트워크 오프라인·backend 미배포 상태에서도 모든 무료 Pro 기능이 접근되고, 구매/환불을 기다리는 상태가 없어야 한다.

### S-03. Google Play 릴리스 설정

1. 수정/이동 파일 목록
   - `app/build.gradle.kts:12-46` — Google flavor의 `applicationId`가 `com.convert2video`이고 namespace/package가 `com.example.convert2video`로 유지되는지 확인한다.
   - `app/build.gradle.kts:39-46` — signing은 로컬/CI secret property로만 연결하고 release build 정책을 결정한다.
   - `gradle/libs.versions.toml:39,84,91` — Huawei IAP/AGCP가 Google 전용 빌드에 불필요하면 제거하되 참조 0을 확인한다.
2. 동시 확인 파일
   - `AndroidManifest.xml:7-40`의 exact alarm 및 foreground service 권한을 Play Console 신고 목록과 대조한다.
   - `google-services.json`과 업로드 키는 저장소에 넣지 않고 CI secret/로컬 비추적 경로를 사용한다.
3. 사전 확인 체크리스트
   - `applicationId=com.convert2video`, versionCode 증가 규칙, Play App Signing 소유자를 기준으로 확인한다.
   - 무료 출시에서 backend를 사용하지 않으면 `BACKEND_BASE_URL` 호출이 남지 않았는지 확인한다.
4. 검증
   - 컴파일: `.\gradlew.bat :app:assembleGoogleRelease --no-daemon`
   - 구조: `rg -n 'signingConfig|storePassword|keyPassword|storeFile|applicationId|BACKEND_BASE_URL' app/build.gradle.kts`
   - 문자열/용어: applicationId와 namespace/FQCN을 구분하여 `com.convert2video` wiring 및 기존 `com.example.convert2video` namespace/package 보존을 확인하고, `TODO\.example|gathering|booking|meetup|reservation|my_info|my_page`도 검사한다.
   - 런타임/배포: 서명된 AAB 설치, 신규 설치·업데이트 설치, 백업 복원, Android 13+ 권한 동작과 Play Data Safety/FGS 선언을 확인한다.

### S-04. 운영 로그와 ErrorLog 상한

1. 수정/이동 파일 목록
   - `app/src/main/java/com/example/convert2video/utils/AppLogger.kt:56-72` — release에서 debug/info가 기록되지 않도록 게이트한다.
   - `app/src/main/java/com/example/convert2video/data/ErrorLogDao.kt:11-15` — 최신 N건 보존을 위한 삭제 쿼리를 추가한다.
   - `app/src/main/java/com/example/convert2video/data/ErrorLogRepository.kt:13` 또는 persist sink 연결부 — insert 후 trim을 한 번만 호출한다.
2. 동시 확인 파일
   - `Convert2videoApplication.kt:60-77`의 persist sink 호출 빈도와 `ErrorLogViewModel.kt:22`의 전체 관찰을 확인한다.
3. 사전 확인 체크리스트
   - 동일한 `deleteOldest`/TTL 정책이 이미 있는지 `rg -n "error_log_entries|delete.*Error|observeAll" app/src`로 확인한다.
   - stack trace/message의 민감정보가 로그와 DB에 들어가지 않는 현재 정책을 유지한다.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-logging-forbidden.ps1`
   - 문자열/용어: `rg -n 'android\.util\.Log|Text\(\s*"|gathering|booking|meetup|reservation|my_info|my_page' app/src/main`
   - 런타임: 반복 오류를 500회 발생시켜 DB가 정한 상한을 넘지 않는지, Error Log 화면이 빠르게 열리는지, release APK에서 D/I 로그가 사라지는지 확인한다.

### S-05. placeholder·지원 이메일·한국어 리소스

1. 수정/이동 파일 목록
   - `app/src/main/java/com/example/convert2video/ui/screens/options/ContactUsIntent.kt:11` — 실제 지원 주소 또는 무료 출시에서 유효한 문의 경로로 교체한다.
   - `app/src/main/res/values/strings.xml` 및 `values-ko/strings.xml` — contact subject/body와 출시 문구를 양쪽에 맞춘다.
   - 무료 정책으로 남길 수 없는 placeholder를 `app/build.gradle.kts:36`과 관련 backend 호출부에서 제거한다.
2. 동시 확인 파일
   - `rg -n 'TODO|placeholder|example\.com|support@|options_contact_us' app backend`
3. 사전 확인 체크리스트
   - 새 문자열 key를 만들기 전에 기존 key를 검색한다.
   - 사용자 노출 문자열을 Kotlin 리터럴로 추가하지 않는다.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-terminology-forbidden.ps1`
   - 문자열/용어: `rg -n 'Text\(\s*"|TODO\.example|support@.*example|gathering|booking|meetup|reservation|my_info|my_page' app/src/main`
   - 런타임: 영어/한국어에서 Contact Us가 올바른 mailto를 만들고, 문의 실패 시 앱이 종료되지 않는지 확인한다.

### S-06. 출시 통합 검증

1. 코드 변경 없음. S-02~S-05의 결과를 하나의 release checklist로 묶는다.
2. 확인 파일: `AndroidManifest.xml`, `proguard-rules.pro`, backup rules, `scripts/check-*.ps1`.
3. 사전 확인: `rg -n 'TODO|FIXME|isMinifyEnabled|allowBackup|SCHEDULE_EXACT_ALARM|FOREGROUND_SERVICE' app backend`.
4. 검증
   - 컴파일: `.\gradlew.bat :app:testGoogleDebugUnitTest --no-daemon`
   - 구조: `.\.cursor\hooks\run-stop-checks.ps1`
   - 문자열/용어: `rg -n 'com\.example\.|TODO\.example|pro_lifetime_unlock|support@.*example|gathering|booking|meetup|reservation|my_info|my_page' .`
   - 런타임: cold start, 권한 거부, 녹음 중 종료/재진입, WorkManager 재개, 대용량 변환, 오프라인, 언어 변경, 백업 복원, Play 내부 테스트 트랙을 통과한 뒤에만 출시한다.

### S-07. Options 화면/VM 분리 (출시 후)

1. 수정/이동 파일 목록
   - `OptionsScreen.kt:204-941`의 섹션별 stateful wrapper를 먼저 분리한다.
   - YouTube, Drive, Desktop Sync 상태를 각각 작은 ViewModel/상태 모델로 이동한다. 한 스프린트에 한 도메인만 처리한다.
   - capability-gated state 반복은 공통 UI helper로 추출하되 domain import를 만들지 않는다.
2. 동시 확인 파일: `OptionsScreen.kt:1103,1210,1397,1624,1842,1946,2157`, `OptionsViewModel.kt`.
3. 사전 확인: `rg -n "collectAsStateWithLifecycle|shouldShowYouTubeOptions|Options.*Content" app/src/main`.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-forbidden-imports.ps1`
   - 문자열/용어: `rg -n 'Text\(\s*"|gathering|booking|meetup|reservation|my_info|my_page' app/src/main/java/com/example/convert2video/ui`
   - 런타임: 화면 회전·프로세스 재생성·OAuth 취소·Drive 오류·무료 Desktop Sync 진입에서 상태가 유지되고 중복 요청이 없는지 확인한다.

### S-08. Converted Videos 분리 (출시 후)

1. 수정/이동 파일 목록
   - `ConvertedVideosScreen.kt:142-899` — selection state machine, upload dialogs, list rendering을 별도 도메인 파일로 분리한다.
   - `ConvertedVideosScreen.kt:1115`의 row composable과 `:1548,1654,1790`의 upload dialogs를 각각 단계적으로 이동한다.
2. 동시 확인 파일: `ConvertedVideosViewModel.kt`, `ui/shared/WorkInfoUiPhase.kt`, 관련 string resource.
3. 사전 확인: 기존 `ConvertedVideoFolderLabel.kt`, filter/sort mapping, upload dialog 파일의 중복 여부를 검색한다.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-forbidden-imports.ps1`
   - 문자열/용어: `rg -n 'WorkInfo\.State|Text\(\s*"|gathering|booking|meetup|reservation|my_info|my_page' app/src/main/java/com/example/convert2video/ui`
   - 런타임: 단일/다중 선택, 삭제, YouTube 업로드 진행/취소/재진입, Converted 탭 랜딩을 확인한다.

### S-09. MainActivity 및 Recordings List 분리 (출시 후)

1. 수정/이동 파일 목록
   - `MainActivity.kt:416-999` — shell navigation, prompts, Home tabs를 별도 coordinator/composable로 나눈다.
   - `RecordingsListScreen.kt:154-892` — stateful orchestration과 stateless content를 domain 단위로 나눈다.
   - `RecordScreen.kt:406-778` — Record content 분리는 별도 스프린트로 진행한다.
2. 동시 확인 파일: `AppDestination`, `HomeTab`, `ConvertViewModel`, `RecordViewModel`.
3. 사전 확인: `rg -n 'AppDestination|HomeTab|setHomeTabRoot|WorkInfo\.State' app/src/main/java/com/example/convert2video`.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-forbidden-imports.ps1`
   - 문자열/용어: `rg -n 'ConvertedVideos|drawer_converted_videos|Text\(\s*"|gathering|booking|meetup|reservation|my_info|my_page' app/src/main`
   - 런타임: Convert 확인 버튼은 Convert에 잔류하고, 목록 보기/TopBar 칩만 Home의 ConvertedVideos 탭으로 이동하는지 확인한다.

### S-10. Desktop Sync 서비스 분리 및 보안 점검 (출시 후)

1. 수정/이동 파일 목록
   - `PairingServerService.kt:400-425` — Ktor route registration을 route module로 분리한다.
   - `PairingServerService.kt:702-853` — upload validation/streaming을 handler로 분리한다.
   - `DesktopSyncController.kt:149-157` — `BIND_AUTO_CREATE`를 실제 기능 진입 시점의 lazy bind로 바꿀지 검토한다.
2. 동시 확인 파일: `NsdAdvertiser.kt`, `PairingTokenStore.kt`, Manifest의 service 선언.
3. 사전 확인: `rg -n '0\.0\.0\.0|/ping|/pair/request|/whoami|/upload|BIND_AUTO_CREATE' app/src/main`.
4. 검증
   - 컴파일: `.\gradlew.bat :app:compileGoogleDebugKotlin --no-daemon`
   - 구조: `.\scripts\check-forbidden-imports.ps1`
   - 문자열/용어: `rg -n 'absolutePath|uri|token|jwt|Text\(\s*"|gathering|booking|meetup|reservation|my_info|my_page' app/src/main/java/com/example/convert2video/desktopsync`
   - 런타임: 인증 전 multipart 파싱 금지, Free/Pro 정책과 관계없이 무료 사용자가 기능을 사용할 수 있는지, 공유 Wi-Fi에서 pairing/upload rate limit·payload cap·서비스 종료 정리가 작동하는지 확인한다.

### S-11. 공통 정리

1. 수정/이동 파일 목록
   - `RecordScreen.kt:1058`의 local `findActivity`를 shared helper로 통일한다.
   - `YouTubeUploadWorker.kt:131`에 예외 처리와 cancellation 정책을 `DriveAutoUploadWorker`와 맞춘다.
   - `ConversionWorker.kt:127,191,215`, `YouTubeUploadWorker.kt:274,284`에서 `CancellationException`을 삼키지 않도록 정리한다.
   - bare `@Suppress("DEPRECATION")`에는 날짜와 사유를 추가한다.
2. 동시 확인 파일: `MainActivity.kt:103`, `DriveAutoUploadWorker.kt`, 관련 테스트.
3. 사전 확인: `rg -n 'catch \(e: Exception\)|runCatching|@Suppress\("DEPRECATION"\)' app/src/main`.
4. 검증
   - 컴파일: `.\gradlew.bat :app:testGoogleDebugUnitTest --no-daemon`
   - 구조: `.\.cursor\hooks\run-stop-checks.ps1`
   - 문자열/용어: `rg -n 'android\.util\.Log|Text\(\s*"|gathering|booking|meetup|reservation|my_info|my_page' app/src/main`
   - 런타임: Worker 취소가 재시도/취소 상태로 남고, 예외 메시지는 사용자용 fallback으로만 표시되며, 업로드 실패가 프로세스를 종료하지 않는지 확인한다.

## [4] 🏁 마무리 선언

수정은 Cursor AI를 통해 진행할 예정입니다. 각 Sprint별로 단독 가동하기 좋게 프로젝트 룰 및 아토믹 디자인(Atomic Design) 단위를 반영한 플랜 작성이 완료되었습니다.
