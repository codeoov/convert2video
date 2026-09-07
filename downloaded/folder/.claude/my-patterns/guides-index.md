# my-patterns 가이드 인덱스

> **AI(Claude Code, Cursor)를 위한 진입점 파일.**
> 신규 프로젝트 작업 시 이 파일을 먼저 읽고, 해당 기능 가이드를 추가로 열어라.
> 모든 가이드는 `happy_v12` 프로덕션 코드에서 검증된 패턴 기반.

---

## 현재 가이드 목록

| 파일 | 한 줄 설명 |
|------|-----------|
| `project-structure.md` | 전체 폴더·패키지 맵, 화면↔ViewModel 대응표, 핵심 규칙 빠른 참조 |
| `starter-template.md` | 신규 프로젝트 시작 시 세팅 순서, 레포 구조, 신규 프로젝트 체크리스트 |
| `auth-signup-impl-guide.md` | 회원가입·로그인·세션 체크·비밀번호 재설정 전체 플로우 |
| `network-layer-guide.md` | Retrofit 세팅, OkHttp 인터셉터(토큰 자동 첨부·갱신), 에러 처리 래퍼 |
| `list-pagination-guide.md` | LazyColumn, offset 페이지네이션, 로딩·에러·빈 상태 3중 분기 |
| `my-page-profile-guide.md` | 탭형 마이페이지 구조, 프로필 편집, Organism 조합 패턴 |
| `image-upload-guide.md` | 갤러리 선택(크롭 포함), 이미지 압축, Multipart 서버 업로드 |

---

## 시나리오별 안내 — 어떤 파일을 열면 되나

| 구현할 기능 | 열 파일 |
|------------|---------|
| 새 프로젝트 시작, 폴더 구조 잡기 | `starter-template.md` → `project-structure.md` |
| 로그인 / 회원가입 / 비밀번호 재설정 화면 | `auth-signup-impl-guide.md` |
| Retrofit 세팅, API 호출 패턴 | `network-layer-guide.md` |
| 목록 화면 (무한스크롤, 로딩/에러 처리) | `list-pagination-guide.md` |
| 홈 화면 (여러 섹션, 가로/세로 카드) | `list-pagination-guide.md` §10 (섹션형 홈) |
| 마이페이지 탭, 설정 메뉴 | `my-page-profile-guide.md` |
| 프로필 편집 화면 | `my-page-profile-guide.md` + `image-upload-guide.md` |
| 갤러리에서 사진 선택, 서버 업로드 | `image-upload-guide.md` |
| 여러 이미지 첨부 (게시물 등) | `image-upload-guide.md` §3 (헬퍼 3: 다중 이미지) |
| 새 도메인 API 추가 (Repository 만들기) | `network-layer-guide.md` §9 (체크리스트) |
| 에러 메시지, Snackbar 처리 | `network-layer-guide.md` §7 + `auth-signup-impl-guide.md` §17 |
| 검색 + 필터가 있는 목록 화면 | `list-pagination-guide.md` §11 (검색·필터 연동) |

---

## AI 사용 방법

### Claude Code에서
```
# 신규 프로젝트 시작 시
"@.claude/my-patterns/guides-index.md 와 starter-template.md 를 읽고 프로젝트를 세팅해줘"

# 특정 기능 구현 시
"@.claude/my-patterns/auth-signup-impl-guide.md 를 참고해서 이 프로젝트에 회원가입 플로우를 구현해줘"

# 여러 가이드가 필요할 때
"@.claude/my-patterns/my-page-profile-guide.md @.claude/my-patterns/image-upload-guide.md 를 참고해서 프로필 편집 화면을 만들어줘"
```

### Cursor에서
```
# 컨텍스트로 추가
@.claude/my-patterns/guides-index.md

# 또는 .cursorrules 에 참조 경로 명시
Always check .claude/my-patterns/ for implementation patterns before writing new code.
```

### CLAUDE.md에 추가하면 자동 참조
```markdown
## 구현 패턴 참조
신규 기능 구현 전 `.claude/my-patterns/guides-index.md`를 먼저 확인한다.
```

---

## 아직 없는 가이드 (미완성 항목)

AI가 아래 기능을 구현할 때는 가이드가 없으므로 `happy_v12` 소스를 직접 참고하거나 직접 판단한다.

| 기능 | 참고 파일 (happy_v12) | 상태 |
|------|--------------------|------|
| **DataStore + 세션 저장** | `data/local/SessionPreferences.kt`, `core/datastore/` | 가이드 없음 |
| **FCM 푸시 알림 + 딥링크** | `handlers/PushNotificationHandler.kt`, `controllers/common/DeepLinkViewModel.kt` | 가이드 없음 |
| **검색 + 최근 검색어** | `controllers/member/home/GatheringSearchViewModel.kt`, `data/local/GatheringSearchRecentStore.kt` | 가이드 없음 |
| **위시리스트 토글 (낙관적 업데이트)** | `controllers/member/home/GatheringDetailViewModel.kt` | 가이드 없음 |
| **채팅** | `controllers/member/chats/`, `controllers/host/chat/` | 가이드 없음 |
| **지도 위치 선택** | `ui/views/5_pages/common/GoogleMapPickerScreen.kt` | 가이드 없음 |

---

## 핵심 규칙 — 이것만은 모든 파일에서 공통

```
Route 상수        → config/Route.kt 만 사용 (하드코딩 금지)
색상              → MaterialTheme.colorScheme 경유
여백/크기          → AppSpacing, AppSizes 사용
문자열            → stringResource() / context.getString()
로딩              → AppLoadingIndicatorMolecule
로그              → AppLogger (Log.d/e 직접 금지)
에러              → ErrorDisplayHelper
Scaffold          → 5_pages에서 직접 사용 금지 → ScaffoldTemplate 경유
외부 라이브러리    → domain/contracts/ 래퍼 경유
금지 용어          → activity, meetup, reservation, my_info
```

---

*가이드 추가 시 이 파일의 목록과 시나리오 표를 함께 업데이트한다.*
