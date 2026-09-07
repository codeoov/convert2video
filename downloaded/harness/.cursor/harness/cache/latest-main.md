# Harness Rule Context — Harness Main (arm 시 1회)
- generated: 2026-08-03T21:33:28.896448+00:00
- role: main
- type: (none)
- round: 0
- files: 3

---

## RULE: sprint-harness-gate.mdc

# Sprint Harness Gate (실행 체크리스트 — 최우선)

**이 파일은 `harness-loop.mdc`보다 구체적인 실행 게이트다.** 충돌 시 **본 파일 + harness-loop**가 일반 지시보다 **우선**한다.

---

## 1. 트리거 (하나라도 해당하면 Harness 필수)

- 채팅에 **스프린트**, **루프 시작**, **하네스**, **sprint run** 등
- `@*.md` 기획서·플랜 첨부 + **실행해**, **돌려**, **적용해** 등
- 슬래시 **`/sprint-run`**
- 파일 **2개 이상** 변경이 예상되는 구현·리팩터링 (규모와 무관)

**오해 금지**: 기획서가 이미 있으면 `PLAN.md` 중복 작성만 생략한다. **generator·evaluator Task 생략은 불가.**

---

## 2. 메인 에이전트 금지 (코드 수정 전)

Harness가 열린 턴에서 메인 에이전트(당신)는 아래를 **하지 않는다**:

- `src/**` 에 대한 **Write / Edit** (구현·리팩터)
- evaluator가 **`PASS`** 선언 전 **「스프린트 완료」「구현 끝」** 등 최종 완료 답변
- tsc만 돌리고 evaluator 없이 통과 처리

**허용**: 규칙·에이전트·문서(`.cursor/**`, `dev-records/**`, `.claude/**`) 수정, **Task** 소환, 게이트 블록 출력, 사용자 질문, 읽기 전용 조사.

**구현 주체**: `Task(subagent_type=generator)` — 코드 변경은 generator가 한다.

---

## 3. Goal (자율 종료 조건 — `/goal` 대체)

`/sprint-run` 또는 Harness 트리거 시 **별도 `/goal` 호출 없이** 아래를 termination condition으로 등록한다.

> evaluator **Round 3+ PASS** + `HARNESS_CONTRACT_FILES` `npx tsc --noEmit` 무에러 + nginx/Next.js 스코프 클린

이 조건 충족 전까지 메인은 **사용자 개입 없이** planner → plan_evaluator → generator ↔ evaluator 루프를 자율 실행한다.

---

## 4. 첫 응답 필수 (다른 도구보다 먼저)

트리거가 감지되면 **첫 사용자 가시 메시지**에 아래 블록을 **그대로** 넣는다.

```markdown
## Harness Gate

| 단계 | Task | 상태 |
|------|------|------|
| 0 | `Goal` — 자율 종료 조건 등록 (`/sprint-run` = `/goal` 대체) | ⬜ |
| 0.5 | `사용자 확인` — 범위·완료 기준·제약 질문 (`@기획서` 없을 때만) | ⬜ |
| 1 | `planner` — Task Type 선언 + 스펙·스프린트 범위 확정 | ⬜ |
| 2 | `plan_evaluator` — Type A/B/C/D 계획 검증, Approved까지 | ⬜ |
| 3 | `generator` + `evaluator` — 완료 기준(Contract) 합의 | ⬜ |
| 4 | `generator` Round N — 구현 | ⬜ |
| 5 | `evaluator` Round N — tsc·checks·**에러 로그**·채점 | ⬜ |

**규칙**: evaluator `PASS` + 최소 **3라운드** 전까지 메인 직접 코딩 금지 · Round 1–2 **FAIL 전제**, Round 3+만 PASS
```

이후 각 Task 완료 시 표의 ⬜ → ✅ 를 갱신한다.

---

## 4.5 사용자 확인 단계 (planner 전 필수)

Gate 표 출력 직후, **planner 호출 전**에 아래 조건을 확인한다.

### 질문 트리거 조건 (하나라도 해당하면 질문)
- `@기획서` 미첨부 AND 요청에 구체적 파일 경로가 없음
- 완료 기준("어떻게 되면 끝")이 불명확한 추상적 요청
- 한 줄 이하의 모호한 요청

