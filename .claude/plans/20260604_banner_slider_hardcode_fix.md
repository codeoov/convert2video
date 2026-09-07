# [Improvement Architecture Report]

## 구조적 배치 검토 결과

### 탐색 파일
- `ui/theme/AppAlpha.kt`, `AppSizes.kt`, `AppSpacing.kt`
- `3_organisms/member_1_home/BannerSliderOrganism.kt`
- `res/values/strings.xml`

### 발견된 기술 부채 및 배치 결정

1. **AppAlpha**: 배너 전용 alpha 값 3개 미정의 → `bannerGradientDark(0.55f)`, `bannerBadgeBackground(0.22f)`, `bannerIndicatorInactive(0.45f)` 배너 전용 블록으로 추가.
2. **AppSizes**: `RoundedCornerShape(50)` 패턴이 전역 16개 파일에 산재. 이번 스프린트는 BannerSliderOrganism 2곳만 `pillCornerPercent = 50`으로 치환, 나머지 15개 파일은 후속 스프린트로 분리(회귀 위험 격리).
3. **AppSizes 배너 블록**: `bannerBadgeBottomPad = 56.0`, `bannerBadgePaddingH = 10.0` 신규 추가. `4.dp`는 기존 `AppSpacing.listItemGap` 재사용.
4. **strings.xml**: `banner_badge_this_week` 신규 추가. 기존 `category_filter_date_this_week` / `filter_date_this_week`는 필터 도메인 용도 — 재사용 불가.

### 색상 치환 전략

| 현재 코드 | 치환 후 | 근거 |
|-----------|---------|------|
| `Color.Black.copy(alpha = 0.55f)` | `MaterialTheme.colorScheme.scrim.copy(alpha = AppAlpha.bannerGradientDark)` | M3 scrim = 어두운 오버레이 토큰 |
| `Color.White.copy(alpha = 0.22f)` | `MaterialTheme.colorScheme.surface.copy(alpha = AppAlpha.bannerBadgeBackground)` | surface = 밝은 계열 토큰 |
| `Color.White` (배지 텍스트·배너 제목) | `MaterialTheme.colorScheme.surface` | 동일 |
| `Color.White.copy(alpha = 0.45f)` | `MaterialTheme.colorScheme.surface.copy(alpha = AppAlpha.bannerIndicatorInactive)` | 비활성 인디케이터 |
| `Color.Transparent` (L135) | 유지 | M3 대응 토큰 없음 |

---

# 스프린트 기획서: BannerSliderOrganism 하드코딩 해소

**날짜**: 2026-06-04
**스프린트 타입**: [Type B] — 기존 파일 수정, 신규 파일 없음, 비즈니스 로직·화면 동작 변화 없음
**감사 원본**: `.claude/plans/20260604_0944_member_view_rule_audit.md` §A

> Type B 판정 근거: UI 규칙 준수 리팩토링. ViewModel/Repository/DTO 계층 무변경. 상수 파일 2개 + strings.xml 상수 추가, BannerSliderOrganism 치환만 수행.

---

## 수정 대상 파일 목록

| 순서 | 파일 경로 | 변경 유형 |
|------|-----------|-----------|
| 1 | `app/src/main/java/com/example/happy_v12/ui/theme/AppAlpha.kt` | 상수 3개 추가 |
| 2 | `app/src/main/java/com/example/happy_v12/ui/theme/AppSizes.kt` | 상수 3개 추가 |
| 3 | `app/src/main/res/values/strings.xml` | 문자열 1개 추가 |
| 4 | `app/src/main/java/com/example/happy_v12/ui/views/3_organisms/member_1_home/BannerSliderOrganism.kt` | 색상/dp/문자열 치환 |

---

## Phase 1: 테마 상수 추가

### 1-1. AppAlpha.kt — 상수 3개 추가

삽입 위치: 기존 `fullyTransparent` 상수 직전, 아래 블록 삽입.

```
// ── 배너 슬라이더 전용 ──────────────────────────────────────────────
/** 배너 하단 그라데이션 어두운 끝색 alpha (Color.Black 계열 오버레이) */
const val bannerGradientDark = 0.55f

/** 배너 배지("THIS WEEK" 등) 반투명 흰색 배경 alpha */
const val bannerBadgeBackground = 0.22f

/** 배너 페이저 인디케이터 미선택 상태 alpha */
const val bannerIndicatorInactive = 0.45f
```

### 1-2. AppSizes.kt — 상수 3개 추가

삽입 위치: 기존 `// ── 배너 ──` 블록 내부, `bannerIndicatorSelectedHeight` 아래.

```
/** Pill형 Surface/clip shape 퍼센트 (RoundedCornerShape(percent) 용, Int). */
const val pillCornerPercent = 50  // Int (profileAvatarCornerPercent 패턴 동일)

/** 배너 배지 하단 여백 (배지·제목 end offset, dp) */
const val bannerBadgeBottomPad = 56.0

/** 배너 배지 수평 내부 여백 (horizontal padding, dp) */
const val bannerBadgePaddingH = 10.0
```

> `pillCornerPercent`는 `Int` 타입. `RoundedCornerShape(AppSizes.pillCornerPercent)` 형태로 사용.
> BannerSliderOrganism 외 다른 파일의 `RoundedCornerShape(50)` 치환은 이번 스프린트 범위 밖.

---

## Phase 2: strings.xml 문자열 추가

삽입 위치: `banner_link_open_failed` 문자열 바로 아래, 아래 블록 삽입.

