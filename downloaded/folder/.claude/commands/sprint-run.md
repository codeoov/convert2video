# 스프린트 Harness 실행 (/sprint-run)

이 커맨드를 실행한 뒤, **메인 에이전트는 직접 `app/**` 코드를 수정하지 말고** 아래를 **순서대로** 수행한다.  
단일 기준: `.cursor/rules/sprint-harness-gate.mdc` + `.cursor/rules/harness-loop.mdc` + `.cursor/agents/*.md`

---

## 0. 게이트 출력 (필수 · 첫 응답)

`sprint-harness-gate.mdc` §3의 **Harness Gate** 표를 채팅에 붙인다. (체크 전 Write/Edit on `app/**` 금지)

```markdown
## Harness Gate

| 단계 | Task | 상태 |
|------|------|------|
| 1 | `planner` — 스펙·스프린트 범위 확정 | ⬜ |
| 2 | `plan_evaluator` — Type 검증·Approved | ⬜ |
| 3 | `generator` + `evaluator` — 완료 기준(Contract) 합의 | ⬜ |
| 4 | `generator` Round N — 구현 | ⬜ |
| 5 | `evaluator` Round N — compile/checks·채점 | ⬜ |

**규칙**: evaluator `PASS` + 최소 **2라운드** 전까지 메인 직접 코딩 금지 · Round 1 evaluator는 Anti-Lazy **FAIL 전제**
```

---

## 1. Planner (Agent)

```
Agent(subagent_type="planner")
```

- 사용자가 기획서를 줬으면: `PLAN.md` 새로 쓰지 말고, **이번 스프린트 범위·완료 기준·수정 파일 목록**만 확정.
- 없으면: 스프린트 단위로 쪼갠 범위를 generator에 넘길 요약을 반환.
- **출력에 `[Type A/B/C/D]` 선언 필수.**

---

## 2. Plan Evaluator (Agent)

```
Agent(subagent_type="plan_evaluator")
```

- planner 결과물을 받아 Type별 검증 수행.
- **Approved** 나올 때까지 planner → plan_evaluator 재제출 루프.
- Approved 이후에만 generator 진입.

---

## 3. Contract (Agent — generator + evaluator)

evaluator에게 완료 기준 초안을 요청하고, generator와 합의된 Contract를 확정한다.

Contract에 포함:
- 수정/생성 파일 (경로)
- 완료 기준 (스펙 bullet)
- 검증: compile, `run-stop-checks.ps1`, Route/l10n/금지 용어, 런타임 체크 1줄 이상

---

## 4. 구현·검증 루프 (최대 10회)

| Round | 주체 | 할 일 |
|-------|------|--------|
| 1+ | `generator` | Contract대로 코드 수정 |
| 1+ | `evaluator` | compile + checks + Grill-me 채점 · **Round 1은 Anti-Lazy로 FAIL 전제** |

**PASS 조건** (`.cursor/agents/evaluator.md`):
- compile 성공
- 스펙·아키텍처 룰 충족
- **최소 2라운드** 진행 후 PASS (1라운드만으로 PASS 금지)

FAIL 시: evaluator 피드백 → generator 재호출 → evaluator 재검증.

---

## 5. 터미널 (evaluator가 실행 — PowerShell, `&&` 금지)

```powershell
cd "C:\Users\songw\AndroidStudioProjects\happy_v12"
.\gradlew.bat :app:compileDebugKotlin --no-daemon
```

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".cursor\hooks\run-stop-checks.ps1"
```

(메인은 PASS 전 compile만으로 완료 선언하지 않는다.)

---

## 6. 최종 답변 (evaluator PASS 이후만)

`happy-core-kotlin.mdc` §3 형식으로 한국어 정리:

1. 한 일 · 수정 파일
2. Harness Round 수 · evaluator **PASS** 여부
3. 사용자 확인 시나리오
4. 남은 리스크

**금지**: evaluator `PASS` 없이 「스프린트 완료」.
