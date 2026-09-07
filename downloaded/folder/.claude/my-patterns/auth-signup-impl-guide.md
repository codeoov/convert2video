# 회원가입/인증 구현 가이드

> **목적**: 신규 프로젝트에서 이 문서를 보고 회원가입·로그인·세션·웰컴 진입을 구현한다.  
> **검증 기준 프로젝트**: `happy_v12` (Kotlin, Compose, Hilt, Retrofit, 멀티모듈: `domain:auth` 등).  
> **최종 갱신**: 2026-04 — 로그인 성공 네비 단일화(`PostSignInNavigation`), 웰컴 세션 검증 타임아웃 UX, 제출·IME 패턴 반영.

이 가이드는 **다른 앱으로 그대로 복사해 쓸 수 있는 원칙 문서**다. 프로젝트 트리·패키지명·문자열 리소스 이름은 각 앱에 맞게 바꾸되, **아래 “하지 말 것”은 이유와 함께 지키는 것**을 권장한다.

---

## 0. 다른 앱으로 옮길 때 — 하지 말 것·원칙 (총정리)

### 0.1 네비게이션·라우트

| 하지 말 것 | 이유 | 대신 |
|------------|------|------|
| 화면 파일에 `"welcome"`, `"home"` 등 **경로 문자열 하드코딩** | 오타·불일치 시 런타임만 깨짐 | `Route` **단일 소스** (`config/Route.kt` 등) |
| 이메일·토큰·전화번호를 **직접 문자열 조합**해 `navigate` | `@`, `/`, `+` 등으로 깨짐 | `AuthDestinations` + **encode/decode** (`RouteArgsCodec` 등) |
| **로그인 성공 후** `ACCOUNT_BLOCKED` / `PHONE_SIGNUP` / `HOME` 분기를 **화면마다 복붙** | 한 화면만 규칙이 달라지면 버그 | **`resolvePostSignInDestination`** 같은 **순수 함수 한 곳** + 화면은 `apply`만 (§6) |
| `popUpTo` 없이 무한 백스택 쌓기 | 뒤로가기 지옥 | 플로우에 맞게 `popUpTo`·`inclusive` 설계 |

### 0.2 문자열·l10n

| 하지 말 것 | 이유 | 대신 |
|------------|------|------|
| UI 문구 **코드에 한글/영문 하드코딩** | 다국어·톤 통제 불가 | `stringResource` / `context.getString(R.string.xxx)` |
| **Composable이 아닌 곳**(ViewModel, `onClick` 람다, semantics)에서 `stringResource()` | Compose 규칙 위반·크래시 위험 | ViewModel은 **문자열을 만들지 않거나** `Context.getString` / UI에서만 조합 |
| 운영 빌드에서 **API 메시지·스택** 그대로 노출 | 보안·신뢰 | 사용자용 짧은 문구만 (`ErrorDisplayHelper` 등 프로젝트 표준) |

### 0.3 세션·웰컴

| 하지 말 것 | 이유 | 대신 |
|------------|------|------|
| 웰컴에서 서버 검증 **타임아웃** 시 **무조건 로컬 세션 삭제** | 느린 망에서 정상 사용자까지 로그아웃됨 | 정책을 명시: happy_v12는 **타임아웃 시 세션 유지 + 재시도 UI** (§8) |
| `performWelcomeCheck`를 **한 번도 호출 안 함** | 자동 로그인 불가 | 세션 로드 완료 후 1회 + **재시도 트리거** 시 추가 (§8) |
| 타임아웃 값을 화면·ViewModel에 **매직 넘버** | 조정 어려움 | `DomainLimits.SESSION_VERIFY_TIMEOUT_MS` 같은 **단일 상수** |

### 0.4 제출 버튼·키보드(IME)

