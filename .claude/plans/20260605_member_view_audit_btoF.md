# 감사 결과 §B~§F 잔존 위반 해소 기획서 (v3)

**날짜**: 2026-06-05
**스프린트 타입**: [Type B] — 코드 품질 개선 (규칙 위반 해소, 기능 추가 없음)

---

## [Improvement Architecture Report]

### 실제 잔존 위반 (코드베이스 현재 상태 기준)

plan_evaluator 전역 Grep으로 확인된 실제 위반 파일:

| 파일 | 위반 내용 | 줄 번호 |
|------|----------|---------|
| `ChatInputBarRoundedIconMolecule.kt` | `AppColors.neutral` × 2 (파라미터 기본값), `AppColors.inkSecondary` × 1 (파라미터 기본값) | L32, L65, L66 |
| `GatheringDetailTemplate.kt` | `AppColors.backgroundScreen` × 1 | L272 |

### ChatRoomInputOrganism.kt 호출부 분석 (이월 파일이지만 기본값 의존 명세 필요)

`ChatRoomInputOrganism.kt`는 `ChatInputBarRoundedIconButtonMolecule`을 두 곳에서 호출:

- **L180**: `backgroundColor`, `iconTint` 파라미터 미전달 → **기본값에 의존**
  - 기획서 수정 방식(Option A)에서: 기본값이 `Color.Unspecified`로 바뀌고, 내부에서 `takeOrElse { MaterialTheme.colorScheme.surfaceVariant }` 처리
  - `AppColors.neutral == LightColorScheme.surfaceVariant` (완전 동치) → **시각적 변화 없음, 호출부 수정 불필요**

- **L200, L249, L254**: `AppColors.neutral`, `AppColors.inkSecondary` **직접 전달** → 이월 대상 (별도 스프린트에서 처리)
  - 이 호출들은 기본값과 무관하게 명시 전달이므로, Molecule 파라미터 기본값 변경과 독립적
  - 기획서 수정 후에도 L200/L249/L254는 여전히 `AppColors`를 명시 전달하지만, 이는 **이월 범위의 별도 위반**이며 이번 스프린트 contract 밖

### 의도적 이월 파일 (이번 스프린트 제외)

| 파일 | 이월 근거 |
|------|----------|
| `ChatRoomInputOrganism.kt` L200/L249/L254 | AppColors 명시 전달 위반 — ChatRoomColors 슬롯 매핑 검토 필요, 채팅 도메인 별도 스프린트 |
| `ChatRoomBodyOrganism.kt` | 동일 사유 |
| `HostProfileViewBodyOrganism.kt` | HostColors 신규 Theme 확장 설계 필요 |

---

## 색상 매핑 전략

| AppColors 토큰 | 치환 방식 | 근거 |
|---------------|----------|------|
| `neutral` (파라미터 기본값) | `Color.Unspecified` → 내부 `takeOrElse { MaterialTheme.colorScheme.surfaceVariant }` | Composable 파라미터 기본값에 colorScheme 사용 불가 |
| `inkSecondary` (파라미터 기본값) | `Color.Unspecified` → 내부 `takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }` | 동일 |
| `backgroundScreen` (일반 사용) | `MaterialTheme.colorScheme.background` 직접 교체 | Composable 내부이므로 직접 접근 가능 |

---

## 수정 파일 목록 (2개, 신규 파일 없음)

### 파일 1: ChatInputBarRoundedIconMolecule.kt
`C:\Users\songw\AndroidStudioProjects\happy_v12\app\src\main\java\com\example\happy_v12\ui\views\2_molecules\chats\ChatInputBarRoundedIconMolecule.kt`

현재 코드 구조:
- `ChatInputBarRoundedIconContainerMolecule` — `backgroundColor: Color = AppColors.neutral` (L32)
- `ChatInputBarRoundedIconButtonMolecule` — `iconTint: Color = AppColors.inkSecondary` (L65), `backgroundColor: Color = AppColors.neutral` (L66)

변경 방식 (Option A):

`ChatInputBarRoundedIconContainerMolecule`:
```
backgroundColor: Color = Color.Unspecified  // 기본값 변경
// 내부에서:
val resolvedBg = backgroundColor.takeOrElse { MaterialTheme.colorScheme.surfaceVariant }
// background(color = resolvedBg, ...) 로 사용
```

`ChatInputBarRoundedIconButtonMolecule`:
```
iconTint: Color = Color.Unspecified         // 기본값 변경
backgroundColor: Color = Color.Unspecified  // 기본값 변경
// 내부에서:
val resolvedTint = iconTint.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }
// ChatInputBarRoundedIconContainerMolecule(backgroundColor = backgroundColor, ...) 는 그대로
// (Container 내부에서 Unspecified → surfaceVariant 처리함)
// Icon(tint = resolvedTint, ...)
```

추가: `MaterialTheme` import 추가, `AppColors` import 제거

**호출부 영향**:
- `ChatRoomInputOrganism.kt` L180 (파라미터 미전달): 기본값 `Color.Unspecified` → `surfaceVariant` 처리 → **시각적 변화 없음**
- `ChatRoomInputOrganism.kt` L236 (AppColors 명시 전달): 여전히 `AppColors.neutral` 명시 전달 → 이월 범위, 이번 스프린트 무관

### 파일 2: GatheringDetailTemplate.kt
`C:\Users\songw\AndroidStudioProjects\happy_v12\app\src\main\java\com\example\happy_v12\ui\views\4_templates\common\gathering_detail\GatheringDetailTemplate.kt`

변경 내용:
- L272: `AppColors.backgroundScreen` → `MaterialTheme.colorScheme.background`
- `import com.example.happy_v12.ui.theme.AppColors` 제거 (다른 AppColors 참조 없는지 확인 후)

---

## 완료 기준 (Done Criteria)

1. `ChatInputBarRoundedIconMolecule.kt` 에서 `AppColors.` 직접 참조 0건
2. `GatheringDetailTemplate.kt` 에서 `AppColors.` 직접 참조 0건
3. 두 파일에서 `import com.example.happy_v12.ui.theme.AppColors` 제거
4. `compileDebugKotlin` PASS (exit 0)
5. `run-stop-checks.ps1` PASS (exit 0)
6. `ChatRoomInputOrganism.kt` L180 호출부(기본값 의존): `backgroundColor`/`iconTint` 미전달 → 내부 `surfaceVariant`/`onSurfaceVariant` 처리로 시각적 동일성 유지

---

## 작업 로드맵

```
[Phase 1] ChatInputBarRoundedIconMolecule.kt + GatheringDetailTemplate.kt 동시 수정  직렬
```