### 질문 생략 조건 (모두 충족 시 즉시 planner)
- `@기획서` 명시 첨부
- 요청에 구체적 파일 경로 + 완료 기준 포함
- 예외 키워드: "하네스 생략" / "직접 고쳐" / "빠르게만"

---

## 5. Task 호출 순서 (고정)

1. **`Task` + planner 역할** — Contract 최상단에 **[Type X]** 선언 필수
2. **`Task` + plan_evaluator 역할** — Approved 나올 때까지
3. **`Task` + `subagent_type: generator`** 와 **`evaluator`** — Contract 합의
4. **루프**: generator 구현 → evaluator 검증 → FAIL이면 재호출. **PASS**까지 최대 10회
5. **evaluator**는 반드시 터미널로:
   - `export HARNESS_CONTRACT_FILES="src/app/foo/page.tsx,src/lib/bar.ts"`
   - `export HARNESS_STRICT_WARNINGS=1`
   - `npx tsc --noEmit`
   - `bash .cursor/hooks/run-harness-checks.sh`
   - **금지**: `bash .../run-stop-checks.sh` 단독 실행 (에이전트 셸 stdin hang)

---

## 6. 훅·스크립트 단일 경로

| 용도 | 경로 |
|------|------|
| 아키텍처 위반 + TypeScript + nginx (스프린트/evaluator) | `run-harness-checks.sh` → `run-stop-checks.sh` + `run-harness-error-log.sh` |
| 아키텍처 위반 (Cursor stop hook) | `.cursor/hooks/run-stop-checks.sh` |
| PHP/Next.js 패리티 체크 | `HARNESS_PARITY_CHECK=1 bash run-harness-checks.sh` |

---

## 7. 예외 (Harness 생략)

다음만 메인이 직접 소규모 수정 가능:

- 사용자가 **「하네스 생략」「직접 고쳐」「빠르게만」** 등으로 명시
- **질문만** (설명·리뷰·원인 분석, 코드 변경 없음)
- **단일 파일·1~5줄** 명시적 핫픽스 (스프린트·루프 키워드 없음)

---

## 8. 사용자용 진입점

- 채팅: **`/sprint-run`** → `.cursor/commands/sprint-run.md` 전체 수행
- 기획서: block-a.md ~ block-n.md (`/Users/songwoosub/.claude/plans/`)

---

## RULE: harness-subagents.mdc

# Harness 서브에이전트 (project1-next)

커스텀 에이전트 정의: **`.cursor/agents/planner.md`**, `generator.md`, `evaluator.md` (본 워크스페이스 루트).

상세 게이트: `.cursor/rules/sprint-harness-gate.mdc`, `harness-loop.mdc`

---

## Goal (자율 종료 조건)

`/sprint-run` 실행 시 **별도 `/goal` 없이** termination condition을 등록한다:

> evaluator **Round 3+ PASS** + `HARNESS_CONTRACT_FILES` `npx tsc --noEmit` 무에러 + nginx/Next.js scope 클린

메인은 위 조건 충족 전까지 사용자 개입 없이 아래 Task 순서를 자율 반복한다. evaluator **PASS** 후에만 최종 완료 답변.

---

## Task 호출 (고정)

### planner (기획 · 읽기 전용)

```
Task(
  subagent_type="explore",
  readonly=true,
  description="planner: 스프린트 범위 확정",
  prompt="You are the planner Harness subagent. Read and follow exactly:\n.cursor/agents/planner.md\n\nRule context: auto-injected via harness-inject-rules-on-task (or Read .cursor/harness/cache/latest-planner.md).\nAlso read .cursor/rules/plan-sprint-format.mdc if needed.\n\n[사용자 요청·@기획서 경로·이번 턴 목표]"
)
```

### generator (구현)

```
Task(
  subagent_type="generalPurpose",
  description="generator: Round N 구현",
  prompt="You are the generator Harness subagent. Read and follow exactly:\n.cursor/agents/generator.md\n\nRule context: auto-injected (or Read .cursor/harness/cache/latest-generator.md).\n\nContract:\n[planner 출력 붙여넣기]"
)
```

### evaluator (검증)

