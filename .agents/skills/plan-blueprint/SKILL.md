---
name: plan-blueprint
description: >
  Generate a bite-sized sprint roadmap as plan-blueprint.md for convert2video
  work that would change 2+ files, add a new screen or domain, or refactor
  architecture. Use when the user asks for a plan, blueprint, sprint split,
  or a planner-ready spec. Enforces project rules and Atomic Design layer
  walls so a Harness Loop session can execute without a giant one-shot plan.
---

# Skill: Project-Rule & Atomic-Design Based Blueprint Generator

## Purpose
본 스킬은 유저가 신규 화면 추가, 대규모 기능 구현, 아키텍처 리팩토링 등 **파일 2개 이상 변경이 예상되는 모든 개발 작업**을 요청했을 때 발동합니다. 메인 에이전트 및 플래너가 독단적이거나 거대한 통짜 플랜을 짜지 못하도록 강제하며, 기존 **프로젝트 룰(Project Rules)**과 **아토믹 디자인(Atomic Design)** 단위를 완벽히 준수하여 **Cursor Auto(Sonnet급)** 에이전트가 단발성 세션(Harness Loop)에서 토큰 폭발 없이 즉시 실행할 수 있는 스프린트 로드맵을 자동으로 설계합니다.

## Trigger Conditions
아래 조건 중 하나라도 해당하면 **즉시** 이 스킬을 발동합니다. 사용자가 별도로 요청하지 않아도 자율 발동합니다.
- 파일 **2개 이상** 변경이 예상되는 작업
- **신규 화면** 또는 **신규 도메인** 추가 작업
- **아키텍처 리팩토링** (폴더 이동, 레이어 재구성 등)
- **NavGraph, DI 모듈** 변경이 수반되는 작업

## Execution Protocol
트리거 조건이 충족되면 에이전트는 구구절절 설명하는 텍스트를 최소화하고, 즉시 다음 4가지 핵심 단계 및 규칙을 적용하여 루트 폴더에 **`plan-blueprint.md`** 파일을 자율 발행하십시오.

> **기존 파일 처리**: `plan-blueprint.md`가 이미 존재한다면 반드시 내용을 먼저 읽어 이전 작업의 완료/미완료 스프린트를 파악한 뒤, 이어가기 또는 전면 갱신 여부를 판단하십시오. 이전 파일을 읽지 않고 덮어쓰는 행위는 금지입니다.

---

[읽기 순서 — 절대 생략 금지]

1. .cursor/rules/rules-index-kotlin.mdc   ← 목차

2. .cursor/rules/convert2video-core.mdc   ← 로깅·용어·네이밍·테스트 핵심

3. 작업 주제에 해당하는 파일:
- 폴더·패키지 → structure-kotlin.mdc
- 제품 플로우 → project-scope-kotlin.mdc
- 단일 소스·금지 → do-not-repeat-kotlin.mdc
- 용어 → terminology-glossary.mdc

---

추가로 `memory/feedback_atomic_folder_precheck.md`를 반드시 확인하여, **신규 파일·폴더 생성 전** 동일하거나 유사한 파일이 이미 존재하는지 `Glob`/`Grep` 으로 먼저 검증하십시오.

### 2. Cursor Auto(Sonnet급) 기준 파쇄기 (Bite-Sized Sprints)
* 본 기획서를 읽고 실제 코드를 수정할 **Cursor Auto(Sonnet급)** 에이전트의 컨텍스트 윈도우 한계와 자율 루프 안정성을 최우선으로 고려하여 스프린트 계획을 수립해야 합니다.
* 단일 스프린트가 너무 많은 계층을 동시에 건드리면 커서가 폭주하여 컴파일 에러를 내고 정상 빌드된 것처럼 허위 브리핑을 하게 됩니다. 이를 방지하기 위해 다음 분할 제약을 **절대 원칙**으로 적용하십시오.