| 하지 말 것 | 이유 | 대신 |
|------------|------|------|
| **제출 직전** `clearFocus()`만 호출하고 바로 네트워크 호출 | IME 접힘과 레이아웃이 한 프레임에 바뀌어 **버튼이 튀거나 첫 탭이 먹지 않는 느낌** | `isLoading == true` 된 **뒤** 한 코루틴 스텝 뒤 `clearFocus()` (§9 `ClearFocusWhenLoadingEffect`) |

### 0.5 아키텍처·테스트

| 하지 말 것 | 이유 | 대신 |
|------------|------|------|
| `NavController`를 **도메인/유틸 순수 함수**에 넘김 | 테스트·재사용 어려움 | **결과만** `sealed class`로 반환하고, 화면에서 `navigate` |
| 로그인 분기 로직에 **단위 테스트 없음** | 리팩터링 시 회귀 | `PostSignInNavigationTest` 패턴 — 입력 `AuthSessionResult` → 기대 `route`·`popUpTo` (§6) |

### 0.6 빌드·도구 (참고)

| 하지 말 것 | 이유 | 대신 |
|------------|------|------|
| 실패한 증분 빌드를 **무한 재시도만** | `NoSuchFileException` (예: `DomainLimits.class`) ASM 단계 | `gradlew --stop` 후 `:app:clean` 또는 전체 clean rebuild |
| Kotlin/Gradle **데몬 여러 개** 방치 | 클래스 출력 경합 | `--stop` 또는 IDE에서 Daemon 정리 |

### 0.7 용어·이름 (happy_v12 규칙과 맞출 때)

- 모임은 `gathering`, 예약은 `booking` — `activity`(모임 의미), `meetup` 등 **금지 용어**는 프로젝트 룰 따름.  
- 다른 앱에서는 자체 용어 사전을 두되, **같은 개념에 이름 여러 개** 쓰지 말 것.

---

## 1. 전체 흐름 개요

```
앱 시작
  └→ WelcomeScreen
        ├→ 로컬 세션 로딩 (DataStore 등)
        ├→ 세션 있음 → performWelcomeCheck() (서버 검증, 타임아웃 시 재시도 UI 가능)
        │     ├→ 정지/휴면 → ACCOUNT_BLOCKED
        │     ├→ 가입·프로필 완료 → HOME
        │     └→ 그 외 → 웰컴 유지 또는 프로필 이어하기 버튼
        └→ 세션 없음 → 구글 / 이메일 진입

이메일 (기존 사용자)
  EmailEntry → EmailPasswordEntry 또는 EmailLogin
  → signIn 성공 → PostSignInNavigation(WelcomeStack) → HOME / 전화 / 차단

이메일 (신규)
  EmailEntry → SignupPassword → EmailVerification → PhoneSignup → … → SignupNickname → HOME

Google
  GoogleAuthLoading → 성공 시 PostSignInNavigation(GoogleAuthLoading)
        ├→ 정지/휴면 → ACCOUNT_BLOCKED
        ├→ 신규(isNewUser) → GOOGLE_AUTH_ADDITIONAL_INFO (이메일 플로우와 다름)
        ├→ 전화 필요 → PHONE_SIGNUP
        └→ 그 외 → HOME
```

**중요**: 이메일 로그인 화면은 `isNewUser`로 **추가 정보 화면으로 보내지 않는다**(happy_v12). 구글만 `isNewUser` 분기. 이 차이는 **`PostSignInNavContext`** 로 한 모듈에서 처리한다.

---

## 2. 파일·모듈 구조 (참조: happy_v12)

다른 앱은 폴더명이 달라도 **역할**은 맞출 것.

