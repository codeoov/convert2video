# happy_v12 — 프로젝트 구조 레퍼런스

> **목적**: AI(Claude Code, Cursor)가 이 프로젝트를 빠르게 파악하기 위한 단일 참조 문서.
> 상세 규칙은 `.cursor/rules/` 참조.

---

## 1. 프로젝트 개요

인도네시아 대상 모임(Gathering) 플랫폼. 사용자 역할: **Member** (참가자) / **Host** (주최자) / **Admin** (운영자).
기술 스택: Kotlin + Jetpack Compose + MVVM + Hilt + Retrofit + DataStore + PHP/MySQL(백엔드).
아키텍처: Clean Architecture — Page → Template → Organism → ViewModel → Repository → RemoteApi.

---

## 2. 최상위 모듈 맵

```
app/           ← 모든 비즈니스 로직의 실체 (모놀리스). 실제 코드는 여기에 있다.
core/
  common/      ← 공통 유틸·DTOs·라우트 인코더
  datastore/   ← DataStore 래퍼
  model/       ← 공유 도메인 모델 (여러 모듈에서 참조)
  navigation/  ← 네비게이션 계약 인터페이스
  network/     ← 네트워크 레이어 계약
  ui/          ← 공유 테마·베이스 컴포저블
data/
  auth/        ← 인증 데이터 레이어 [스캐폴드, 미구현]
  chat/        ← 채팅 데이터 레이어 [스캐폴드, 미구현]
  admin/       ← 어드민 데이터 레이어 [스캐폴드, 미구현]
domain/
  auth/        ← 인증 유스케이스 [스캐폴드, 미구현]
  chat/        ← 채팅 유스케이스 [스캐폴드, 미구현]
  admin/       ← 어드민 유스케이스 [스캐폴드, 미구현]
feature/
  auth/        ← 인증 UI 피처 [스캐폴드, 미구현]
  chat/        ← 채팅 UI 피처 [스캐폴드, 미구현]
  admin/       ← 어드민 UI 피처 [스캐폴드, 미구현]
htdocs/        ← 백엔드 PHP/MySQL (읽기 전용 참고용)
scripts/       ← 금지 import·로그 검사 스크립트
.cursor/rules/ ← Cursor 상세 규칙 16개 파일
.claude/       ← Claude Code 설정·훅·패턴 문서
```

> **핵심**: `core/`, `data/`, `domain/`, `feature/` 모듈은 스캐폴드만 존재.
> 실제 동작하는 코드는 전부 `app/` 안에 있다.

---

## 3. app/ 패키지 맵

```
com/example/happy_v12/
│
├── config/                   ← 전역 상수
│   ├── Route.kt              ← 네비게이션 경로 상수 (하드코딩 금지, 반드시 여기서 참조)
│   ├── ApiActions.kt         ← API 액션 enum
│   ├── DomainStatus.kt       ← 도메인 상태 코드
│   ├── DomainLimits.kt       ← 비즈니스 룰 한도값
│   └── ChatConstants.kt      ← 채팅 관련 설정값
│
├── controllers/              ← ViewModel 전체 (도메인별 서브패키지)
│   ├── begin/
│   │   ├── welcome/          ← 로그인, 비밀번호 재설정, Google 인증
│   │   └── signup/           ← 회원가입 단계별 (이메일→전화→닉네임→성별→생년월일→추천코드)
│   ├── member/
│   │   ├── home/             ← 게더링 목록·상세·예약·위시리스트·검색·알림
│   │   ├── lounge/           ← 피드(소셜 포스트) 목록·작성
│   │   ├── chats/            ← 채팅방·채팅 목록
│   │   └── my_page/          ← 프로필·설정·분쟁 티켓·계정 복구
│   ├── host/
│   │   ├── create/           ← 게더링 생성 마법사
│   │   ├── home/             ← 호스트 대시보드 (내 게더링 목록)
│   │   ├── manage/           ← 예약 관리·QR 인증
│   │   ├── chat/             ← 호스트-멤버 메시지
│   │   ├── reviews/          ← 리뷰 조회
│   │   └── settlement/       ← 정산
│   ├── admin/                ← 어드민 대시보드
│   └── common/               ← 딥링크, 공용 프로필 조회
│
├── data/
│   ├── dto/                  ← API 요청·응답 DTO (auth, booking, chat, gathering 등 18개 서브패키지)
│   ├── remote/               ← Retrofit API 인터페이스
│   ├── repository/           ← Repository 구현체
│   ├── local/                ← DataStore·로컬 캐시 (GatheringSearchRecentStore 등)
│   └── adapters/             ← 외부 라이브러리 어댑터 구현
│
├── domain/
│   ├── model/                ← 도메인 엔티티
│   ├── enums/                ← 도메인 enum
│   ├── contracts/            ← 외부 라이브러리 인터페이스 래퍼 (직접 import 금지)
│   └── options/              ← 옵션 모델
│
├── navigation/               ← Jetpack Navigation 그래프
│   ├── AppNavHost.kt         ← 루트 NavHost
│   ├── AuthNavGraph.kt       ← 인증 플로우
│   ├── MemberNavGraph.kt     ← 멤버 플로우
│   ├── HostNavGraph.kt       ← 호스트 플로우
│   └── CommonNavGraph.kt     ← 공용 화면 (지도, 프로필 조회)
│
├── ui/
│   ├── theme/                ← 색상(AppColors), 여백(AppSpacing, AppSizes), 타이포, ScreenScale
│   ├── components/           ← 공용 컴포넌트 (AppLoadingIndicator, OfflineBanner 등)
│   └── views/                ← Atomic Design 5계층 (§4 참조)
│
├── di/                       ← Hilt 모듈 (NetworkModule 등)
├── handlers/                 ← 이벤트 핸들러 (PushNotificationHandler)
├── helpers/                  ← 이미지·에러 표시·외부 URL 헬퍼
└── utils/                    ← 로깅(AppLogger)·날짜·금액·유효성·QR·검색정책 유틸
```

