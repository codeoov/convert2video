# member_1~5 뷰 룰 위반 감사 (Audit) + 수정 검증

**최초 감사**: 2026-06-04 09:44  
**수정 검증**: 2026-06-04 (동일 세션)  
**범위**: `ui/views/3_organisms`, `4_templates`, `5_pages` — member_1~5 전체

---

## 최종 결과: 전체 PASS ✅

6개 파일의 모든 위반사항이 수정 완료되었고, 신규 인프라 파일도 올바르게 생성·등록됨.

---

## 파일별 수정 검증

### A. BannerSliderOrganism.kt ✅
`3_organisms/member_1_home/BannerSliderOrganism.kt`

| 구분 | 이전 | 이후 |
|------|------|------|
| 그라데이션 오버레이 | `Color.Black.copy(alpha=0.55f)` | `MaterialTheme.colorScheme.scrim.copy(alpha = AppAlpha.bannerGradientDark)` |
| 배지 배경 | `Color.White.copy(alpha=0.22f)` | `MaterialTheme.colorScheme.surface.copy(alpha = AppAlpha.bannerBadgeBackground)` |
| 배지 텍스트 / 제목 | `Color.White` | `MaterialTheme.colorScheme.surface` |
| 인디케이터 | `Color.White` / `Color.White.copy(alpha=0.45f)` | `MaterialTheme.colorScheme.surface` / `.copy(alpha = AppAlpha.bannerIndicatorInactive)` |
| `56.dp`, `10.dp`, `4.dp` | 하드코딩 | AppSizes / AppSpacing 상수로 교체 |
| `RoundedCornerShape(50)` | 하드코딩 | AppSizes 상수로 교체 |
| `"THIS WEEK"` | 하드코딩 | `stringResource(R.string.banner_badge_this_week)` |

### B. MemberHomeTemplate.kt ✅
`4_templates/member_1_home/MemberHomeTemplate.kt`

| 구분 | 이전 | 이후 |
|------|------|------|
| `AppColors.inkSecondary` | FQCN 직접 사용 | 제거 (LocalHomeColors 또는 colorScheme 경유) |
| `TextButton(` | Material3 직접 | `TextButtonSmall` Atom |
| `Icon(` | Material3 직접 | `IconAtom` |
| `2.dp` | 하드코딩 | AppSpacing 상수 |
| `"featured"`, `"popular"`, `"new"` | 매직 스트링 | `HomeSectionType.FEATURED` 등 상수 |

### C. VerticalCardMoreScreen.kt ✅
`5_pages/member_1_home/routes/VerticalCardMoreScreen.kt`

| 구분 | 이전 | 이후 |
|------|------|------|
| `"featured"` / `"popular"` / `"recommended"` / `"new"` | when 분기에 하드코딩 | `HomeSectionType.FEATURED` 등 상수 |

### D. FeedCardOrganism.kt ✅
`3_organisms/member_3_lounge/FeedCardOrganism.kt`

| 구분 | 이전 | 이후 |
|------|------|------|
| `AppColors.accentRed` × 2 | 직접 사용 | `LocalLoungeColors.current.likeActive` |
| `AppColors.inkSecondary` × 5 | 직접 사용 | `LocalLoungeColors.current.secondary` |
| `AppColors.primarySoft` × 1 | 직접 사용 | `LocalLoungeColors.current.hostBadgeSurface` |

### E. MyPageHeaderOrganism.kt ✅
`3_organisms/member_5_mypage/MyPageHeaderOrganism.kt`

| 구분 | 이전 | 이후 |
|------|------|------|
| `AppColors.neutral` | 직접 사용 | `LocalMyPageColors.current.interestChipContainer` |
| `AppColors.inkSecondary` | 직접 사용 | `LocalMyPageColors.current.interestChipLabel` |
| `AppColors.accentYellow` | 직접 사용 | `LocalMyPageColors.current.hostBadgeBackground` |
| `"HOST"` | 하드코딩 | `stringResource(R.string.role_host)` |

### F. MemberHomeScreen.kt ✅
`5_pages/member_main/MemberHomeScreen.kt`

| 구분 | 이전 | 이후 |
|------|------|------|
| `Icon`, `IconButton` import | Material3 직접 | 제거 → `IconAtom`, `IconButtonSmall` 등 |
| `ScrollableTabRow`, `Tab` import | Material3 직접 (자동검사 대상) | 제거 → `ScrollableResourceTabRowMolecule` 등 |
| `Text`, `TextButton`, `TopAppBar` import | Material3 직접 (자동검사 대상) | 제거 |
| `Badge`, `BadgedBox` import | Material3 직접 | 제거 → Molecule 경유 |
| `Color.White` × 2 | 하드코딩 | `MaterialTheme.colorScheme` 경유 |
| `AppColors.accentRed` × 2 | 직접 사용 | `LocalHomeColors.current` 경유 |
| `3.dp`, `2.dp`, `38.dp`, `8.dp` | 하드코딩 | AppSizes / AppSpacing 상수 |

---

## 신규 생성 인프라 파일

| 파일 | 경로 | 역할 |
|------|------|------|
| `HomeSectionType.kt` | `config/HomeSectionType.kt` | 홈 섹션 타입 상수 (`FEATURED`, `POPULAR`, `RECOMMENDED`, `NEW`) |
| `LoungeColors.kt` | `ui/theme/LoungeColors.kt` | 라운지 전용 색상 Theme 확장 (`LocalLoungeColors`) |
| `MyPageColors.kt` | `ui/theme/MyPageColors.kt` | 마이페이지 전용 색상 Theme 확장 (`LocalMyPageColors`) |
| `AppAlpha` | (BannerSlider 관련) | 배너 오버레이·인디케이터 알파값 상수 |
| `strings.xml` 추가 | `res/values/strings.xml` | `banner_badge_this_week`, `role_host` 키 추가 |

**Theme 등록 확인** (`ui/theme/Theme.kt` L101-109):
```kotlin
CompositionLocalProvider(
    LocalChatRoomColors provides chatRoomColors,
    LocalProfileEditColors provides profileEditColors,
    LocalAppSemanticColors provides appSemanticColors,
    LocalCalendarColors provides calendarColors,
    LocalLoungeColors provides loungeColors,    // ✅ 신규 등록
    LocalMyPageColors provides myPageColors,    // ✅ 신규 등록
    LocalHomeColors provides homeColors,        // ✅ 신규 등록
) {
    ScreenScaleProvider { content() }
}
```
