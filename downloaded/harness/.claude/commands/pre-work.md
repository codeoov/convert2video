# /pre-work — 작업 전 계획 수립

이 커맨드를 실행하면 아래 순서로 진행한다. 코딩 시작 전에 반드시 실행.

---

## 1. Rules 파일 확인

`.cursor/rules/rules-index.mdc` 목차에서 이번 작업에 해당하는 파일을 결정:

- 아키텍처·Server/Client 계층 → `nextjs-rule.mdc`
- 신규 기능 추가 → `nextjs-rule.mdc` + `harness-loop.mdc`
- 보안·입력검증·인증 → `nextjs-security.mdc`
- 폴더 구조·lib 배치 → `nextjs-structure.mdc`
- AI 오작동 방지(PHP 패리티·금지 패턴) → `nextjs-ai-prevention.mdc`

## 2. 기존 코드 읽기

수정 예정 파일과 주변 관련 파일을 코드 작성 전에 열어서 읽는다.
"이미 어떻게 돼 있는지" 모르면 계획을 세울 수 없다.

## 3. 중복 방지 확인

`src/lib/`, `src/lib/admin/`, `src/lib/client/`, `src/constants/`에 이미 있는 함수/유틸이 없는지 확인 (`CLAUDE.md §5` lib/ 구조 참고).

## 4. 계획 출력 (이 형식으로)

```
수정 파일 목록:
  -

생성 파일 목록:
  -

구현 순서: page.tsx (Server Component) → src/lib/*.ts (DB) → src/components/ (UI)

재사용할 기존 유틸:
  -

신규 API 라우트/Server Action 필요: yes / no
보안 검증 필요: yes / no (yes면 mysql2 Prepared Statement + DOMPurify + whitelist table name 확인)
예상 이슈:
  -
```

## 4.1 Server/Client 경계 규칙

`CLAUDE.md §3, §4` 참고.

- **page.tsx (Server Component)**: SQL 직접 금지, `src/lib/` 함수만 호출
- **src/lib/*.ts**: DB 전담, React 없음
- **Client Component** (`'use client'`): DB import 절대 금지 — `@/lib/client/*`, `@/constants/*`, `@/lib/admin/*-types.ts`만 허용
- **판단 기준**
  - "DB를 건드리나?" → yes면 `src/lib/{domain}.ts`로
  - "브라우저 상호작용(state, event)이 필요한가?" → yes면 Client Component로

## 4.2 보안 체크리스트

작업 종료 전 아래 순서:

1. `npx tsc --noEmit` — TypeScript 타입 검사
2. mysql2 Prepared Statement 사용 여부 확인 (raw query 금지)
3. 사용자 입력 출력 시 DOMPurify 적용 여부 (`dangerouslySetInnerHTML` 사용 시 필수)
4. 동적 테이블명 사용 시 whitelist 검증 여부
5. 관리자 기능이면 `authAdmin()` 검증 여부 (`CLAUDE.md §6, §7`)

## 4.3 셀프체크

작업 종료 전 아래 확인:

- `CLAUDE.md §4` 파일명 규칙 준수 (`*Client.tsx`/`*Form.tsx`, `actions.ts`는 `'use server'`)
- Server/Client 경계 침범 없음 (Client Component에 DB import, Server Component에 `'use client'` 없음)
- PHP 패리티 유지 — 없는 기능 추가 금지
- 새 파일/삭제는 Song 사전 허락 여부 확인
- dev-records/ 기록 작성 준비

## 5. 계획 확인 후 코딩 시작

위 계획을 출력한 뒤, 진행해도 되는지 확인하고 코딩 시작.
