# 페이즈런 사용법

Codex 채팅에서 플랜을 한 번 지정하면 Phase별 새 Task를 순차 실행합니다.
하네스가 필요한 Phase는 그 Task 안에서 planner/generator/evaluator를 사용합니다.

```text
페이즈런 plan-blueprint-pro-billing.md P0-P14
```

이전에 선택한 플랜이 대화에서 명확하면 `페이즈런 P0-P14`로 줄일 수 있습니다.
`P1b` 같은 중간 단계도 플랜의 순서대로 포함합니다. 단순 숫자 반복이 아닙니다.

```text
페이즈런 상태
페이즈런 재개
페이즈런 미리보기 plan-blueprint-pro-billing.md P0-P14
```

같은 입력을 `/phase-run P0-P14 --plan plan-blueprint-pro-billing.md`,
`/phase-run --status`, `/phase-run --resume`, `/phase-run --dry-run`으로도 쓸 수 있습니다.
정식 스킬 선택은 `$phase-run`입니다. `/phase-run`은 이 프로젝트가 해석하는 채팅
별칭이므로 앱의 slash 자동완성 목록에 반드시 표시되는 것은 아닙니다.

실행 요청은 각 Phase의 새 Task 생성과 **같은 saved project checkout의 local 실행**을
포함합니다. 이 checkout에서 다른 구현 Task를 동시에 실행하지 마세요.
기존 미커밋 작업은 보존하며 커밋/푸시/배포는 별도 요청 없이 하지 않습니다.

실기기·스토어 계정·수동 확인이 필요한 필수 gate에서 멈추면 필요한 확인을 마친 뒤
`페이즈런 재개`라고 입력하세요. 완료한 Phase는 반복하지 않고 저장된 상태와 실제
Task 결과를 대조합니다. 중단 직전에 새 Task가 생성됐는지 불확실하면 먼저 기존
Task를 찾습니다. 조회 실패를 'Task 없음'으로 간주해 중복 생성하지 않습니다.

플랜 작성부터 준비하려면 다음처럼 요청하세요.

```text
이 기능 플랜을 페이즈런용으로 짜줘. Phase별 하네스 여부와 수동 gate도 분류해줘.
```

준비된 계획을 검토한 다음 `페이즈런`으로 실행합니다. 플랜 작성만 요청하면
구현은 시작하지 않습니다. 기존 플랜도 첫 실행 때 실행 명세를 준비할 수 있습니다.

Phase Run은 현재 Codex desktop의 Task 생성/조회/대기 도구를 사용합니다.
앱 종료나 사용량 제한 뒤 자동으로 다시 켜지는 별도 서비스는 아닙니다.
모델/추론 강도는 실행 요청에서 지정할 수 있고, 생략하면 앱 기본값을 사용합니다.
새 컨텍스트로 이전 로그 누적을 줄이지만 서브에이전트와 검증에도 토큰이 듭니다.

설치 위치와 동작 근거: `.agents/skills/phase-run/`의 Codex 스킬 및 `AGENTS.md` 진입 규칙.
공식 [스킬 문서](https://learn.chatgpt.com/docs/build-skills)와
[서브에이전트 문서](https://learn.chatgpt.com/docs/agent-configuration/subagents)를 참고했습니다.
