# Harness Rule Context — Harness Planner
- generated: 2026-08-03T21:44:23.726202+00:00
- role: planner
- type: (none)
- round: 0
- files: 4

---

## RULE: plan-sprint-format.mdc

# 스프린트 플랜 필수 양식 (Plan Sprint Format)

기획서·스프린트 계획을 **새로 쓰거나 보완**할 때, **매 스프린트마다** 아래 3가지를 **반드시** 포함하십시오.

**하나라도 빠지면 기획 미완료**로 간주하고 Harness(구현)에 넘기지 마십시오.

---

## 세션 분할 판단 (플랜 최상단에 명시)

플랜의 전체 개발 볼륨이 **커서AI Auto 모드가 한 번에 무리없이 처리할 수 있는 규모(1세션 분량)**를 넘으면, 플랜 문서 **최상단**에 아래를 명시하십시오:

- "이 작업은 볼륨이 커서 여러 세션으로 나눠 진행하는 것을 권장합니다."
- 세션 경계 제안 (Phase/Sprint 그룹 단위로 몇 세션에 나눌지 요약)

1세션 규모 안이면 이 섹션은 생략합니다.

---

## 필수 3요소 (스프린트마다)

| 항목 | 내용 |
|------|------|
| **수정** | 이번 스프린트에서 건드릴 **파일 전체 경로** (예: `src/app/sea/[country]/[board]/page.tsx`) |
| **완료** | 무엇이 되면 끝인지 — 동작·기능 기준, 1줄 이상 |
| **검증** | `npx tsc --noEmit` + 브라우저에서 확인할 URL·동작 (PHP vs Next.js 응답 코드 비교 포함) |

**DB 마이그레이션(`_up.sql`) 포함 스프린트**: "검증"에 SQL 파일 작성만 적으면 미완료. 로컬 DB 실제 실행 + `SHOW COLUMNS` 재확인 + 의존 라우트 curl 확인까지 적을 것 → 상세: `db-migration-verification.mdc`

---

## 검증 실행 원칙 (구현 단계 — 완료 주장 시 반드시 적용)

- **말로 된 완료 선언 금지**: "적용했습니다"/"통과했습니다"만 쓰지 말고, "검증"에 적은 명령을 **실제로 실행한 raw 터미널 출력**을 응답에 그대로 첨부한다.
- **에러 발견 시 셀프 교정 최대 2회**: 실행 결과 에러가 나오면 사용자에게 넘기기 전에 직접 고치고 재검증 — 2회 초과하면 중단하고 시도 내용 + 마지막 raw 에러와 함께 블로커로 보고한다 (무제한 재시도 금지).
- 상세·DB 특화 절차 → `db-migration-verification.mdc`

---

## 표준 블록 (복사·준수)

```markdown
## Sprint 1
- 수정: src/lib/sea.ts, src/app/sea/[country]/[board]/page.tsx
- 완료: /sea/indonesia/qna 게시판 목록 렌더링, PHP와 동일한 HTML 구조
- 검증: npx tsc --noEmit, curl -o /dev/null -w "%{http_code}" localhost:3000/sea/indonesia/qna → 200
         브라우저 localhost:3000/sea/indonesia/qna → PHP 원본과 UI 비교
```

---

## 적용 대상

- 메인 에이전트가 기획을 작성할 때
- **`Task(planner)`** subagent
- **`@기획서` 검증 시**: 3요소가 없으면 planner가 **보완**한 뒤 Harness 진행

---

## 금지

- 수정·완료·검증 없는 한 줄 요약만 두기
- `src/app/...` 전체 경로 생략하기
- 브라우저 URL 없이 검증 완료 주장하기
- raw 터미널 출력 없이 "검증 완료"라고 말로만 선언하기
- 에러 발견 후 셀프 교정 없이(또는 무제한 재시도로) 바로 사용자에게 넘기기
- 개발 볼륨이 큰데 세션 분할 언급 없이 단일 플랜으로 제시하기

---

## RULE: nextjs-rule.mdc

# Indosarang Next.js 아키텍처

이 프로젝트는 PHP 인도사랑 사이트를 Next.js 15 (App Router)로 마이그레이션한 것입니다.
PHP → Next.js 100% 기능 패리티가 목표입니다.

---

## 데이터 흐름

