# AGENTS.md — Codex CLI 진입점 (convert2video)

> **도메인 규칙 단일 소스(SSOT)는 [`CLAUDE.md`](CLAUDE.md)다.**  
> 이 파일은 Cursor의 `.cursor/rules/*.mdc` 스텁과 같은 **포인터 레이어**이며, Codex는 `@include`를 지원하지 않으므로 아래 지시문으로 대체한다.

---

## 필수 (코드·설정 수정 전)

**Phase Run 먼저 분기:** `/phase-run`, `$phase-run`, `페이즈런` / `페이즈 런`,
`페이즈 재개`, `페이즈 상태` 요청은 [`.agents/skills/phase-run/SKILL.md`](.agents/skills/phase-run/SKILL.md)를
전체 읽고 실행/재개/상태/미리보기를 구분한다. 실행 호출은 선택한 Phase마다
새 Codex Task를 만들고 **동일 saved project의 local checkout**에서 순차 실행하라는
사용자 요청이다. coordinator 자체에 단일 스프린트 Gate를 적용하지 않으며,
각 `harness` Phase 안에서 아래 Harness 규칙을 적용한다.
기능 설치·규칙 편집·질문·미리보기·상태 조회는 플랜 실행 권한이 아니다.

1. **`CLAUDE.md` 전체를 읽고** 그 규칙을 따른다. 제품 범위·컨벤션·재발 방지·폴더 구조·용어 사전은 모두 그 파일에 있다.
2. **`.kt` / `.kts` 작업 시** `CLAUDE.md` § Anti-Repeat / Single-Source Rules도 반드시 적용한다.
3. **스프린트·하네스·루프·`/sprint-run`** 등 Harness 트리거가 감지되면, `CLAUDE.md` § Default Workflow보다 아래 Harness 스펙이 **우선**한다:
   - [`.cursor/rules/sprint-harness-gate.mdc`](.cursor/rules/sprint-harness-gate.mdc) — 트리거·메인 `app/**` 직접 Write 금지·Gate 표
   - [`.cursor/rules/harness-loop.mdc`](.cursor/rules/harness-loop.mdc) — planner → plan_evaluator → generator ↔ evaluator 루프
   - [`.cursor/rules/harness-subagents.mdc`](.cursor/rules/harness-subagents.mdc) — Task 템플릿·종료 조건
4. Harness 서브에이전트 정의: [`.codex/agents/*.toml`](.codex/agents/) (Cursor 대응: [`.cursor/agents/*.md`](.cursor/agents/)).

애매하면 Harness를 탄다. 사용자가 **「하네스 생략」「직접 고쳐」「빠르게만」** 등으로 명시한 경우만 예외.

---

## 주제별 — `CLAUDE.md` 섹션 매핑

| 주제 | 읽을 섹션 (`CLAUDE.md`) |
|------|-------------------------|
| 제품 플로우·화면 | § Product Scope |
| 스택·네이밍·로깅·답변 형식 | § Core Conventions |
| 패키지·폴더·역할 분리 | § Folder Structure |
| 단일 소스·WorkManager·금지 import | § Anti-Repeat / Single-Source Rules |
| 도메인 용어·상수 | § Terminology Glossary |

전체 목차: [`.cursor/rules/rules-index-kotlin.mdc`](.cursor/rules/rules-index-kotlin.mdc)

---

## Codex 전용 경로

| 용도 | 경로 |
|------|------|
| 서브에이전트 | `.codex/agents/{planner,plan_evaluator,generator,evaluator}.toml` |
| 훅 | `.codex/hooks.json` → `.cursor/hooks/*.ps1` (런타임 분기 포함) |
| 스킬 | `.agents/skills/<name>/SKILL.md` (원본: `.claude/skills/`) |
| 스프린트 진입 | `.agents/skills/source-command-sprint-run/SKILL.md` |
| 전체 Phase 실행/재개/상태 | `.agents/skills/phase-run/SKILL.md` (`/phase-run` 채팅 별칭) |

---

## 스킬 사본 원칙 (Phase 2)

캐노니컬 원본은 **`.claude/skills/`** 다. Codex는 **`.agents/skills/<name>/SKILL.md`** 만 읽는다. Windows 심볼릭 링크는 쓰지 않는다.

| Codex 스킬 | 원본 | 비고 |
|------|------|------|
| `autoresearch` | `.claude/skills/autoresearch/` | 디렉터리 복사. SKILL.md 본문의 Claude→Codex 표기만 사본에서 허용 |
| `finn-build` | `.claude/skills/finn-build/` | 복사. Linear MCP 필요 |
| `finn-review` | `.claude/skills/finn-review/` | 복사. Linear MCP 필요 |
| `finn-spec` | `.claude/skills/finn-spec/` | 복사. Linear MCP 필요 |
| `skill-creator` | `.claude/skills/skill-creator/` | 디렉터리 복사. SKILL.md 본문의 Claude→Codex 표기만 사본에서 허용 |
| `plan-blueprint` | `.claude/skills/plan-blueprint.md` | 원본은 SKILL.md 래퍼 없음. 사본만 `name`/`description` 프런트매터를 씌움 |
| `phase-run` | `.claude/skills/phase-run/` | Codex desktop 전용. 디렉터리 전체 동일 사본 유지 |

**드리프트 방지:** `.claude/skills/`를 고치면 `.agents/skills/`를 **재복사**한다. `plan-blueprint`는 재복사 후 프런트매터를 다시 씌운다. 자동 동기화 스크립트는 이번 범위 밖(후속).

**Linear MCP:** `.codex/config.toml` `[mcp_servers.linear]` — `url = "https://mcp.linear.app/mcp"` (streamable HTTP; `url`이 있으면 HTTP, `command`면 stdio. 별도 `transport` 필드 없음). `finn-*` 실호출 전 `codex mcp login linear`. `autoresearch` / `skill-creator` / `plan-blueprint`는 외부 MCP 없이 동작.

---

## 검사·게이트 (Stop / evaluator)

| 용도 | 경로 |
|------|------|
| compile | `.cursor/hooks/run-stop-compile.ps1` |
| checks (import·로깅·용어) | `.cursor/hooks/run-stop-checks.ps1` |
| Harness 스모크 | `scripts/verify-harness-setup.ps1` |

PowerShell에서 `&&` 금지. evaluator `PASS`(Round 3+) + compile 무에러 + checks exit 0 전까지 「스프린트 완료」 선언 금지.

---

## 검증 (Phase 1)

Codex 세션에서 *「Convert 화면 Success 다이얼로그 확인 버튼 누르면 어디로 이동해?」* 질의 → **`Convert에 잔류`(확인 ≠ 랜딩)** 이라고 답하면 `CLAUDE.md` § Product Scope를 올바르게 따른 것이다.