---

## 4. Atomic Design 계층 (ui/views/)

| 계층 | 폴더 | 역할 | 주요 서브폴더 |
|------|------|------|--------------|
| **1_atoms** | 원자 UI 요소 | 버튼·텍스트·아이콘·입력 등 최소 단위 | 예시 `buttons/`, `texts/`, `icons/`, `textfields/`, `chips/`, `badges/`, `checkbox/`, `divider/`, `progressbar/`, `img_wrapper/` |
| **2_molecules** | 조합 컴포넌트 | 원자 2개 이상 조합 | 예시 `app_bars/`, `cards/`, `forms/`, `modals/`, `search/`, `chats/`, `comments/`, `displays/`, `feedback/`, `lists/`, `navigation/` |
| **3_organisms** | 도메인별 UI 블록 | 화면 섹션 단위. 도메인 폴더로 분리 | 예시 `member_1_home/`, `host_2_manage/`, `admin_1_home/`, `common/`, **`scaffolds/`**, `navigationbars/` |
| **4_templates** | 레이아웃 뼈대 | Scaffold 포함 레이아웃 구조 | 예시 `common/` (ScaffoldTemplate, FormTemplate), `member_1_home/routes/`, `member_2_discovery/`, `member_3_lounge/`, `member_4_chats/`, `member_5_mypage/`, `host_*/` |
| **5_pages** | 완성 화면 (~90개) | NavGraph에서 직접 호출되는 최상위 화면 | 예시 `begin_1_welcome/`, `member_1_home/routes/`, `host_*/`, `admin_1_home/`, `common/` |

**규칙**: `5_pages`에서 `Scaffold` 직접 사용 금지 → 반드시 `ScaffoldTemplate`, `FormTemplate`, `AuthStepScaffold` 경유.

### 3_organisms/scaffolds/ — 공통 구조 뼈대

> **언제 `scaffolds/`에 넣나**: 두 개 이상의 organism이 동일한 레이아웃 구조를 반복할 때, 그 구조를 여기로 추출한다.
> 도메인에 종속되지 않는 **재사용 가능한 organism 껍데기**다.

| 파일 | 역할 |
|------|------|
| `AuthStepScaffold` | 온보딩·회원가입 단계 화면 공통 레이아웃 (헤더+콘텐츠+하단버튼 구조) |
| `BackNextButtonsScaffold` | 뒤로/다음 버튼 쌍이 필요한 스텝 화면 공통 하단 영역 |
| `HorizontalCardListSectionScaffold` | "섹션 제목 + 가로 스크롤 카드 리스트" 반복 패턴 |
| `VerticalCardListSectionScaffold` | "섹션 제목 + 세로 카드 리스트" 반복 패턴 |
| `VerticalBigCardSectionScaffold` | "섹션 제목 + 세로 큰 카드 리스트" 반복 패턴 |
| `HorizontalSlotsScaffold` | 가로 슬롯 나열 구조 공통 뼈대 |

**적용 원칙**: 새 organism을 만들 때 `member_1_home/`, `host_*/` 등 도메인 폴더의 기존 scaffold를 먼저 확인하고, 구조가 같으면 새로 만들지 말고 재사용한다.

---

## 5. 화면 — ViewModel 대응표

### Auth / Begin

