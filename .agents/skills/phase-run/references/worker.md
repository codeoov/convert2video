# Phase worker protocol

당신은 coordinator가 지정한 **Phase 하나**만 처리한다. 먼저 이 문서 전체,
AGENTS.md/CLAUDE.md 전체, 전달받은 manifest와 해당 Phase handoff를 읽는다.
프롬프트의 runId/phaseId/attempt/dispatch key와 상태의 예약이 일치해야 한다.
예약은 있지만 bind 전일 수 있다. 코드 수정 전에 coordinator가 실제 Task ID를
bind한 상태를 확인하고, 다른 Task로 bind되었거나 상태가 취소됐다면 작업하지 않는다.

coordinator가 명시한 같은 saved local checkout에서 작업한다. git status와 현재
파일을 확인하고 기존 미커밋/사용자 변경을 보존한다. 새 worktree·branch·checkout
전환, git reset/clean, 자동 commit/push는 하지 않는다.

## read-only

조사·기존 검사 실행만 수행한다. 허용되는 기록은 `.phase-run/`의 이번 attempt
보고/로그다. 소스·테스트·설정·규칙 파일을 수정하지 않는다. 수정이 필요하면
어떤 파일과 이유인지 보고하고 blocked로 종료한다. 모드를 스스로 낮추거나
수정 후 read-only였다고 보고하지 않는다.

실기기·계정 접근이 없어 검사를 못 했으면 필수 gate 미충족으로 보고한다.
검사를 실행하지 않았는데 성공했다고 가정하지 않는다.

## harness

기존 sprint-harness-gate / harness-loop / harness-subagents를 따른다.
첫 응답 Gate와 planner → plan_evaluator → Contract → generator ↔ evaluator를 수행한다.
Codex native `spawn_agent`/`send_input`/`wait_agent`를 실제 제공 스키마로 사용한다.
역할 `planner`, `plan_evaluator`, `generator`, `evaluator`는 `.codex/agents/*.toml` 기준이다.
자동 규칙 주입이 안 되어도 각 역할에 필요한 원본 규칙을 직접 읽도록 전달한다.
다른 Phase의 `.cursor/harness-state.json`이나 cache/latest 파일은 이번 Phase의 PASS 증거가 아니다.

Task 생성 권한은 coordinator에게 있다. 내부 역할에 `create_thread`를 사용하지 않는다.
역할 서브에이전트가 다시 planner/generator/evaluator를 재귀 생성하지 않도록 범위를
명시한다. 구현은 generator만 수행하고, 동일 파일에 두 writer를 동시에 두지 않는다.
검증/Gradle은 구현이 멈춘 뒤 순차 실행한다. 모든 역할이 종료해야 Phase가 끝난다.

이 Phase의 목표·허용 파일·전용 검사/수동 gate를 Contract로 고정한다.
계획이 이미 있으면 전체 계획을 다시 만들지 않고 현재 Phase 범위만 검토한다.
타입 검토/Contract 미승인 상태에서 구현하지 않는다.

evaluator는 최소 3라운드 이후에만 PASS할 수 있다. 실제 결함은 증거와 함께 수정하며,
숫자를 맞추기 위해 버그를 발명하거나 의미 없는 코드 변경을 하지 않는다.
전체 round 상한은 10이다. compile/checks/필수 gate가 미통과면 완료를 선언하지 않는다.
사용자 계정·기기·권한이 필요하거나 범위 밖 수정이 필요하면 blocked 결과를 보고한다.

## 결과와 종료

상태 파일은 coordinator만 갱신한다. worker는 지정된 보고 위치에 다음을 남긴다:

- 현재 runId, phaseId, attempt, dispatch key와 실제 Task ID.
- verdict, 실제 수정 파일, 각 완료 기준의 확인 내용.
- 명령/exit code/시각과 로그 경로, 검증한 변경 fingerprint.
- 하네스일 때 승인 결과/역할 ID, evaluator round와 명시적 PASS 또는 미통과 사유.
- 수동 gate별 실제 결과 또는 필요한 사용자 조치.
- 다음 Phase에 필요한 짧은 계약/주의 사항.

보고 후 새 Phase를 시작하지 않는다. coordinator가 같은 Task를 재개시키면 새로운
attempt를 확인하고 필요한 보완/재검증을 수행한다. 이전 증거를 새 attempt로 복사해
통과시키지 않는다. 마지막 Phase도 필수 gate가 미충족이면 전체 완료를 선언하지 않는다.