**[분할 제약 — 기획서 작성 시 엄격 적용]**
1. **1개 스프린트 제한**: 단일 스프린트(Sprint)는 **최대 파일 2~3개 수정/이동** 또는 **단일 컴포넌트/기능 흐름 1개**로만 잘게 쪼갭니다.
2. **아토믹 디자인 레이어간 차단벽**: UI 계층(`1_atoms` ➡️ `2_molecules` ➡️ `3_organisms` ➡️ `4_templates` ➡️ `5_pages`) 경계를 넘나드는 작업을 한 스프린트에 절대 몰아넣지 마십시오. 레이어별로 스프린트를 무조건 강제 분할해야 합니다.
3. **Data Layer 계층간 차단벽**: 데이터 흐름(`Server API/PHP` ➡️ `DTO` ➡️ `Repository` ➡️ `ViewModel` ➡️ `Screen/UI`) 역시 한 번에 처리하는 것(Opus급 통짜 작업)을 엄격히 금지합니다.
   * *올바른 분할 예시*: 
     - `Sprint 1`: 백엔드 PHP API 추가 및 안드로이드 `DTO` 정의
     - `Sprint 2`: `AuthSignInRepository` 비즈니스 로직 수정 및 컴파일 검증
     - `Sprint 3`: `EmailLoginViewModel` 및 State 필드 추가
     - `Sprint 4`: `EmailLoginScreen` 아토믹 디자인 UI 레이어 반영 및 최종 검증
4. **의존성 순방향 정렬**: 반드시 하위 디펜던시(PHP/DTO)가 먼저 완벽히 컴파일 PASS를 받은 뒤 상위 레이어(ViewModel/Screen)로 진격하도록 타임라인을 직렬로 배치하십시오.

### 3. 정밀 정찰 및 줄 번호 저격 (Precise Code Anchors)
추상적으로 "어디를 고치세요"라고 하지 말고, `Glob`/`Grep`/`Read` 툴을 사용해 수정 및 참조 대상이 되는 **정확한 파일 경로 및 관련 코드의 줄 번호(Line Number)**를 계획서에 확실히 박아두어야 합니다. 줄 번호 없는 파일 참조는 계획서에 포함 불가입니다.

### 4. 피 터지는 이밸루에이션 락 (Windows PowerShell 규격)
각 스프린트가 끝날 때마다 Evaluator가 현미경 검증을 할 수 있도록 **해당 스프린트 전용 검증 포인트**를 아래 4종 세트로 동봉해야 합니다. 
특히 터미널 명령어 작성 시 **리눅스용 `&&` 연산자 사용을 절대 금지**하며, Windows PowerShell 콘솔에서 에러 없이 실행 가능한 개별 라인 순차 실행 형식을 엄격히 준수하십시오.

1. 컴파일 명령 (`gradlew.bat` 개별 줄 분리 실행 명시)
2. 패키지·구조 오염 체크 PowerShell 스크립트
3. **Route 상수·l10n·금지 용어** 하드코딩 체크 스크립트
4. 런타임 엣지 케이스 체크 포인트

---

## 📋 plan-blueprint.md 표준 포맷

발행되는 계획서 파일은 어떤 도메인 작업이든 무조건 아래 양식을 100% 준수해야 합니다.

### [1] 아키텍처 영향 분석 (Architecture Impact Report)
기존 프로젝트 룰 및 아키텍처 규칙(아토믹 디자인 계층 구조, Cross-Feature 참조 위반 여부, Data Layer 경계 침범 등) 체계 하에서 이번 작업이 미치는 영향과 기술 부채 해결 방향성을 기술합니다.

필수 포함 항목:
- 영향받는 아토믹 레이어 목록 (`1_atoms` ~ `5_pages` 중 해당 레이어)
- 영향받는 Data Layer 계층 (`DTO` / `Repository` / `ViewModel` / `Screen`)
- 깨지는 NavGraph, DI 모듈, Import 목록 (있을 경우)
- 위반 또는 주의가 필요한 프로젝트 룰 목록

### [2] 🚀 한 눈에 보는 스프린트 로드맵 (Bite-Sized Sprints)
전체 타임라인과 직렬/병렬 가능 여부를 아래 마크다운 테이블 형식으로 요약합니다.

