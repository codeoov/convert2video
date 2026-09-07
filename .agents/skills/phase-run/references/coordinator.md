# Coordinator protocol

## 역할과 도구

이 Task는 순서·증거·상태만 관리한다. 제품 구현은 각 Phase Task가 맡는다.
도구 이름이 세션에 없으면 제공된 도구 검색으로 찾고 **실제 스키마**를 읽는다.
필요한 Desktop 도구: `list_projects`, `create_thread`, `wait_threads`, `read_thread`,
`list_threads`, `send_message_to_thread`. native subagents는 Phase 안에서 사용한다.
도구 누락은 기능 미지원으로 보고한다. 셸에서 내부 RPC/DB를 흉내 내지 않는다.

`status`와 `dry-run`은 조회만 한다. 상태 파일/lock/Task 생성, 하네스 가동,
기존 state 수정, 프로세스 시작이 없다. preview 시 실제 실행을 요청하지 않는다.

## 실행 명세 준비

선택한 계획과 같은 위치의 `<stem>.phase-run.json`을 먼저 찾는다. 없으면
계획을 읽어 실행 명세를 작성한다. 명세는 계획의 범위·의존성·필수 검사를 보존한다.
예전에 완료했다는 대화 요약이나 파일 존재만으로 Phase를 PASS 처리하지 않는다.
본문과 명세가 다르면 구현 전에 고친다. 실행 중인 명세/계획을 조용히 덮어쓰지 않는다.

Phase ID는 명세 배열 순서를 따른다. P0-P14는 두 ID 사이의 포함 구간이며 P1b도
포함한다. 중복/없는 ID, 역방향 범위, 누락 의존성은 오류다. 부분 범위의 선행
의존성을 검증하지 못하면 시작하지 않는다. 생략된 Phase를 완료로 위장하지 않는다.

모드는 `read-only` 또는 `harness`다. 미분류 구현은 harness가 기본이다.
문자열 2개 파일 수정, 테스트/설정/백업 XML 수정도 harness다. P3/P14라는
번호만으로 검사 전용이라고 간주하지 않는다. read-only 허용 산출물은 실행 기록뿐이다.

실행 시 시작 범위·모드·수동 gate를 짧게 알리고 진행한다. 사용자가 이미 승인한
범위라면 단계마다 재승인을 받지 않는다. 계획이 비어 있거나 실질적인 제품 선택이
필요할 때만 질문한다. 설정/계정 비밀은 명세나 인계 파일에 복사하지 않는다.

## Workspace와 순차 실행

1. `list_projects`로 saved project의 실제 ID/경로/Git 여부를 확인한다.
2. phase-run 실행은 같은 saved checkout의 **local** 사용 요청이다.
   `create_thread.target = {type: "project", projectId: <조회된 ID>,
   environment: {type: "local"}}`를 사용한다. 기본 branch worktree, projectless,
   history fork로 바꾸지 않는다. 경로가 맞는 프로젝트가 없으면 시작하지 않는다.
3. 현재 checkout의 git HEAD/status와 기존 수정 파일을 기록한다. 다른 구현 Task가
   같은 checkout에서 실행 중이면 먼저 충돌 여부를 조사한다. 단일 writer 원칙을 지킨다.
4. 상태 helper로 현재 coordinator를 소유자로 등록하고 요청한 범위를 초기화한다.
   한 checkout에는 한 개의 미완료 run만 허용한다. 다른 run을 자동 삭제하지 않는다.
5. 실행될 Phase를 **예약한 뒤** 생성 요청을 보낸다. 예약에 run ID, phase ID,
   attempt, dispatch key, owner, revision을 영속화한다.

## 새 Task 생성과 인계

`create_thread`는 비동기이며 일반 멱등 키가 없다. 요청 **이전**에 저장된 dispatch key를
제목/프롬프트에 넣어 결과가 유실돼도 조회할 수 있게 한다. 성공한 호출의 실제
threadId/hostId를 bind한다. `clientThreadId`만 반환되면 생성 대기 중으로 저장하며
이를 threadId 자리에 넣지 않는다. setup 완료가 확인될 때까지 다음 생성은 금지다.

새 Task 프롬프트는 다음의 짧은 계약으로 만든다(값은 현재 run에서 채운다).

```text
Phase Run worker: <runId>/<phaseId>, attempt <n>, dispatch <key>.
Use saved project local checkout <absolute path>.
Read <absolute path>/.agents/skills/phase-run/references/worker.md completely.
Manifest: <absolute manifest path>. State: <absolute state path>.
Phase contract/handoff: <absolute handoff path>.
Work only on <phaseId> in mode <mode>. The user authorized this phase's
implementation and native Harness subagents within the manifest scope.
Do not create another desktop Task or update coordinator state.
Report only this phase with evidence for this attempt. Stop at unmet manual gates.
```

필수 역할 지침은 전달하되 이전 대화·전체 빌드 로그를 붙이지 않는다.
사용자가 이번 실행에 모델/강도를 명시했다면 `model`/`thinking`에 지원 값을 전달한다.
그 외에는 생략한다. 스킬 설치에 사용한 모델을 모든 향후 실행에 고정하지 않는다.