| 화면 설명 | Page 파일 | ViewModel |
|----------|-----------|-----------|
| 웰컴 (세션 체크) | `WelcomeScreen` | `AuthViewModel` |
| 이메일 입력 | `EmailEntryScreen` | `EmailEntryViewModel` |
| 이메일 로그인 | `EmailLoginScreen` | `EmailLoginViewModel` |
| 이메일+비밀번호 입력 | `EmailPasswordEntryScreen` | `EmailPasswordEntryViewModel` |
| 이메일 인증 코드 | `EmailVerificationScreen` | `EmailVerificationViewModel` |
| 이메일 토큰 검증 | `EmailVerifyTokenScreen` | `EmailVerifyTokenViewModel` |
| 비밀번호 설정 | `PasswordSetupScreen` | `PasswordSetupViewModel` |
| 비밀번호 재설정 요청 | `PasswordResetRequestScreen` | `PasswordResetRequestViewModel` |
| 비밀번호 재설정 검증 | `PasswordResetVerifyScreen` | `PasswordResetVerifyViewModel` |
| 비밀번호 재설정 완료 | `PasswordResetCompleteScreen` | `PasswordResetCompleteViewModel` |
| Google 추가 정보 | `GoogleAuthAdditionalInfoScreen` | `GoogleAuthAdditionalInfoViewModel` |
| Google 전화 검증 | `GoogleAuthPhoneVerifyScreen` | `GoogleAuthPhoneVerifyViewModel` |
| 회원가입 비밀번호 | `SignupPasswordScreen` | `SignupPasswordViewModel` |
| 회원가입 전화 입력 | `PhoneSignupScreen` | `PhoneSignupViewModel` |
| 회원가입 전화 검증 | `PhoneVerifySignupScreen` | `PhoneVerifySignupViewModel` |
| 회원가입 추천코드 | `SignupReferralScreen` | `SignupReferralViewModel` |
| 회원가입 성별 | `SignupGenderScreen` | `SignupGenderViewModel` |
| 회원가입 생년월일 | `SignupBirthdateScreen` | `SignupBirthdateViewModel` |
| 회원가입 닉네임 | `SignupNicknameScreen` | `SignupNicknameViewModel` |
| 계정 복구 요청 | `RestoreRequestScreen` | `RestoreRequestViewModel` |
| 계정 복구 확인 | `RestoreConfirmScreen` | `RestoreConfirmViewModel` |
| 계정 차단 안내 | `AccountBlockedScreen` | — |

### Member

| 화면 설명 | Page 파일 | ViewModel |
|----------|-----------|-----------|
| 홈 탭 (게더링 목록) | `Member1HomePage` | `HomeTabViewModel` |
| 게더링 상세 | `routes/GatheringDetailScreen` | `GatheringDetailViewModel` |
| 예약 티켓 | `routes/BookingTicketScreen` | `BookingTicketViewModel` |
| 리뷰 작성 | `routes/GatheringReviewWriteScreen` | `GatheringReviewWriteViewModel` |
| 게더링 검색 | `routes/GatheringSearchScreen` | `GatheringSearchViewModel` |
| 위시리스트 | `routes/GatheringWishlistScreen` | `GatheringWishlistViewModel` |
| 내 예약 목록 | `routes/MyBookingsScreen` | `MyBookingsViewModel` |
| 알림 | `routes/NotificationsScreen` | `NotificationsViewModel` |
| 추천 게더링 목록 | `routes/GatheringRecommendedScreen` | `GatheringRecommendedViewModel` |
| 섹션별 카드 더보기 | `routes/VerticalCardMoreScreen` | `VerticalCardMoreViewModel` |
| 라운지 피드 | `LoungeFeedScreen` | `LoungeFeedViewModel` / `LoungeViewModel` |
| 라운지 글쓰기 | `LoungeWriteScreen` | `LoungeWriteViewModel` |
| 채팅방 | `ChatRoomScreen` | `ChatRoomViewModel` |
| 채팅 목록 | `ChattingsTabContent` | `ChatListViewModel` |
| 프로필 수정 | `EditProfileScreen` | `EditProfileViewModel` |
| 이메일 변경 | `ChangeEmailScreen` | `ChangeEmailViewModel` |
| 전화번호 변경 | `ChangePhoneScreen` | `ChangePhoneViewModel` |
| 언어 설정 | `LanguageSettingsScreen` | `LanguageSettingsViewModel` |
| 국가/지역 설정 | `CountryRegionSettingsScreen` | `CountryRegionSettingsViewModel` |
| 기기 지문 설정 | `DeviceFingerprintSettingsScreen` | `DeviceFingerprintSettingsViewModel` |
| 로그인 기록 | `LoginHistoryScreen` | `LoginHistoryViewModel` |
| 개인정보 설정 | `PrivacySettingsScreen` | `PrivacySettingsViewModel` |
| 분쟁 티켓 생성 | `DisputeCreateTicketScreen` | `DisputeCreateTicketViewModel` |
| 내 분쟁 티켓 | `DisputeMyTicketsScreen` | `DisputeMyTicketsViewModel` |
| 친구 초대 | `FriendInviteScreen` | — |

### Host

