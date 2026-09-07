# 신규 프로젝트 스타터 템플릿 — 블루프린트

> **목적**: 새 프로젝트 시작 시 이 문서를 보고 구조·규칙·UI 컴포넌트를 빠르게 세팅한다.
> `happy_v12`에서 검증된 패턴을 추출한 범용 레퍼런스.

---

## 1. 레포(Repository) 전략

### 신규 프로젝트마다 할 것

```
GitHub에서 새 레포 생성
  → happy-starter 레포를 "Use this template" 클릭
  → 새 레포 이름 지정 (예: project-name-android)
  → clone 후 패키지명·앱이름·색상만 교체
  → 즉시 개발 시작
```

### happy-starter 레포 구성 (한 번만 만들면 됨)

```
happy-starter/
├── android/                ← Kotlin + Compose 앱 뼈대
├── ios/                    ← SwiftUI 뼈대 (나중에 추가)
├── .cursor/rules/          ← 범용 AI 규칙 5개
├── .claude/settings.json   ← Stop 훅 (자동 컴파일)
├── CLAUDE.md               ← AI에게 주는 프로젝트 지시사항
└── setup.md                ← 신규 프로젝트 시작 체크리스트
```

---

## 2. Android 폴더 구조 (Atomic Design + MVVM)

```
android/app/src/main/java/com/starter/app/
│
├── config/
│   ├── Route.kt            ← 화면 경로 상수 (하드코딩 금지, 여기서만 정의)
│   ├── ApiActions.kt       ← API 액션 상수
│   ├── DomainStatus.kt     ← 상태 코드 상수
│   └── DomainLimits.kt     ← 비즈니스 한도값 상수
│
├── ui/
│   ├── theme/              ← 디자인 토큰 (아래 §3 참고)
│   └── views/              ← Atomic Design 5계층 (아래 §4 참고)
│
├── controllers/            ← ViewModel (도메인별 서브패키지)
├── data/
│   ├── dto/                ← API 요청·응답 DTO
│   ├── remote/             ← Retrofit 인터페이스
│   ├── repository/         ← Repository 구현체
│   └── local/              ← DataStore·로컬 캐시
├── domain/
│   ├── model/              ← 도메인 엔티티
│   ├── contracts/          ← 외부 라이브러리 래퍼 (직접 import 금지)
│   └── enums/
├── navigation/             ← AppNavHost, 도메인별 NavGraph
├── di/                     ← Hilt 모듈
├── helpers/                ← 이미지·에러·URL 헬퍼
└── utils/                  ← AppLogger, DateUtils, Validators 등
```

---

## 3. Theme 시스템 (디자인 토큰)

| 파일 | 역할 | 규칙 |
|------|------|------|
| `AppSpacing.kt` | 여백 상수 (xs~xxl) | dp 직접 숫자 금지 |
| `AppSizes.kt` | 컴포넌트 크기 상수 | dp 직접 숫자 금지 |
| `AppColors.kt` | 기본 팔레트 | `MaterialTheme.colorScheme` 경유 |
| `AppAlpha.kt` | 투명도 상수 | |
| `AppFontSizes.kt` | 폰트 크기 상수 | |
| `ScreenScale.kt` | 반응형 스케일 | 기준: 375×812dp (iPhone X 비율) |
| `Type.kt` | 타이포그래피 정의 | `MaterialTheme.typography` 경유 |

```kotlin
// 사용 예시
Modifier.padding(AppSpacing.md.dp)     // ✅
Modifier.padding(16.dp)                // ❌ 직접 숫자 금지

MaterialTheme.colorScheme.primary      // ✅
Color(0xFF0099FF)                      // ❌ 하드코딩 금지
```

---

## 4. Atomic Design 5계층

| 계층 | 폴더 | 역할 |
|------|------|------|
| **1_atoms** | 원자 단위 UI | 버튼·텍스트·아이콘·입력 등. ViewModel 참조 금지. |
| **2_molecules** | 원자 조합 | 2개 이상 atom 조합. DTO 표시 가능, 비즈니스 로직 금지. |
| **3_organisms** | 도메인 UI 블록 | 화면 섹션 단위. 도메인별 서브폴더로 분리. |
| **4_templates** | 레이아웃 뼈대 | `Scaffold` 포함. `ScaffoldTemplate`, `FormTemplate` 등. |
| **5_pages** | 완성 화면 | NavGraph에서 직접 호출. `Scaffold` 직접 사용 금지. |

### 1_atoms 구성 (happy_v12에서 검증된 40개)

```
1_atoms/
├── buttons/    PrimaryButton{Small|Medium|Large}, SecondaryButton*, TextButton*, IconButton*, PillOutlineButtonLarge
├── texts/      Text1(32sp) ~ Text8(최소) — MaterialTheme.typography 기반
├── chips/      FilterChip*, AssistChip*, InputChip* (각 Small|Medium|Large)
├── icons/      IconAtom, AnimatedIconAtom
├── textfields/ TextFieldAtom
├── checkbox/   CheckboxAtom, SwitchAtom
├── divider/    HorizontalDividerAtom, VerticalDividerAtom
├── badges/     CountBadgeAtom
├── progressbar/ CircularProgressAtom, LinearProgressAtom
└── img_wrapper/ ImageWrapperAtom
```

### 2_molecules 범용 구성 (happy_v12에서 검증된 ~20개)