```
app/src/main/java/.../config/
  Route.kt                 ← 경로 상수 단일 소스
  DomainLimits.kt          ← SESSION_VERIFY_TIMEOUT_MS 등
  ApiActions.kt, DomainStatus.kt …

app/.../controllers/begin/welcome/
  AuthViewModel.kt
  EmailEntryViewModel.kt, EmailLoginViewModel.kt, EmailPasswordEntryViewModel.kt
  …

app/.../controllers/begin/signup/
  SignupPasswordViewModel.kt, SignupNicknameViewModel.kt, …

domain/auth/  (멀티모듈인 경우)
  AuthSessionResult.kt, SessionUserSnapshot.kt
  AuthRepository.kt (인터페이스)

app/.../data/repository/
  AuthRepository.kt (구현체 또는 위임)

app/.../navigation/
  AuthNavGraph.kt
  AuthDestinations.kt
  PostSignInNavigation.kt   ← 로그인 성공 후 이동 규칙 단일 소스 (권장)

app/.../ui/views/5_pages/begin/welcome/…
  WelcomeScreen.kt

app/.../ui/views/5_pages/begin/email/…
  EmailEntryScreen.kt, EmailLoginScreen.kt, EmailPasswordEntryScreen.kt, …

app/.../ui/views/5_pages/begin/google/…
  GoogleAuthLoadingScreen.kt, …

app/.../ui/views/5_pages/begin/common/…
  SignupNicknameScreen.kt, SignupReferralScreen.kt, …

app/.../ui/views/3_organisms/begin/welcome/…
  WelcomeSessionVerifyTimeoutBanner.kt   ← 웰컴 타임아웃 안내 (선택 구현 시)

app/.../ui/views/3_organisms/begin/common/begin_common_shared/
  ClearFocusWhenLoadingEffect.kt         ← 제출·IME 패턴 (선택)

app/src/test/.../navigation/
  PostSignInNavigationTest.kt            ← 분기 단위 테스트 (권장)
```

**하지 말 것**: 위 트리를 **문자 그대로 다른 앱 경로에 강요하지 말 것**. 역할 단위로 매핑할 것.

---

## 3. Route 상수

`config/Route.kt` — **문자열 경로는 여기만**.

```kotlin
object Route {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val EMAIL_ENTRY = "email_entry"
    const val EMAIL_LOGIN = "email_login"
    const val EMAIL_PASSWORD_ENTRY = "email_password_entry"
    const val GOOGLE_AUTH_LOADING = "google_auth_loading"
    const val GOOGLE_AUTH_ADDITIONAL_INFO = "google_auth_additional_info"
    const val ACCOUNT_BLOCKED = "account_blocked"
    const val PHONE_SIGNUP = "phone_signup"
    // … 나머지 생략
}
```

---

## 4. AuthDestinations — 타입 안전 빌더

**하지 말 것**: `"${Route.XXX}/$email"` 직접 조합.

```kotlin
object AuthDestinations {
    fun emailPasswordEntry(email: String) =
        "${Route.EMAIL_PASSWORD_ENTRY}/${RouteArgsCodec.encode(email)}"

    fun phoneSignup(email: String) =
        "${Route.PHONE_SIGNUP}/${RouteArgsCodec.encode(email)}"
    // …
}
```

---

## 5. AuthNavGraph

`composable(Route.WELCOME) { WelcomeScreen(navController) }` 형태로 등록. 인자 있는 라우트는 `navArgument` + `AuthDestinations.parse*` 패턴 유지.

---

## 6. 로그인 성공 후 네비 단일화 (PostSignInNavigation)

### 6.1 왜 필요한가

- `EmailLoginScreen`, `EmailPasswordEntryScreen`, `GoogleAuthLoadingScreen`에 **동일한 when 분기**가 있으면, 한쪽만 수정되어 **차단 계정·전화 미인증·홈** 분기가 어긋난다.
- **순수 함수**로 두면 **단위 테스트**로 표(table) 검증 가능.

### 6.2 패턴

- **`PostSignInNavContext`**
  - `WelcomeStack`: 이메일 로그인·비밀번호 입력 플로우. 성공 시 `popUpTo` 기준은 보통 `WELCOME`.
  - `GoogleAuthLoading`: 구글 로딩 화면. 차단·추가정보·전화는 `GOOGLE_AUTH_LOADING` 제거, **홈만** `WELCOME` 기준(happy_v12와 동일).
