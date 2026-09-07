# Part B — convert2video `ui/` 폴더 구조 전면 재편 세부 플랜

## Context

`downloaded-harness-claude-foamy-kite.md`의 Part B는 현재 화면 무관 없이 flat한 `ui/`(25개 파일) + `ui/theme/`(5개 파일, 컴포넌트 포함)를 `components/theme/shared/screens` 2~3계층으로 재편하는 것이 목표다. 이번 세션에서 실제 코드를 조사해 원본 문서의 목표 트리를 파일 단위로 검증하고, 스프린트 단위 실행 계획으로 구체화했다.

**조사 중 발견한 정정 사항**: 원본 문서는 `MediaDurationFormat`을 "Home/Record/RecordingsList 공용"이라고 적었지만, 실제 grep 결과 사용처는 `AudioPickScreen.kt` / `RecordScreen.kt` / `RecordingsListScreen.kt` 세 곳이며 **Home은 사용하지 않는다**. `ui/shared/`로 옮길 때 캐치업 대상에서 HomeScreen을 찾을 필요는 없다.

**차단 의존성 (중요)**: Part B의 모든 실제 코드 이동은 `/sprint-run` 하네스(`generator`/`evaluator`/`planner`/`plan_evaluator` 서브에이전트)를 통해서만 가능하다. **Part A #1 수정 전에는 실행 불가**였으나, 사용자는 Part A 완료를 확인했다.

사용자 확인 사항:
- 스프린트는 **3개**로 쪼갠다 (컴포넌트 분해 / shared 이동 / screens 재편).
- YouTube 업로드 UI는 **패키지 이동만** — `YouTubeUploadViewModel`/`YouTubeUploadUiState`만 `screens/youtube_upload/`로 옮기고, Composable은 `ConvertedVideosScreen.kt`에 그대로 둔다.

---

## 목표 구조 (검증 완료)

```
ui/
├── components/
│   ├── buttons/Buttons.kt      # AccentCtaButton, SoftChipButton, TopBarChipButton, RoundedIconButton, SoftIconButton
│   ├── badges/Badges.kt         # SectionStepBadge, SectionLabel, StatusBadge, AccentGlyphBadge
│   ├── cards/Cards.kt            # C2vCard
│   ├── controls/Controls.kt       # SegmentedControl, StepperControl, StepperGlyphButton(private), C2vSwitch
│   └── layout/Layout.kt             # DashedDropZone, dashedRoundRectBorder(private ext), GradientThumbnailPlaceholder, SegmentPreviewBar
├── theme/                    # Color.kt, Theme.kt, Type.kt, Shapes.kt (변경 없음 — C2vComponents.kt만 빠지면 자동 충족)
├── shared/
│   ├── ConversionUiState.kt
│   ├── MediaDurationFormat.kt   # AudioPick/Record/RecordingsList 공용 (Home 아님)
│   └── AppDrawerContent.kt      # MainActivity 앱 셸 — 화면 소속 아님
└── screens/
    ├── home/                  HomeScreen.kt, HomeViewModel.kt
    ├── audio_pick/             AudioPickScreen.kt, AudioPickViewModel.kt, AudioSourceFilter.kt, AudioSourceFilterMapping.kt
    ├── background_pick/         BackgroundPickScreen.kt, BackgroundPickViewModel.kt
    ├── converted_videos/         ConvertedVideosScreen.kt, ConvertedVideosViewModel.kt
    ├── error_log/                 ErrorLogScreen.kt, ErrorLogViewModel.kt
    ├── options/                    OptionsScreen.kt, OptionsViewModel.kt
    ├── record/                      RecordScreen.kt, RecordViewModel.kt, RecordUiState.kt
    ├── recordings_list/              RecordingsListScreen.kt, RecordingsListViewModel.kt, RecordingsListNavigation.kt, RenameValidation.kt
    └── youtube_upload/                YouTubeUploadViewModel.kt, YouTubeUploadUiState.kt (Composable은 converted_videos/ConvertedVideosScreen.kt에 물리적으로 잔류)
```

---

## Sprint 1 — 컴포넌트 분해 (이번 `/sprint-run` 범위)

**대상**: `ui/theme/C2vComponents.kt`(525줄, 18개 선언) → `ui/components/{buttons,badges,cards,controls,layout}/` 5개 파일로 분해, 원본 삭제.

- `SectionLabel`은 `badges/Badges.kt`에 두고 같은 파일의 `SectionStepBadge`를 호출한다 (layout↔badges 크로스 import 없음).
- `theme/` 폴더는 이 스프린트로 자연히 "Color/Theme/Type/Shapes만" 상태가 됨.

**Import 갱신 대상**: 16개 public 컴포넌트를 참조하는 모든 파일. planner가 16개 심볼명으로 grep해 확정.

**테스트 이동**: 없음.

## Sprint 2 — Shared 이동 (이후 별도 sprint-run)

`ui/ConversionUiState.kt`, `ui/MediaDurationFormat.kt`, `ui/AppDrawerContent.kt` → `ui/shared/` + 관련 테스트 미러 이동.

## Sprint 3 — Screens 재편 (이후 별도 sprint-run)

9개 화면 Screen/ViewModel/UiState → `ui/screens/<domain>/` + 테스트 미러. YouTube는 ViewModel/UiState만 이동.

---

## Verification

- 스프린트별: `compileDebugKotlin` + `run-stop-checks.ps1` PASS
- Sprint 3 완료 후: test/androidTest 전체 재실행, 문서 갱신 (`structure-kotlin.mdc`, `project-structure.md`)