`wait_threads`에 실제 threadId/hostId를 전달하고 반환 cursor를 다음 `afterCursor`로
유지한다. 대기는 30~60초 단위로 하며 unchanged 결과를 반복 설명하지 않는다.
`read_thread`는 완료 보고/불확실한 상태 확인에만 사용한다.
승인 요청/사용자 질문은 coordinator가 임의로 답하지 않는다.

Phase 완료 메시지가 도착해도 즉시 다음 Phase를 시작하지 않는다. 모든 내부
서브에이전트가 끝났고 더 이상 write하지 않는지, 보고가 현재 run/phase/attempt/
dispatch/thread에 해당하는지, 검증 증거와 변경 범위가 맞는지 확인한다.
짧은 phase 결과를 저장하고 helper가 통과를 받아들인 뒤 다음 Phase를 예약한다.

## 인계와 증거

각 Phase 인계에는 목표, 허용 파일, 이전 Phase의 공개 계약/변경 파일 요약,
필수 검증/수동 gate, 보고 위치를 적는다. 도메인 규칙은 CLAUDE.md를 직접 읽게 한다.
중간 로그는 별도 파일에 두고 다음 Phase가 필요한 부분만 읽게 한다.

실행 증거에는 실제 명령, exit code 또는 수동 확인 결과, 시각, 대상 Phase/attempt,
검증한 변경의 fingerprint, 로그/보고 경로가 있어야 한다. 파일 존재나 "PASS"라는
단어만으로 통과시키지 않는다. helper의 형식 검증은 coordinator의 내용 검토를 대신하지 않는다.
검사 후 파일이 바뀌면 해당 증거를 재사용하지 않는다.

Harness 완료에는 planner 범위 승인, plan_evaluator Approved, generator/evaluator
Contract 합의와 **evaluator Round 3+ PASS**, compile/checks exit 0가 필요하다.
Round 1~2의 FAIL은 프로젝트의 검토 절차이며 존재하지 않는 버그를 만들라는 뜻이 아니다.
실제 결함이 없으면 그 사실과 남은 검토 gate를 기록한다. 라운드 수 충족을 위해
불필요한 코드를 변경하지 않는다. 최대 10라운드 뒤 미해결이면 blocked로 보고한다.

필수 실기기/계정/환경 gate는 실제 확인될 때까지 blocked다. 미실행을 N/A나 PASS로
바꾸지 않는다. 수동 확인 대기는 구현 완료와 전체 완료를 분리해 사용자에게 알린다.
범위 밖 수리·배포·commit/push·실결제는 자동으로 추가하지 않는다.

## 재개와 중복 생성 방지

`--resume`은 저장된 상태를 읽고 plan/manifest hash, checkout, 기존 Task와 결과를
대조한다. owner를 바꿔야 하면 이전 coordinator가 실행 중이 아닌지 먼저 조회한다.
잠금 파일의 나이나 단순 timeout만 보고 소유권을 빼앗지 않는다.

- `running`: 같은 Task를 조회/대기한다. 새 Task를 만들지 않는다.
- `dispatching` 및 생성 결과 유실: dispatch key로 기존 Task를 찾고 본문/프로젝트/
  phase를 대조한다. 정확히 하나면 bind, 여러 개거나 조회 불가면 blocked.
  `list_threads` 결과에 없다는 것만으로 생성 실패가 확정된 것은 아니다.
- Task 생성이 **명확하게 실패**했다는 응답이 있을 때만 새 예약을 허용한다.
  불확실한 상태는 최대 3회 조회 후 사유를 남긴다. 재생성으로 우회하지 않는다.
- `blocked`/`failed`: 원인을 확인하고 같은 Phase Task에 후속 메시지를 보내 재개한다.
  증거는 새 attempt로 다시 수집한다. PASS Phase는 다시 실행하지 않는다.
- 계획/명세/checkout 변경: 실행을 멈추고 이전 결과를 보존한다. 새 범위의 검증 없이
  기존 PASS를 새 계획에 적용하지 않는다.

사용량 제한/앱 중단 뒤 자동 wakeup은 이 스킬의 기능이 아니다. 실행 중에는
완료 확인 후 다음 Phase로 계속하며, 종료됐으면 사용자 `재개`로 이어간다.

## 마지막 보고

모든 선택 Phase가 검증됐을 때만 전체 완료를 선언한다. P14 등 마지막 Phase Task는
최종 통합 검증 보고를 남기고 coordinator가 전체 상태를 확인해 사용자에게 요약한다.
각 Phase Task에도 완료 기록이 남으므로 앱 알림이 마지막 한 번만 온다고 보장하지 않는다.
새 Task 생성 도구가 요구하는 `::created-thread{threadId="..."}` 표시도 생략하지 않는다.

blocked 시 완료 Phase, 멈춘 Phase, 필요한 사용자 조치, `페이즈런 재개`를 안내한다.
상태 조회에서는 새 Task나 하네스를 생성하지 않는다.
