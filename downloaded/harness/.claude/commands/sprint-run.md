---
description: Harness 스프린트 — planner → generator ↔ evaluator (TypeScript + 패리티 체크 포함)
argument-hint: "[@기획서 또는 목표 한 줄]"
allowed-tools: Bash, Read, Grep, Glob, Agent
---
<!-- 이 파일은 .claude/ 쪽 정본이기도 합니다. 수정 후: python3 .cursor/harness/sync-agent-docs.py -->

Cursor와 동일 Harness. **코드 Write/Edit는 generator만.**

## Goal (자율 종료 조건)

`/goal`을 따로 쓰지 않는다. **`/sprint-run` = Goal 등록 + Harness 루프**다.

이 스프린트의 termination condition:

> evaluator **Round 3+ PASS** + `HARNESS_CONTRACT_FILES` `npx tsc --noEmit` 무에러 + nginx/Next.js scope 클린

위 조건이 충족될 때까지 **사용자 개입 없이** planner → plan_evaluator → generator ↔ evaluator 루프를 자율 실행한다. evaluator **PASS 전** 「스프린트 완료」 금지.

## 0.5 사용자 확인 (planner 전)

- **질문 필요**: `@기획서` 없고 파일 경로·완료 기준 불명확 → 2~4개 질문 후 답변 대기
- **질문 생략**: `@기획서` 첨부 (block-*.md) 또는 "하네스 생략" / "직접 고쳐" / "빠르게만" → 즉시 planner

---

## 순서

1. **planner** — Task Type 선언 + Contract 작성
2. **plan_evaluator** — Type별 계획 검증, Approved까지
3. **Contract** — generator + evaluator 합의 (수정·완료·검증)
4. **루프** — generator Round N → evaluator Round N (최대 10회)

## Rule Context 주입 (Claude P2)

- **자동**: `UserPromptSubmit` → Harness arm · `PreToolUse(Agent)` → `harness-inject-rules.py` (Cursor Task와 동일 번들)
- **폴백**: 훅 실패 시 수동 bundle + `latest-<role>.md` Read

```bash
# 폴백만 (훅 미동작 시)
bash .claude/hooks/harness-bundle-rules.sh --role evaluator --round 3 --contract-files "src/…"
```

설계: `.claude/harness/rule-context-design.md`

## evaluator 필수 (매 Round)

```bash
# Contract -수정- 파일 목록으로 교체 (예시이며 SEA 특화 아님)
export HARNESS_CONTRACT_FILES="src/lib/<domain>.ts,src/app/<path>/page.tsx"
export HARNESS_STRICT_WARNINGS=1

npx tsc --noEmit
bash .cursor/hooks/run-harness-checks.sh

# 마이그레이션 스프린트 한정 — PHP 패리티 체크 (일반 스프린트는 생략)
# HARNESS_PARITY_CHECK=1 \
# HARNESS_PARITY_ROUTES="/<contract-path-1>,/<contract-path-2>" \
# bash .cursor/hooks/run-harness-checks.sh
```

- Mac nginx: `/opt/homebrew/var/log/nginx/error.log`
- 정본: `.cursor/agents/evaluator.md`

## PASS

- Round **3+** · Grill-me **5+** · 브라우저 URL in Contract
- `HARNESS_CONTRACT_FILES` + checks · strict TypeScript error=FAIL
- Round 1–2 FAIL 전제