```xml
<!-- Banner slider badge labels -->
<string name="banner_badge_this_week">THIS WEEK</string>
```

> `category_filter_date_this_week` / `filter_date_this_week` 재사용 불가 사유: 필터 선택 UI 전용 의미 도메인 — 배너 배지와 별개.

---

## Phase 3: BannerSliderOrganism.kt 치환

파일 전체 경로: `app/src/main/java/com/example/happy_v12/ui/views/3_organisms/member_1_home/BannerSliderOrganism.kt`

### 3-1. import 변경

추가:
- `import androidx.compose.ui.res.stringResource`
- `import com.example.happy_v12.R`

제거 검토:
- `import androidx.compose.ui.graphics.Color` — 치환 후 `Color.Transparent`(L135)만 남으면 유지. 완전 제거 여부는 generator가 최종 확인 후 결정.

### 3-2. 각 라인별 치환 명세

| 라인 | 위반 유형 | Before | After |
|------|-----------|--------|-------|
| L136 | 색상 하드코딩 | `Color.Black.copy(alpha = 0.55f)` | `MaterialTheme.colorScheme.scrim.copy(alpha = AppAlpha.bannerGradientDark)` |
| L144 | dp 하드코딩 (Shape) | `shape = RoundedCornerShape(50)` | `shape = RoundedCornerShape(AppSizes.pillCornerPercent)` |
| L145 | 색상 하드코딩 | `color = Color.White.copy(alpha = 0.22f)` | `color = MaterialTheme.colorScheme.surface.copy(alpha = AppAlpha.bannerBadgeBackground)` |
| L148 | dp 하드코딩 | `bottom = 56.dp` | `bottom = AppSizes.bannerBadgeBottomPad.dp` |
| L151 | 문자열 하드코딩 | `text = "THIS WEEK"` | `text = stringResource(R.string.banner_badge_this_week)` |
| L154 | 색상 하드코딩 | `color = Color.White` | `color = MaterialTheme.colorScheme.surface` |
| L156 | dp 하드코딩 | `horizontal = 10.dp, vertical = 4.dp` | `horizontal = AppSizes.bannerBadgePaddingH.dp, vertical = AppSpacing.listItemGap.dp` |
| L166 | dp 하드코딩 | `end = 56.dp` | `end = AppSizes.bannerBadgeBottomPad.dp` |
| L173 | 색상 하드코딩 | `color = Color.White` (Text3 파라미터) | `color = MaterialTheme.colorScheme.surface` |
| L183 | dp 하드코딩 | `Arrangement.spacedBy(4.dp)` | `Arrangement.spacedBy(AppSpacing.listItemGap.dp)` |
| L189 | dp 하드코딩 (Shape) | `shape = RoundedCornerShape(50)` | `shape = RoundedCornerShape(AppSizes.pillCornerPercent)` |
| L190 | 색상 하드코딩 | `Color.White` (선택 인디케이터) | `MaterialTheme.colorScheme.surface` |
| L191 | 색상 하드코딩 | `Color.White.copy(alpha = 0.45f)` | `MaterialTheme.colorScheme.surface.copy(alpha = AppAlpha.bannerIndicatorInactive)` |

---

## 완료 기준 (Done Criteria)

1. `BannerSliderOrganism.kt` 내 `Color.Black`, `Color.White`, `Color.White.copy(...)` 참조 0건 (`Color.Transparent` 허용).
2. `BannerSliderOrganism.kt` 내 `56.dp`, `10.dp`, `4.dp` 직접 사용 0건.
3. `BannerSliderOrganism.kt` 내 `RoundedCornerShape(50)` 직접 사용 0건.
4. `BannerSliderOrganism.kt` 내 하드코딩 문자열 리터럴 0건 (`"THIS WEEK"` 포함).
5. `AppAlpha.kt`에 `bannerGradientDark`, `bannerBadgeBackground`, `bannerIndicatorInactive` 3개 추가 확인.
6. `AppSizes.kt`에 `pillCornerPercent(Int)`, `bannerBadgeBottomPad`, `bannerBadgePaddingH` 3개 추가 확인.
7. `strings.xml`에 `banner_badge_this_week` 추가 확인.
8. `gradlew :app:compileDebugKotlin` PASS (컴파일 오류 0건).
9. 시각적 회귀 없음: 배너 슬라이더의 그라데이션·배지·제목·인디케이터가 치환 전과 동일하게 렌더링.

---

## 범위 외 (이번 스프린트 금지)

- 다른 16개 파일의 `RoundedCornerShape(50)` 치환 (후속 스프린트).
- `MemberHomeTemplate`, `FeedCardOrganism`, `MyPageHeaderOrganism` 등 감사 §B~§F 항목.
- ViewModel / Repository / DTO 계층 변경 없음.

---

## 작업 로드맵 및 실행 모드

```
[Phase 1] AppAlpha + AppSizes 상수 추가  직렬
[Phase 2] strings.xml 문자열 추가        [Phase 1]과 병렬 가능
[Phase 3] BannerSliderOrganism.kt 치환   [Phase 1][Phase 2] 완료 후 직렬
```

### 구현 Notes (generator용)
- `pillCornerPercent`는 `Int` 타입으로 선언. `AppSizes.pillCornerPercent`를 `RoundedCornerShape()` 인자로 직접 전달.
- `Color.Transparent`는 유지(L135). import 제거 여부는 generator가 컴파일 후 확인.
- `4.dp` 두 곳(인디케이터 간격 + 배지 vertical padding)은 기존 `AppSpacing.listItemGap = 4.0`으로 재사용 (신규 상수 불필요).