```
nginx (80) → PHP (legacy)  또는  nginx → Next.js :3000 (신규)
                                         ↓
                              Server Component (page.tsx)
                                         ↓
                              src/lib/*.ts (DB 함수, mysql2)
                                         ↓
                              MySQL (인도사랑 기존 DB)
```

**PHP MVC 대응표:**

| PHP | Next.js |
|-----|---------|
| Router.php | next.config.ts redirects + nginx |
| Controller | Server Component (page.tsx) |
| Model (PDO) | src/lib/*.ts (mysql2 pool) |
| View (.view.php) | .tsx (JSX) |
| Helper | src/lib/utils.ts |
| Service | src/lib/*.ts (도메인별) |

---

## 계층 규칙

### Server Component (page.tsx, layout.tsx)
- DB 접근: `src/lib/*.ts` 함수 호출로만
- `async` 함수 — 서버에서 렌더링
- `'use client'` 없음 (기본이 Server Component)
- **금지**: `useState`, `useEffect`, `useSession()`, `mysql2` 직접 import

### Client Component (*.tsx with 'use client')
- 반드시 파일 최상단에 `'use client'` 선언
- **신규 파일**: `*Client.tsx` / `*Form.tsx` 접미사 권장
- **레거시 파일** (`Header.tsx`, `ScrapButton.tsx`, `BootstrapLoader.tsx` 등): suffix 없이 유지 — 대량 rename 금지
- **금지**: `mysql2` import, `auth()` 직접 호출, DB 접근, `src/lib/*` (DB·서버 모듈)
- **허용 import**: `@/constants/*`, `@/lib/client/*`, `@/lib/admin/*-types.ts` (type-only), DB 없는 레거시 유틸
- **`import type` 원칙**: DB 함수 포함 서버 파일에서도 `import type`은 허용(타입 소거로 클라이언트 번들 유입 없음). **값(value) import는 금지** — `type` 키워드 없이 실제 함수·상수를 가져오면 mysql2가 클라이언트 번들에 유입됨. 타입만 필요하면 `*-types.ts` 분리 파일(있는 경우)을 우선 사용
- `useSession()` 사용 가능 (NextAuth v5 클라이언트 훅)

### Server / Client / Server Action 파일명

| 파일명 패턴 | 종류 | 필수 선언 |
|------------|------|----------|
| `page.tsx`, `layout.tsx` | Server Component | 없음 (기본값) |
| `*Client.tsx` | Client Component | `'use client'` 파일 최상단 |
| `*Form.tsx` | Client Component | `'use client'` 파일 최상단 |
| Button·Modal·Dialog·Loader·Header·Sidebar·Footer·Provider 류 | Client Component | `'use client'` 필수, suffix 생략 허용 |
| `actions.ts` (route 폴더 내) | Server Action | `'use server'` 함수/파일 최상단 |

- **신규 Client Component**: `*Client.tsx` 또는 `*Form.tsx` 접미사 권장 (예: `BoardListClient.tsx`, `AdminLoadForm.tsx`)
- **레거시 파일**: `Header.tsx`, `ScrapButton.tsx`, `BootstrapLoader.tsx` 등 기존 이름 유지 — suffix 없어도 OK, **대량 rename 금지** (~27개)
- UI 역할 컴포넌트(Button·Modal·Loader 등)는 suffix 생략 허용

### API Route (src/app/api/**/route.ts)
- DB 접근 허용 (서버 사이드)
- `auth()` 로 세션 체크
- 모든 사용자 입력: 타입 검증 + DB는 Prepared Statement만
- 동적 테이블명: WHITELIST 배열로 검증 필수

### src/lib/ (DB 함수, 공유 유틸)
- `mysql2/promise` pool 사용
- SQL은 Prepared Statement (`?` 플레이스홀더)
- 동적 테이블명: 화이트리스트 검증 후 사용
- **금지**: React import, HTTP 응답 객체

---

## NextAuth v5 규칙 — 이중 인스턴스

이 프로젝트는 NextAuth 인스턴스가 **두 개**다.

| 구분 | import 경로 | 쿠키명 | 세션 필드 |
|------|------------|--------|----------|
| 일반 사용자 | `@/auth` → `auth()` | `next-auth.session-token` | `no, uid, level, email` |
| 관리자 | `@/auth-admin` → `authAdmin()` | `admin-session` | `adminRole` |