```markdown
| Sprint | 작업 대상 (레이어) | 디펜던시 | 참조 파일:줄번호 | 병렬 가능 |
|--------|-------------------|----------|-----------------|-----------|
| S-01   | API & DTO 정의     | 없음     | FooDto.kt:12    | -         |
| S-02   | Repository 구현   | S-01     | FooRepo.kt:34   | -         |
| S-03   | ViewModel/State   | S-02     | FooVm.kt:56     | -         |
| S-04   | Organism 컴포넌트 | S-03     | FooCard.kt:78   | S-05와 병렬 가능 |
| S-05   | Template/Page 연결 | S-04     | FooPage.kt:90   | S-04와 병렬 가능 |

> 스프린트 1개당 수정 파일이 2~3개를 초과하면 **즉시 분할**하십시오.

### [3] 🛠️ 스프린트별 상세 실행 가이드 & 검증 포인트
각 스프린트 섹션마다 아래 4가지 항목을 구체적으로 채워 넣습니다.

1. 수정/이동 파일 목록 (From ➡️ To 또는 Target Files)
  - 아토믹 디자인 레이어 또는 Data Layer 계층이 명시된 물리적 파일 경로 + 줄 번호
  - 예) 3_organisms/FooCard.kt (신규 생성) / 5_pages/FooPage.kt:45-67 (수정)
2. 동시 수정이 필요한 외부 파일
  - 해당 작업으로 인해 깨지는 NavGraph, DI Module, Import 목록을 줄 번호와 함께 저격
3. 사전 확인 체크리스트
  - 동일·유사 파일이 이미 존재하는지 확인: Glob / Grep 명령 명시
  - Route 상수 사전 확인: config/Route.kt 내 해당 경로 존재 여부

4. 🚨 해당 단계 전용 검증 가이드 (Verification 4종 세트)

  ① 컴파일 (PowerShell 안전 규격 예시)
   ```powershell
    # cd와 명령어 사이에 && 연산자를 사용하지 마십시오.
    cd "C:\Users\songw\AndroidStudioProjects\convert2video"
    .\gradlew.bat :app:compileDebugKotlin --no-daemon
   ```

   **② 패키지·구조 오염 체크**
   ```powershell
   # 예시: 아토믹 레이어 간 역방향 import 탐지
    Select-String -Path "app/src/main/java/**/*.kt" -Pattern "import.*5_pages" -Recurse
   ```

   **③ Route·l10n·금지 용어 하드코딩 체크**
   ```powershell
   # Route 하드코딩 탐지
    Select-String -Path "app/src/main/java/**/*.kt" -Pattern '"[a-z_]+/[a-z_]+"' -Recurse
    # 문자열 하드코딩 탐지 (stringResource 미사용)
    Select-String -Path "app/src/main/java/**/*.kt" -Pattern 'Text\s*\(\s*"[^"]*"' -Recurse
    # 금지 용어 탐지 (activity, meetup, reservation, my_info)
    Select-String -Path "app/src/main/java/**/*.kt" -Pattern '\b(activity|meetup|reservation|my_info)\b' -Recurse
   ```

   **④ 런타임 체크 포인트**
   - 예) "화면 진입 시 Compose 트리에서 중복 리컴포지션이 일어나지 않는지 확인"
   - 예) "ViewModel StateFlow가 화면 회전 후에도 정상 복원되는지 확인"
   - 예) "Snackbar가 ViewModel → onResult 콜백 패턴으로 전달되는지 확인"


[4] 🏁 마무리 선언 (고정 아웃풋)
파일 최하단에 아래 문구를 한 토씨도 틀리지 말고 고정으로 출력하십시오.
"수정은 Cursor AI를 통해 진행할 예정입니다. 각 Sprint별로 단독 가동하기 좋게 프로젝트 룰 및 아토믹 디자인(Atomic Design) 단위를 반영한 플랜 작성이 완료되었습니다."