| 화면 설명 | Page 파일 | ViewModel |
|----------|-----------|-----------|
| 호스트 온보딩 인트로 | `HostOnboardingIntroScreen` | — |
| 호스트 온보딩 입력 | `HostOnboardingFormScreen` | `HostOnboardingViewModel` |
| 호스트 온보딩 완료 | `HostOnboardingCompleteScreen` | — |
| 호스트 홈 (4탭) | `HostHomeScreen` | `HostGatheringViewModel` |
| 내 게더링 관리 | `ManageMyGatheringScreen` | `ManageMyGatheringViewModel` |
| 예약 관리 | `HostGatheringBookingsScreen` | `HostGatheringBookingsViewModel` |
| 예약 검증 (QR/수동) | `HostBookingVerifyScreen` | `HostBookingVerifyViewModel` |
| 예약 QR 스캔 | `HostBookingScanScreen` | — |
| 게더링 리뷰 조회 | `HostGatheringReviewsScreen` | `HostGatheringReviewsViewModel` |
| 게더링별 채팅 | `ChattingMyGatheringScreen` | `HostChatViewModel` |
| 정산 상세 | `HostSettlementDetailScreen` | `HostSettlementViewModel` |
| 게더링 만들기 | `CreateGatheringScreen` | `CreateGatheringViewModel` |

### Admin

| 화면 설명 | Page 파일 | ViewModel |
|----------|-----------|-----------|
| 어드민 대시보드 | `AdminDashboardScreen` | `AdminViewModel` |

### Common

| 화면 설명 | Page 파일 | ViewModel |
|----------|-----------|-----------|
| 지도 위치 선택 | `GoogleMapPickerScreen` | — (콜백 패턴) |
| 사용자 프로필 조회 | `ProfileViewScreen` | `ProfileViewViewModel` |
| 딥링크 처리 | — | `DeepLinkViewModel` |

---

## 6. 핵심 규칙 빠른 참조

| 항목 | 규칙 | 상세 |
|------|------|------|
| **라우팅** | `Route.XXX` 상수만 | `config/Route.kt` — 하드코딩 금지 |
| **색상** | `MaterialTheme.colorScheme` | 직접 Color() 금지 |
| **여백/크기** | `AppSpacing`, `AppSizes` | 직접 dp 숫자 금지 |
| **화면 스케일** | `scaleW()`, `scaleH()` | `ScreenScale.kt`, DesignSize=375×812 |
| **문자열** | `stringResource()` / `context.getString()` | 하드코딩 금지, 콜백 내부에서 stringResource 금지 |
| **로딩** | `AppLoadingIndicator` | `CircularProgressIndicator` 직접 금지 |
| **로그** | `AppLogger` | `Log.d/e` 직접 호출 금지 |
| **에러** | `ErrorDisplayHelper` | Dev(상세) / Prod(친화적) 자동 분기 |
| **Snackbar** | `onResult: (Boolean, String?) -> Unit` 콜백 | ViewModel에서 콜백 패턴 사용 |
| **Scaffold** | 5_pages에서 직접 사용 금지 | `ScaffoldTemplate`, `FormTemplate`, `AuthStepScaffold` 경유 |
| **외부 라이브러리** | `domain/contracts/` 래퍼 경유 | Retrofit/OkHttp/Gson 직접 import 금지 |
| **금지 용어** | `activity`, `meetup`, `reservation`, `my_info` | 클래스·변수·파일명에 사용 금지 |

---

## 7. 주요 유틸·헬퍼 빠른 참조

| 파일 | 용도 |
|------|------|
| `utils/AppLogger.kt` | 전용 로거 |
| `utils/DateUtils.kt` | 날짜 포맷 |
| `utils/AmountFormatUtils.kt` | 금액 포맷 |
| `utils/Validators.kt` | 입력 유효성 검사 |
| `utils/QrCodeUtils.kt` | QR 이미지 URL 생성 (`buildQrImageUrl()`) |
| `utils/GatheringSearchPolicy.kt` | 검색 정책 |
| `helpers/ErrorDisplayHelper.kt` | 에러 메시지 표시 |
| `helpers/ExternalUrlOpenHelper.kt` | 외부 URL 열기 |
| `helpers/ImageFromGalleryHelper.kt` | 갤러리 단일 이미지 선택 |
| `helpers/MultiImageFromGalleryHelper.kt` | 갤러리 다중 이미지 선택 |
| `helpers/PickImageCropHelper.kt` | 이미지 크롭 |
| `ui/theme/ScreenScale.kt` | 반응형 스케일 (`scaleW`, `scaleH`) |
| `ui/components/AppLoadingIndicator.kt` | 전용 로딩 인디케이터 |

---

*상세 규칙: `.cursor/rules/happy-core-kotlin.mdc` (핵심) + `rules-index-kotlin.mdc` (목차)*