```typescript
// ✅ 사용자 세션 (Server Component / API route)
import { auth } from '@/auth';
const session = await auth();
if (!session?.user) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

// ✅ 관리자 세션 (관리자 API route)
import { authAdmin } from '@/auth-admin';
const token = await authAdmin();
if (!token?.adminRole) return NextResponse.json({ error: 'Forbidden' }, { status: 401 });

// ✅ Client Component (사용자 세션)
import { useSession } from 'next-auth/react';
const { data: session } = useSession();

// ❌ 금지 (NextAuth v4 API)
import { getServerSession } from 'next-auth';
import { authOptions } from '@/auth';
const session = await getServerSession(authOptions);
```

**`src/middleware.ts`** (default export 함수명 `proxy` — `src/proxy.ts`는 존재하지 않음):
- `guardAdminRoute` + `admin-session` JWT로 `/auth/db`, `/auth/panel`, `/members` 보호
- Path / 1st·2nd gate·헤더 재설정 상세 → `.cursor/rules/nextjs-security.mdc` §관리자 경로 방어 심화

### 관리자 경로 방어 심화 (defense-in-depth)

Path / 1st·2nd gate 표 → `.cursor/rules/nextjs-security.mdc` §관리자 경로 방어 심화

API 핸들러: `/api/auth/[...nextauth]` (사용자) / `/api/admin-auth/[...nextauth]` (관리자)

---

## nginx 라우팅 (이중 서버)

- `/commentsNew/*`, `/travel/nightlife/*` → **PHP** (포트 80, try_files → index.php)
- `/login/*` → **Next.js** (:3000)
- `/auth/*` → **Next.js** (:3000) — nginx `location /auth/` proxy_pass :3000. 레거시 PHP `/auth/*.php`와 구분
- 그 외 → **Next.js** (:3000)
- nightlife 게시판은 **PHP 레거시 유지** — Next.js 코드에서 완전 제외

---

## 301 리다이렉트

`next.config.ts`의 `redirects()` 에 정의. PHP Router.php 536–573 라인 완전 이식.
주요 패턴: `/indonesia/*` → `/sea/indonesia/*`

---

## 금지 사항

- Controller (page.tsx)에서 SQL 직접 작성
- Client Component에서 DB import
- `getServerSession(authOptions)` 사용 (NextAuth v4)
- nightlife를 Next.js 라우트/카테고리 목록에 포함
- `dangerouslySetInnerHTML` without `DOMPurify.sanitize()`
- 동적 테이블명 whitelist 미검증 SQL

---

## RULE: nextjs-ai-prevention.mdc

# Next.js AI 오작동 방지 규칙

---

## 1. 기본 원칙

- **추측하지 않는다** — 확실하지 않으면 묻는다
- **삭제하지 않는다** — 명시적 요청 없이 파일/코드 삭제 금지
- **구조를 바꾸지 않는다** — App Router 폴더 구조 임의 변경 금지
- **요청 범위를 넘지 않는다** — 지시한 것만 수정

---

## 2. PHP 패리티 원칙

- PHP 기능이 없는 것을 추가하지 않는다 (Google 로그인 등)
- PHP 동작과 다르게 구현하지 않는다
- nightlife 게시판은 PHP 레거시 유지 — Next.js에 추가 금지
- 301 리다이렉트는 PHP Router.php와 동일하게

---

## 3. 금지 패턴

### 인증
```typescript
// ❌ NextAuth v4 API 절대 금지
import { authOptions } from '@/auth';
getServerSession(authOptions)

// ✅ NextAuth v5
import { auth } from '@/auth';
await auth()
```

### DB
```typescript
// ❌ Client Component에서 DB import 금지
'use client'
import pool from '@/lib/db';  // 금지!

// ❌ 문자열 보간 SQL 금지
`SELECT * FROM ${table} WHERE id = ${id}`

// ✅ 서버에서 Prepared Statement
await pool.query('SELECT * FROM ?? WHERE id = ?', [validatedTable, id]);
```

### 구조
```typescript
// ❌ page.tsx에서 SQL 직접 작성 금지
const [rows] = await pool.query('SELECT ...');  // page.tsx 내부

// ✅ lib 함수 호출
import { getPostById } from '@/lib/sea';
const post = await getPostById(id);
```

---

## 4. 패키지 임의 추가 금지

- `package.json` 수정은 명시적 요청 시만
- 새 npm 패키지 추가 전 Song에게 확인

---

## 5. 한 줄 요약

> PHP와 같게. 추측하지 말 것. 구조를 바꾸지 말 것. nightlife는 PHP에만.

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