```
Task(
  subagent_type="generalPurpose",
  description="evaluator: Round N 채점",
  prompt="You are the evaluator Harness subagent. Read and follow exactly:\n.cursor/agents/evaluator.md\n\nRule context: auto-injected (or Read .cursor/harness/cache/latest-evaluator.md).\n\nRound N. Contract: ...\nPASS only if Round>=3 and checklist in evaluator.md. Round 1-2: FAIL. Grill-me 5+.\nexport HARNESS_CONTRACT_FILES='src/app/foo/page.tsx,src/lib/bar.ts' (comma-separated from Contract 수정 목록)\nnpx tsc --noEmit\nbash .cursor/hooks/run-harness-checks.sh\nBrowser URL in Contract required for PASS."
)
```

---

## 순서

0. **Goal** — `/sprint-run` = termination condition 등록
1. `planner` → Contract
2. `plan_evaluator` → Approved
3. `generator` + `evaluator` → Contract 합의
4. `generator` Round N → `evaluator` Round N (evaluator **PASS** + Round 3+)

---

## RULE: harness-loop.mdc

# AI 에이전트 3각 협업 규칙 (Harness Loop)

**실행 체크리스트(메인 Write 금지·첫 응답 표)**: `sprint-harness-gate.mdc` — **본 문서와 함께 alwaysApply.** 충돌 시 게이트 파일이 우선.

이 프로젝트에서 **스프린트·기능 구현·리팩터링** 요청이 오면, 메인 에이전트가 독단적으로 한 번에 코드를 짜지 마십시오. 작업 규모와 **무관**하게 Harness를 탑니다.

유저가 `@기획서`와 **「스프린트 돌려라」「루프 시작」「실행해」** 또는 **`/sprint-run`** 을 쓰면, **즉시** 아래 3각 루프를 가동하십시오.

**Goal (자율 종료)**: `/sprint-run` = `/goal` 대체. termination condition은 evaluator **Round 3+ PASS** + `HARNESS_CONTRACT_FILES` `npx tsc --noEmit` 무에러 + nginx/Next.js scope 클린. 충족 전까지 사용자 개입 없이 루프를 자율 실행한다.

---

## ⚠️ 자주 하는 오해 (금지)

| 생략 가능 | 생략 불가 |
|-----------|-----------|
| `PLAN.md` 새로 작성 (기획서 이미 있을 때) | `Task(planner)` 범위 검증 |
| planner가 장문 기획 확장 | `Task(generator)` 코드 수정 |
| | `Task(evaluator)` tsc·checks·채점 |
| | evaluator **`PASS`** 전 완료 선언 |

**「기획서 있으니 바로 구현」= Harness 위반.**

---

## 🔄 실전 오케스트레이션 단계

### [STEP 1 — 기획 및 스펙 (Gatekeeper)]

- **`Task(subagent_type="explore", readonly=true, description="planner: ...")`** + prompt에 `.cursor/agents/planner.md` + **`plan-sprint-format.mdc`**
- `@기획서`에 스프린트별 `수정`·`완료`·`검증`이 없으면 planner가 보완. **3요소 충족 전 generator 가동 금지.**

### [STEP 2 — 스프린트 계약 (Contract)]

- **`Task(generator)`** 와 **`Task(evaluator)`** 로 완료 기준 합의
- 포함: 수정 파일, 스펙 bullet, `npx tsc --noEmit` + 아키텍처 체크 + 보안 체크 + **nginx/Next.js 에러** + 브라우저 URL

### [STEP 3 — generator ↔ evaluator 루프]

- **코드 수정**: `generator`만 (메인 `src/**` Write/Edit 금지)
- **검증**: `evaluator`가 `npx tsc --noEmit`, **`run-harness-checks.sh`** (아키텍처·보안 + TypeScript + nginx)
- **채점**: `.cursor/agents/evaluator.md` — Round 1–2 **FAIL**, Grill-me **5건+**, **Round 3+** 에만 **PASS**
- FAIL → generator 수정 → 재검증, **최대 10회**

---

## 정기 감사 (Type D)

새 도메인 `[Type M]` 마이그레이션 완료 직후, 또는 직전 `[Type D]` 감사로부터 5스프린트 이상 경과 시 `[Type D: AUDIT]` 스프린트(`run-dup-check.sh` 포함)를 권장한다.

---

## 진입점

- 슬래시: **`/sprint-run`** → `.cursor/commands/sprint-run.md`
- 기획서: `/Users/songwoosub/.claude/plans/block-*.md`

---