- **`resolvePostSignInDestination(result, context, emailFallbackForPhone)`**  
  - 입력: `AuthSessionResult`, 컨텍스트, 전화 단계 라우트에 쓸 이메일 폴백(화면 state 또는 ViewModel).
  - 출력: `PostSignInDestination.None` 또는 `Navigate(route, popUpToRoute, inclusive)`.
- **`NavController.applyPostSignInDestination(dest)`**  
  - 화면에서만 사용.

### 6.3 분기 순서 (happy_v12)

1. `!success` → `None` (화면에서 이동 없음).
2. `user.status` 정지/휴면 → `ACCOUNT_BLOCKED` + 컨텍스트별 `popUpTo`.
3. **구글 컨텍스트**이고 `isNewUser == true` → `GOOGLE_AUTH_ADDITIONAL_INFO` + `GOOGLE_AUTH_LOADING` pop.
4. 전화 없음 또는 `phoneVerified != true` → `AuthDestinations.phoneSignup(email)` + 컨텍스트별 `popUpTo`.
5. 그 외 → `HOME` + `popUpTo(WELCOME)`.

**WelcomeStack**에서는 `isNewUser`를 **무시**한다 (이메일 플로우와 구글 플로우 정책 분리).

### 6.4 화면 쪽 (요지)

```kotlin
LaunchedEffect(signInSuccess) {
    val result = signInSuccess ?: return@LaunchedEffect
    val nc = navController ?: return@LaunchedEffect
    val dest = resolvePostSignInDestination(
        result = result,
        context = PostSignInNavContext.WelcomeStack,
        emailFallbackForPhone = email.trim(), // 또는 viewModel.email
    )
    if (dest is PostSignInDestination.None) return@LaunchedEffect
    nc.applyPostSignInDestination(dest)
}
```

구글은 `clearLastAuthResult()` 호출 **시점**을 기존과 동일하게 유지(이중 네비 방지).

### 6.5 단위 테스트

`PostSignInNavigationTest`: 정지/휴면, 신규 구글, 전화 필요, 이메일 폴백, `isNewUser` 무시(웰컴 스택) 등 **케이스 표**로 추가.

**하지 말 것**: 이 규칙을 **또 다른 화면에 복붙**하지 말고, 같은 진입이 생기면 **함수 재사용** 또는 컨텍스트 추가.

---

## 7. AuthViewModel · performWelcomeCheck (웰컴 세션 검증)

이전 버전 가이드의 **과도 단순화된 의사코드**(예: `verifySession` 한 번만 호출)는 **프로덕션과 다를 수 있다**. happy_v12 기준 개념만 정리한다.

### 7.1 책임

- 앱 기동 시 `loadSession()`으로 로컬 토큰·유저 복원.
- `performWelcomeCheck()`:
  - 로컬에 세션 없으면 `null` → 웰컴 UI.
  - **`withTimeoutOrNull(SESSION_VERIFY_TIMEOUT_MS)`** 안에서 `checkAndRestoreSession()` 등으로 **서버와 동기화**.
  - **타임아웃(`null`)**: 로컬 세션 **유지**, `sessionVerifyTimedOut = true`, `null` 반환 → 웰컴에 **재시도 배너** (§8).
  - **검증 실패(만료 등)**: 로컬 세션 **삭제**, `null`.
  - **정상**: 정지/휴면 → `ACCOUNT_BLOCKED`; 가입·프로필 상태에 따라 `HOME` 또는 `null`(이어하기).

### 7.2 상태 필드 예시

```kotlin
data class AuthUiState(
    val isSessionLoaded: Boolean = false,
    val sessionToken: String? = null,
    val currentUser: SessionUserSnapshot? = null,
    val isCheckingSession: Boolean = false,
    val sessionVerifyTimedOut: Boolean = false,
    // …
)
```

