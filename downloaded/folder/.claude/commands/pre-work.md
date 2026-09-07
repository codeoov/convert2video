# /pre-work — 작업 전 계획 수립

이 커맨드를 실행하면 아래 순서로 진행한다. 코딩 시작 전에 반드시 실행.

---

## 1. Rules 파일 확인

`rules-index-kotlin.mdc` 목차에서 이번 작업에 해당하는 파일을 결정:

- UI/화면 작업 → `atomic-design-kotlin.mdc`
- 신규 화면·도메인 → `project-scope-kotlin.mdc` + 해당 `phase*.mdc`
- 용어·DTO 추가 → `terminology-glossary.mdc`
- Route·l10n·단일 소스 → `do-not-repeat-kotlin.mdc`

## 2. 기존 코드 읽기

수정 예정 파일과 주변 관련 파일을 코드 작성 전에 열어서 읽는다.
"이미 어떻게 돼 있는지" 모르면 계획을 세울 수 없다.

## 3. 중복 방지 확인

`do-not-repeat-kotlin.mdc` 기준으로 이미 있는 유틸·헬퍼·단일 소스 없는지 확인:
- Route, AppSpacing, AppSizes, ErrorDisplayHelper, GeocodingUtils, QrCodeUtils 등

## 4. 계획 출력 (이 형식으로)

```
수정 파일 목록:
  -

생성 파일 목록:
  -

구현 순서: DTO → Repository → ViewModel → Screen

재사용할 기존 유틸:
  -

Route 상수 필요: yes / no (yes면 Route.kt 상수 있는지 확인)
신규 용어·DTO: yes / no (yes면 terminology-glossary 등록 필요)
예상 이슈:
  -
```

## 4.1 View 경계 규칙 (Phase 0 확정안)

View(`ui/views/**`, 특히 `1_atoms`~`5_pages`)는 **표시 + 이벤트 전달**만 담당한다.

- **View 금지 로직 범위**
  - URL 조립, 주소/문자열 정규화, 날짜 파싱/비교/탐색, diff 비교, 비즈니스 분기.
  - DTO/Domain -> ScreenState의 대규모 변환(`toXxxScreenState`류).
  - 네비 경로 문자열 하드코딩 (`Route` 상수 미사용).
- **View 허용 범위(최소)**
  - 단순 표시용 포맷(아주 짧은 텍스트 형태 변환).
  - `onClick`, `onRetry`, `onNavigateXxx` 같은 UI 이벤트 콜백 전달.
  - 템플릿/유기체 조립(배치/순서/슬롯).
- **판단 기준**
  - "같은 로직을 다른 화면에서도 재사용할 수 있나?" -> yes면 View 밖으로 이동.
  - "테스트가 필요할 정도의 조건/분기/파싱이 있나?" -> yes면 View 밖으로 이동.
  - "표시 결과가 locale/timezone/domain 규칙에 영향 받나?" -> yes면 View 밖으로 이동.

## 4.2 Mapper 위치·네이밍 (Phase 0 확정안)

- **5_pages 매핑**
  - 위치: `ui/views/5_pages/<domain>/...`
  - 이름: `<ScreenName>StateMapper.kt` 또는 `<FeatureName>UiMapper.kt`
  - 역할: ViewModel `UiState` -> `ui/views/state/*ScreenState` 매핑 전용.
- **도메인 변환/비교/정규화**
  - 위치 우선순위: `domain/usecase/` -> `utils/`(순수 함수일 때만).
  - 이름: `resolve*`, `build*`, `normalize*`, `diff*`는 View 파일 밖에 둔다.
- **중복 함수**
  - 같은 역할 함수가 2곳 이상이면 단일 소스로 통합 후 호출만 남긴다.
  - 예: 지역 라벨 변환, 날짜 파싱, maps query 조립.

## 4.3 검증 체크리스트 (Phase 0 확정안)

작업 종료 전 아래 순서를 고정한다.

1. `./gradlew :app:compileDebugKotlin`
2. (선택) `./gradlew :app:lintDebug`
3. 핵심 화면 스모크 체크
   - Home/Discovery/Lounge/Chats/MyPage
   - Gathering Detail / Booking Ticket
   - Host Manage / Host Detail
4. 셀프체크
   - 용어: gathering / booking / session 유지
   - Route 하드코딩 없음 (`config/Route.kt` 단일 소스)
   - `stringResource` 호출 위치 규칙 준수
   - 새 파일/삭제는 사용자 사전 허락 여부 확인

## 5. 계획 확인 후 코딩 시작

위 계획을 출력한 뒤, 진행해도 되는지 확인하고 코딩 시작.
