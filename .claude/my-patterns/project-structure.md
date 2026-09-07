# convert2video — 프로젝트 구조 레퍼런스

> **목적**: AI(Claude Code, Cursor)가 이 프로젝트를 빠르게 파악하기 위한 단일 참조 문서.  
> 상세 규칙은 `.cursor/rules/` (`structure-kotlin.mdc`, `convert2video-core.mdc`) 참조.

---

## 1. 프로젝트 개요

로컬 **오디오 → 배경 이미지 + 영상(video)** 변환 앱. 서버 API·Hilt·Retrofit 없음.
기술 스택: Kotlin + Jetpack Compose + MVVM + StateFlow + Room + Coil + Media3 Transformer + WorkManager.
아키텍처: Screen → ViewModel → Repository → Room/MediaStore/WorkManager.

---

## 2. 최상위 모듈 맵

```
app/                 ← 단일 앱 모듈 (모놀리스)
  src/main/java/…    ← 프로덕션 코드
  src/test/          ← unit (main 패키지 미러)
  src/androidTest/   ← instrumented (main 패키지 미러)
scripts/             ← 금지 import·로그·용어 검사
.cursor/rules/       ← Cursor 규칙 (구조·용어·Harness)
.claude/             ← 패턴·훅·기획
docs/                ← 스프린트/백로그 문서
```

---

## 3. app/ 패키지 맵

```
com.example.convert2video/
├── MainActivity.kt              # AppDestination 라우팅
├── Convert2videoApplication.kt  # AppLogger persist sink
├── data/                        # Room · Repository · MediaStore
├── ui/
│   ├── screens/<domain>/        # Screen · ViewModel · 도메인 UiState
│   ├── shared/                  # ConversionUiState · MediaDurationFormat · AppDrawerContent
│   ├── components/              # buttons · badges · cards · controls · layout
│   └── theme/                   # Color · Theme · Type · Shapes (토큰만)
├── record/                      # 마이크 엔진 · Service · Controller (≠ ui.screens.record)
├── video/                       # Transformer · ConversionWorker · MediaStoreSaver
└── utils/                       # AppLogger
```

상세 트리: `CLAUDE.md` § Folder Structure 참고.

---

## 4. 계층 테이블

| 계층 | 역할 |
|------|------|
| `ui/screens/<domain>/` | 화면·ViewModel. 패키지 `ui.screens.<domain>` |
| `ui/shared/` | Drawer·ConversionUiState·duration 포맷 |
| `ui/components/` | 공통 Compose 위젯 (`theme`에 두지 않음) |
| `ui/theme/` | 색·타이포·Shape 토큰만 |
| `data/` | Room Entity/Dao + Repository |
| `video/` | 변환·갤러리 저장·WorkManager |
| `record/` | 녹음 엔진·포그라운드 Service |
| `utils/` | AppLogger |

---

## 5. 화면 ↔ ViewModel 대응표

| 도메인 (`ui/screens/…`) | Screen | ViewModel / 비고 |
|-------------------------|--------|------------------|
| `home/` | HomeScreen | HomeViewModel (`toConversionUiState` SSOT) |
| `audio_pick/` | AudioPickScreen | AudioPickViewModel, AudioSourceFilter* |
| `background_pick/` | BackgroundPickScreen | BackgroundPickViewModel |
| `converted_videos/` | ConvertedVideosScreen | ConvertedVideosViewModel (+ YouTube Composable 잔류) |
| `youtube_upload/` | — (Composable 없음) | YouTubeUploadViewModel, YouTubeUploadUiState |
| `error_log/` | ErrorLogScreen | ErrorLogViewModel |
| `options/` | OptionsScreen | OptionsViewModel |
| `record/` | RecordScreen | RecordViewModel, RecordUiState |
| `recordings_list/` | RecordingsListScreen | RecordingsListViewModel, Navigation, RenameValidation |

공유: `ui/shared/AppDrawerContent` ← MainActivity.

---

## 6. 핵심 규칙 표

| 규칙 | 내용 |
|------|------|
| 패키지 | `com.example.convert2video`만. happy_v12/모임 앱 이식 금지 |
| 용어 | `background` · `conversion` · `video` · `audio` |
| 변환 | Media3 Transformer는 Worker/메인 Looper. UI에서 장시간 변환 금지 |
| 녹음 | UI → `RecordingController`만. `ui.screens.record` ≠ `record/` 엔진 |
| 이름 검증 | `data/DisplayNameValidation` SSOT |
| ConversionUiState | 타입은 `ui/shared`, 매핑은 `HomeViewModel` |
| DI | Hilt 없음. `AppDatabase.getInstance` 등 수동 |
| 새 화면 | `ui/screens/<domain>/` |
| 공통 위젯 | `ui/components/…` (`theme` 금지) |

---

## 7. 유틸 표

| 파일 | 역할 |
|------|------|
| `utils/AppLogger.kt` | e/w/d + ErrorLog persist sink |
| `scripts/check-forbidden-imports.ps1` | 금지 import |
| `scripts/check-logging-forbidden.ps1` | `Log.*` 직접 호출 금지 |
| `scripts/check-terminology-forbidden.ps1` | 금지 용어 |
| `.cursor/hooks/run-stop-checks.ps1` | 위 검사 일괄 |

---

## 4. 한 줄 요약

> data(Room) → ViewModel → UI. 변환은 video/ + WorkManager. 녹음은 record/ + RecordingController. 화면은 `ui/screens/<domain>/`.