**하지 말 것**: 타임아웃과 “네트워크 오류”를 **같은 처리**로 뭉개지 말 것. 제품 정책에 맞게 분리.

---

## 8. WelcomeScreen · 느린 네트워크 UX

### 8.1 재검사 트리거

```kotlin
var welcomeCheckGeneration by remember { mutableIntStateOf(0) }

LaunchedEffect(uiState.isSessionLoaded, welcomeCheckGeneration) {
    if (!uiState.isSessionLoaded || navController == null) return@LaunchedEffect
    val route = viewModel.performWelcomeCheck()
    if (route != null) {
        navController.navigate(route) { popUpTo(Route.WELCOME) { inclusive = true } }
    }
}
```

`performWelcomeCheck` **시작 시** `sessionVerifyTimedOut = false`로 초기화하면, 재시도 중 배너가 내려간다.

### 8.2 템플릿

- `showLoading = !isSessionLoaded || isCheckingSession`
- `sessionVerifyTimedOut`이 true이면 **안내 문구 + `common_retry`** → `welcomeCheckGeneration++`

문자열 리소스 예: `welcome_session_verify_timeout_message` (values / values-ko).

**하지 말 것**: 사용자 문구를 코드에 박지 말 것 (§0.2).

---

## 9. 제출 버튼·IME (선택) — ClearFocusWhenLoadingEffect

폼 제출 시 **로딩 전** `clearFocus()`만 호출하면 IME·인셋 변화와 탭이 겹칠 수 있다.

- **`ClearFocusWhenLoadingEffect(isLoading)`**: `LaunchedEffect(isLoading)`에서 `true`일 때 `yield()` 후 `clearFocus()`.
- 화면: `ClearFocusWhenLoadingEffect(uiState.isLoading)` + 버튼 `onClick`에서는 **ViewModel 액션만** 호출.

**적용 후보**: 이메일 입력, 로그인, 비밀번호, 닉네임, 추천인, 성별, 생일, 비번 재설정 완료 등 **텍스트 필드 + 주요 제출 버튼**이 있는 begin 화면.

**하지 말 것**: 검증만 하고 `isLoading`이 안 오르는 실패 경로까지 억지로 포커스 제거할 필요는 없음.

---

## 10. DTO · Domain 모델

### AuthResultDto / AuthSessionResult

API 필드명은 백엔드에 맞추되, 앱 내부는 **`AuthSessionResult` + `SessionUserSnapshot`** 같이 **도메인 타입**으로 통일.

- `success`, `user`, `isNewUser`, `accessToken` / `sessionToken`, `effectiveSessionToken` 등.
- `user.status`: 문자열 상수는 `DomainStatus.User` 등 **단일 소스**와 비교.

### SessionUser · 프로필 완료

- `isSignupComplete`, `hasNickname`, `hasGender`, `hasBirthDate` 등 **파생 프로퍼티**로 웰컴·온보딩 분기.

---

## 11. AuthRepository (인터페이스 개요)

`loadSession`, `verifySession`, `signInWithEmail`, `signInWithGoogle`, `checkEmailForSignup`, `saveUserField`, `clearLocalSession`, …  
**하지 말 것**: ViewModel에서 Retrofit/OkHttp **직접 호출** (경계는 Repository).

---

## 12. EmailEntryViewModel

이메일 존재 여부에 따라 `Existing` / `NewUser` 분기 → 화면에서 `AuthDestinations`로 이동. (기존 가이드 코드 참고 유지.)

---

## 13. SignupNicknameViewModel

debounce 후 `checkNicknameAvailability`, 저장 전 `Available` 강제 등 **UX 패턴 유지**.  
**하지 말 것**: 제출 시에만 형식 검증.

---

## 14. PhoneVerifySignupViewModel

OTP 쿨다운·재전송 카운트다운 패턴 유지.

---

## 15. 단순 필드 저장 (성별·생년월일 등)

