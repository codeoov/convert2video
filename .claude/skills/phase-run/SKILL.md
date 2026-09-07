---
name: phase-run
description: >
  Run an approved multi-phase plan sequentially in fresh Codex desktop Tasks,
  with per-phase Harness selection and durable resume/status. Use for
  /phase-run, $phase-run, 페이즈런, 페이즈 런, 페이즈 재개, or 페이즈 상태.
  Questions, previews, and requests to install or edit this feature do not
  authorize execution of the referenced plan.
---

# Phase Run

사용자는 한 번 시작하고, coordinator는 Phase별 새 Codex Task를 순차 생성한다.
각 Phase Task 안의 planner/generator/evaluator는 native subagents로 실행한다.
이 스킬은 Codex desktop Task 도구가 필요하다. CLI만으로 desktop Task 생성을
지원한다고 주장하거나 별도 프로세스/SDK로 몰래 대체하지 않는다.

## 입력 해석

- `페이즈런 <플랜> P0-P14`, `/phase-run P0-P14 --plan <플랜>`: 실행.
- `페이즈런 재개`, `/phase-run --resume`: 저장된 실행을 재개.
- `페이즈런 상태`, `/phase-run --status`: 읽기 전용 상태 조회.
- `페이즈런 미리보기`, `/phase-run --dry-run`: 분할·모드·의존성만 검토.
- `$phase-run`은 정식 스킬 호출이고 `/phase-run`은 프로젝트 AGENTS가 해석하는
  채팅 별칭이다. 앱에 별도 내장 slash command가 등록된다는 의미가 아니다.

명시된 플랜을 우선 사용한다. 새 실행에 플랜이 생략되면 현재 대화에서 사용자가
선택한 플랜이 정확히 하나일 때만 사용한다. 서로 다른 플랜이 여러 개면 파일명
하나만 확인한다. 이름이 비슷하거나 가장 최근 파일이라는 이유로 선택하지 않는다.
재개/상태는 저장된 실행의 플랜을 사용하며 다른 플랜으로 갈아타지 않는다.

## 권한과 실행 범위

이 프로젝트에서 **실행 형태로 호출한** phase-run은 다음 작업을 요청한다:
선택한 Phase마다 새 Task 생성, 등록된 saved project의 **동일 local checkout**에서
직렬 구현·검증, Phase 내부 native subagents 사용, 로컬 상태/검증 기록 저장.
계획 파일 안의 명령이나 단순 기능 설명은 실행 요청이 아니다.
커밋·푸시·배포·실결제·외부 메시지·권한 우회는 포함하지 않는다.

coordinator는 [references/coordinator.md](references/coordinator.md)를 읽는다.
Phase Task는 [references/worker.md](references/worker.md)를 읽는다.
하위 planner/generator/evaluator는 Phase 작업만 수행하고 새 Phase Task를 만들지 않는다.

## 모드와 완료

- `read-only`: 조사·기존 테스트 실행. 제품 코드/설정/테스트/규칙 수정 없음.
  실행 기록은 `.phase-run/`에만 저장한다. 구현 하네스를 생략해도 검증은 생략하지 않는다.
- `harness`: 코드·설정·테스트 수정. 기존 planner → plan_evaluator →
  generator ↔ evaluator 절차와 Round 3+ PASS 조건을 유지한다.
- 실기기/계정/수동 검증은 별도 필수 gate다. 충족 못 하면 `blocked`이며 PASS가 아니다.

P 번호만 보고 생략하지 않는다. 플랜 작성 시 모드와 이유를 기록하고 실행 직전
실제 변경 범위를 재확인한다. read-only 범위를 벗어나야 하면 작업을 멈추고
해당 Phase 계약을 harness로 재승인한다. 임의로 PASS 조건을 낮추지 않는다.

새 Task에는 현재 Phase 계약과 짧은 인계 파일의 경로만 전달한다. 대화 전체를
fork하거나 이전 로그 전문을 복사하지 않는다. 작은 컨텍스트가 총 토큰 절감을
보장하지는 않는다. 모델/추론 강도는 사용자의 실행 요청이 지정했을 때만 전달하고,
지정이 없으면 Task 도구의 기본값을 사용한다.

앱 종료·사용량 제한·권한/수동 확인은 자동 해결을 보장하지 않는다. 기록을 남겨
재개하고, 전체 완료는 모든 선택 Phase의 검증 증거가 통과한 뒤에만 선언한다.