```
2_molecules/
├── feedback/    AppLoadingIndicatorMolecule, EmptyStateViewMolecule, ErrorRetryBlockMolecule
├── forms/       SearchBarMolecule, RichTextInputMolecule, CountryCodeSelectorMolecule, FormInlineErrorMolecule
├── lists/       SectionContainerMolecule, SelectableSettingRowMolecule, MenuItemRowMolecule, NotificationListItemMolecule
├── navigation/  ScrollableResourceTabRowMolecule, ScrollableChipFilterTabRowMolecule
├── modals/      ConfirmDialogMolecule
├── app_bars/    BackIconButtonMolecule
├── displays/    ProfileAvatarMolecule, PriceTextMolecule, QrCodeImageMolecule, RichTextDisplayMolecule
└── cards/       GenericCardMolecule (DTO 없는 범용 버전 1개)
```

### 3_organisms 이후 — 프로젝트마다 새로 작성

organisms부터는 도메인 종속. 신규 프로젝트에서 직접 구현.
atoms/molecules를 조합해서 만든다.

---

## 5. 핵심 규칙 요약

| 항목 | 규칙 |
|------|------|
| 라우팅 | `Route.XXX` 상수만. 문자열 하드코딩 금지. |
| 색상 | `MaterialTheme.colorScheme` 경유. `Color()` 직접 금지. |
| 여백/크기 | `AppSpacing`, `AppSizes` 사용. dp 숫자 직접 금지. |
| 화면 스케일 | `scaleW()`, `scaleH()` — ScreenScale.kt 경유. |
| 문자열 | Composable: `stringResource()` / VM: `context.getString()`. 콜백 내부 `stringResource` 금지. |
| 로딩 | `AppLoadingIndicatorMolecule` 전용. `CircularProgressIndicator` 직접 금지. |
| 로그 | `AppLogger` 전용. `Log.d/e` 직접 호출 금지. |
| 에러 | `ErrorDisplayHelper` — Dev(상세)/Prod(친화적) 자동 분기. |
| Snackbar | `onResult: (Boolean, String?) -> Unit` 콜백 패턴. |
| Scaffold | `5_pages`에서 직접 사용 금지 → `ScaffoldTemplate`, `FormTemplate` 경유. |
| 외부 라이브러리 | `domain/contracts/` 래퍼 경유. Retrofit/OkHttp 직접 import 금지. |
| 금지 용어 | `activity`, `meetup`, `reservation`, `my_info` — 클래스·파일명 사용 금지. |

---

## 6. 신규 프로젝트 시작 체크리스트

```
□ 1. happy-starter 레포에서 "Use this template" → 새 레포 생성
□ 2. 패키지명 전체 교체: com.starter.app → com.yourcompany.projectname
□ 3. AppColors.kt — 브랜드 컬러 5개 교체 (primary, secondary, background 등)
□ 4. Type.kt — 브랜드 폰트 교체 (필요시)
□ 5. Route.kt — 첫 화면 경로 추가
□ 6. AppNavHost.kt — 첫 NavGraph 연결
□ 7. config/ApiActions.kt — 백엔드 API 엔드포인트 추가
□ 8. gradle.properties — 앱 이름, applicationId 변경
□ 9. .cursor/rules/ — 프로젝트 특화 phase rules 추가
□ 10. CLAUDE.md — 프로젝트 개요 업데이트
□ 11. ./gradlew :app:compileDebugKotlin 성공 확인
```

---

## 7. iOS 대응 구조 (나중에 추가)

Android와 동일한 계층·네이밍 사용.

```
ios/Starter/UI/
├── Theme/
│   ├── AppSpacing.swift    ← 동일 상수값 (xs=8, sm=12, md=16...)
│   ├── AppSizes.swift      ← 동일 상수값
│   └── ScreenScale.swift   ← GeometryReader 기반 scaleW/scaleH
├── 1_Atoms/
│   ├── Buttons/            PrimaryButtonLarge.swift 등
│   ├── Texts/              Text1.swift ~ Text8.swift
│   └── Icons/              IconView.swift
├── 2_Molecules/            (.gitkeep — Android molecules 참고해서 구현)
├── 3_Organisms/            (.gitkeep)
└── 4_Views/                (.gitkeep)
```

네이밍 대응:
- `PrimaryButtonMedium.kt` → `PrimaryButtonMedium.swift`
- `Text1.kt` (headlineLarge/32sp) → `Text1.swift` (font(.largeTitle))
- `AppSpacing.md` → `AppSpacing.md` (동일 이름, Swift enum으로)

---

## 8. 주요 유틸·헬퍼 (모든 프로젝트 공통)

| 파일 | 역할 |
|------|------|
| `utils/AppLogger.kt` | 전용 로거 |
| `utils/DateUtils.kt` | 날짜 포맷 |
| `utils/AmountFormatUtils.kt` | 금액 포맷 |
| `utils/Validators.kt` | 입력 유효성 검사 |
| `helpers/ErrorDisplayHelper.kt` | 에러 메시지 표시 |
| `helpers/ExternalUrlOpenHelper.kt` | 외부 URL 열기 |
| `helpers/ImageFromGalleryHelper.kt` | 갤러리 단일 이미지 |
| `helpers/MultiImageFromGalleryHelper.kt` | 갤러리 다중 이미지 |
| `ui/theme/ScreenScale.kt` | 반응형 스케일 |

---

*원본 검증 프로젝트: `happy_v12` — 이 문서의 모든 패턴은 해당 프로젝트에서 실사용 검증됨.*