`saveUserField` + `refreshSessionAfterSave` + `saveSuccess` → 화면 `LaunchedEffect`에서 다음 Route.

---

## 16. 화면 공통 레이아웃

온보딩 단계는 프로젝트 표준 스캐폴드(`AuthStepScaffold` 등) 사용.  
**happy-core**: `5_pages`에서 `Scaffold` 직접 남발 금지 등 — **대상 앱의 cursor/rules**를 따른다.

---

## 17. Repository 구현 요지

성공 시 DataStore(또는 Preferences)에 토큰·유저 저장, DTO → 도메인 변환, `Result` 실패는 `recover { throw }`로 삼키지 말 것(프로젝트 표준).

---

## 18. 신규 프로젝트 체크리스트

```
□ Route 단일 소스
□ AuthDestinations (encode/decode)
□ AuthNavGraph
□ PostSignInNavigation + 단위 테스트
□ AuthViewModel: loadSession, performWelcomeCheck, 타임아웃 정책·플래그
□ WelcomeScreen: 세션 로드 후 검증 + (선택) 타임아웃 배너·재시도
□ 이메일/구글 로그인 성공 → PostSignIn만 사용 (분기 복붙 금지)
□ (선택) ClearFocusWhenLoadingEffect on 폼 화면
□ 문자열 리소스로 사용자 메시지
□ 운영/개발 에러 노출 정책 (프로젝트 core 규칙)
```

---

## 19. 자주 하는 실수 요약표

| 잘못 | 올바름 |
|------|--------|
| 경로 문자열 하드코딩 | `Route` / `AuthDestinations` |
| 로그인 성공 분기 화면마다 복붙 | `resolvePostSignInDestination` |
| 웰컴 타임아웃 시 무조건 로그아웃 | 정책 문서화; happy_v12는 유지+재시도 |
| 제출 직전 즉시 `clearFocus()` | 로딩 후 이펙트(§9) |
| ViewModel에 `stringResource` | `getString` / UI에서만 |
| 단위 테스트 없이 분기만 추가 | `PostSignInNavigationTest` 표 |

---

## 부록 A. happy_v12에서 그대로 열어볼 소스 (복붙용)

긴 ViewModel/화면 전문은 이 문서에 두지 않고 **참조 프로젝트**를 연다.

| 주제 | 경로 (app 모듈 기준) |
|------|----------------------|
| 로그인 성공 네비 | `navigation/PostSignInNavigation.kt` |
| 네비 테스트 | `src/test/.../navigation/PostSignInNavigationTest.kt` |
| 웰컴 검증·타임아웃 | `controllers/begin/welcome/AuthViewModel.kt` (`performWelcomeCheck`) |
| 웰컴 UI | `ui/views/5_pages/begin/welcome/.../WelcomeScreen.kt`, `.../WelcomeTemplate.kt` |
| 타임아웃 배너 | `ui/views/3_organisms/begin/welcome/.../WelcomeSessionVerifyTimeoutBanner.kt` |
| 세션 타임아웃 상수 | `config/DomainLimits.kt` (`SESSION_VERIFY_TIMEOUT_MS`) |
| 포커스·로딩 | `ui/views/3_organisms/begin/common/begin_common_shared/ClearFocusWhenLoadingEffect.kt` |
| 이메일 로그인 화면 | `.../begin_email_4_login/EmailLoginScreen.kt` |
| 이메일+비번만 | `.../begin_email_5_password_entry/EmailPasswordEntryScreen.kt` |
| 구글 로딩 | `.../begin_google_1_loading/GoogleAuthLoadingScreen.kt` |

**하지 말 것**: 위 파일을 **이름만 바꿔 전부 복사**하지 말 것. Route·API·문자열은 대상 앱에 맞게 줄이거나 바꿀 것.

---

*원본 검증: `happy_v12`. 다른 앱에 옮길 때는 API·모듈 구조만 맞추고, **§0 금지·원칙**은 가능한 한 유지할 것.*
