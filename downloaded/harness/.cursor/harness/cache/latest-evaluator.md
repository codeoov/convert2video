# Harness Rule Context — Harness Evaluator
- generated: 2026-08-03T22:22:12.356914+00:00
- role: evaluator
- type: C
- round: 1
- files: 6

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

## RULE: nextjs-security.mdc

# Indosarang Next.js 보안 규칙

---

## 1. DB 접근 (SQL Injection 방지)

### 필수
- **mysql2 Prepared Statement**만 사용. `?` 플레이스홀더 + 배열 바인딩

### ❌ 금지
```typescript
// 문자열 보간 직접 삽입
await pool.query(`SELECT * FROM ${tableName} WHERE id = ${id}`);
await pool.query(`SELECT * FROM forum WHERE body LIKE '%${keyword}%'`);
```

### ✅ 올바른 예
```typescript
await pool.query('SELECT * FROM forum_indonesia WHERE id = ?', [id]);
await pool.query('SELECT * FROM forum_indonesia WHERE body LIKE ?', [`%${keyword}%`]);
```

### 동적 테이블명 (화이트리스트 필수)
```typescript
const ALLOWED_TABLES = ['forum_indonesia', 'bali', 'philippines'] as const;
if (!ALLOWED_TABLES.includes(tableName as any)) {
  throw new Error('Invalid table');
}
// tableName은 검증 후 template literal에서만 사용 가능
await pool.query(`SELECT * FROM \`${tableName}\` WHERE id = ?`, [id]);
```

---

## 2. XSS 방지

### HTML 출력 (dangerouslySetInnerHTML)
```typescript
// ❌ 금지 — XSS 위험
<div dangerouslySetInnerHTML={{ __html: post.body }} />

// ✅ DOMPurify 필수 (Client Component)
import DOMPurify from 'dompurify';
<div dangerouslySetInnerHTML={{ __html: DOMPurify.sanitize(post.body) }} />
```

### PHP 원본 대응
- PHP: `htmlspecialchars($body)` + `nl2br()`
- Next.js: `DOMPurify.sanitize(body)` (클라이언트) 또는 React 자동 이스케이프 (텍스트 노드)

---

## 3. 인증 (NextAuth v5 — 인스턴스 2개)

### 사용자 인증 (`src/auth.ts`)
```typescript
// ✅ Server Component / 사용자 API route
import { auth } from '@/auth';
const session = await auth();
if (!session?.user) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 });

// ✅ Client Component
import { useSession } from 'next-auth/react';
const { data: session } = useSession();

// ❌ 금지 (NextAuth v4)
import { getServerSession } from 'next-auth';
```

Client 파일명·`*Client.tsx` / `*Form.tsx` 규칙 → `nextjs-rule.mdc` § **Server / Client / Server Action 파일명** 표 참고.
- **레거시 Client rename 금지**: `Header.tsx`, `ScrapButton.tsx`, `BootstrapLoader.tsx` 등 기존 이름 유지 — suffix 없어도 OK, **대량 rename 금지** (~27개)

### 관리자 인증 (`src/auth-admin.ts`)
```typescript
// ✅ 관리자 API route (/api/admin/*)
import { authAdmin } from '@/auth-admin';
const token = await authAdmin();
if (!token?.adminRole) return NextResponse.json({ error: 'Forbidden' }, { status: 401 });

// src/middleware.ts (default export proxy) — 1st gate: guardAdminRoute + admin-session JWT
// src/proxy.ts 는 존재하지 않음. 문서·주석은 항상 src/middleware.ts 기준.
```

### 관리자 경로 방어 심화 (defense-in-depth)

| Path | 1st gate (`src/middleware.ts`) | 2nd gate |
|------|-------------------------------|----------|
| `/auth/db/*` | 미인증 404 / 인증 308→panel (`auth-db-redirect.ts`) | (없음 — App Router 삭제) |
| `/auth/panel/*` | 404 (`guardAdminRoute`) | `src/app/auth/panel/layout.tsx` — `x-admin-role-slug` → `redirect('/auth/')` |
| `/members/*` | 404 (`guardAdminRoute`) | `src/app/members/[publicId]/page.tsx` — `notFound()` |

- 1st gate: `getToken({ cookieName: 'admin-session', salt: 'admin-session', secret: ADMIN_AUTH_SECRET })` → `isValidAdminRole` 실패 시 **404** (302/리다이렉트 아님). `/auth/db` config redirects 금지
- `/auth/panel`·`/members` 유효 JWT: 클라이언트 제공 `x-admin-role-slug` 헤더 삭제 후 JWT 값으로 재설정 (헤더 스푸핑 방지)
- 2nd gate (panel/members): layout/page에서 세션·헤더 재검증 — 미들웨어 우회·캐시 꼬임 대비

### OAuth 연동 (`user_oauth_providers`)
```typescript
// ❌ 금지 — user 테이블에 직접 소셜 ID 저장
// user.google_id, user.kakao_id 컬럼은 2026-06-30 제거됨

// ✅ user_oauth_providers 테이블 경유
// findUserByGoogleId() → JOIN user_oauth_providers WHERE provider='google' AND provider_id=?
// findOrCreateGoogleUser() → BEGIN; INSERT user; INSERT user_oauth_providers; COMMIT;
// 신규 소셜 추가 시 ALTER TABLE user 불필요 — provider 값만 추가
```

### 인증 임시 토큰 (`user_auth_tokens`)
```typescript
// ❌ 금지 — user 테이블 컬럼에 토큰 저장
// user.certify_code, user.certify_expire, user.reset_token, user.reset_expire 컬럼은 2026-06-30 제거됨

// ✅ user_auth_tokens 테이블 경유
// type: 'email_verify' | 'password_reset'
// UNIQUE KEY uq_user_type (user_no, type) — 사용자당 타입별 1건
// 사용 완료 즉시 DELETE (markEmailVerified, updatePassword 내부 처리)
// createUser() → BEGIN; INSERT user; INSERT user_auth_tokens type='email_verify'; COMMIT;
```

### 관리자 레벨 체크 (사용자 세션 기반, 레거시)
```typescript
const level = (session?.user as any)?.level ?? 0;
if (level < 9) return NextResponse.json({ error: 'Forbidden' }, { status: 403 });
```

---

## 4. 스팸 처리 (단순화)

PHP SpamDetectionService는 Next.js에서 단순화:
```typescript
// 로그인 회원: spamcheck = 'no' (즉시 게시)
// 비로그인: spamcheck = 'yes' (관리자 승인 필요)
const isLoggedInMember = !!session?.user;
const spamcheck = isLoggedInMember ? 'no' : 'yes';
```

---

## 5. 비밀번호 (비회원 글쓰기)

```typescript
// ✅ bcrypt 해시 비교
import bcrypt from 'bcryptjs';
const isValid = await bcrypt.compare(inputPassword, post.upass);

// ❌ 평문 비교 금지
```

---

## 6. 환경변수 (민감 정보)

```typescript
// ✅ 서버 사이드에서만 접근
process.env.DATABASE_PASSWORD  // API route, lib/*.ts

// ❌ 클라이언트에 노출 금지
// NEXT_PUBLIC_ 접두사 없는 변수는 서버에서만 접근 가능
```

---

## 7. 입력 검증

API route 진입점에서 필수:
```typescript
const body = await req.json();
if (!body.subject || typeof body.subject !== 'string') {
  return NextResponse.json({ error: '제목을 입력하세요' }, { status: 400 });
}
const subject = body.subject.trim().slice(0, 200);
```

---

## 8. CSRF

NextAuth v5가 자체 CSRF 보호를 제공한다.
API route에서 `Origin` 헤더 검증은 선택적 추가 보호.

---

## 한 줄 요약

> mysql2 Prepared Statement 필수. DOMPurify로 HTML 정제. auth()로 세션. 환경변수로 민감 정보. 테이블명은 whitelist.

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

## RULE: server-logs.mdc

# 서버 로그 (Mac 로컬 · project1-next)

사용자가 errorlog·에러 로그·access log·로그 확인·`/error-logs`를 말하면 **아래 경로를 우선** 사용한다.

## Mac (Homebrew nginx)

| 용도 | 절대 경로 |
|------|-----------|
| Nginx 에러 | `/opt/homebrew/var/log/nginx/error.log` |
| Nginx 액세스 | `/opt/homebrew/var/log/nginx/access.log` |
| PHP-FPM | `/opt/homebrew/var/log/php-fpm.log` |

## Next.js

| 용도 | 확인 방법 |
|------|-----------|
| Next.js dev 서버 | 터미널 stdout (`next dev` 실행 중) |
| Next.js 빌드 오류 | `npx next build` 출력 |
| TypeScript 오류 | `npx tsc --noEmit` |
| nginx → Next.js 502 | nginx error.log에서 `502 Bad Gateway` 또는 `connect() failed` |

## 조회 절차

1. 최근 50줄: `tail -n 50 -- '/opt/homebrew/var/log/nginx/error.log'`
2. Next.js 관련만: `grep -E '3000|project1-next|502|504' /opt/homebrew/var/log/nginx/error.log | tail -n 20`
3. TypeScript: `cd /Users/songwoosub/Sites/project1-next && npx tsc --noEmit`
4. 비밀번호·쿠키·세션 전체값은 채팅에 붙여넣지 않는다

## Harness (evaluator)

매 Round: `bash .cursor/hooks/run-harness-checks.sh`
- `HARNESS_CONTRACT_FILES` = comma-separated TypeScript 파일 경로
- `HARNESS_STRICT_WARNINGS=1`
- TypeScript `error TS` → exit 2 → **FAIL**
- nginx 502/504 → exit 2 → **FAIL**
- **PASS**: Round **3+** 만

---

## RULE: nextjs-dev-cache.mdc

# Next.js dev 캐시 초기화 (project1-next)

## 언제 할지

아래를 **수정·추가·삭제**한 뒤, 구현·스프린트·검증을 마치기 **전에** 실행한다.

- `src/app/**` (page, layout, route.ts, 라우트 폴더 이동)
- `src/app/api/**`
- `src/auth.ts`, `src/auth.config.ts`, `src/auth-admin.ts`, `src/auth-admin.config.ts`
- `src/middleware.ts`
- `next.config.ts`

**생략**: CSS·문자열·주석만 바꾼 경우, `src/lib/**`·`src/components/**`만 바꾼 경우.

## 절차 (고정)

```bash
# dev 서버가 켜져 있으면 먼저 중지한 뒤
rm -rf .next
npm run dev
```

Harness evaluator·브라우저 확인 **전에** 위를 한 번 돌린다. 코드만 고치고 `.next`를 남기지 않는다.

## 확인 (Auth/API 꼬임 여부)

```bash
curl -s http://localhost:3000/api/auth/session
```

- **정상**: `null` 또는 `{"user":...}` (JSON)
- **비정상**: `<!DOCTYPE` 로 시작하는 HTML → `.next` 삭제·재시작이 안 된 것. 다시 절차 반복.

## 증상 (이 규칙이 막는 것)

`Unexpected token '<', "<!DOCTYPE "... is not valid JSON` (Auth.js) — 대부분 `/api/auth/session`이 404 HTML을 반환할 때 발생. 코드 버그가 아니라 **dev 캐시 꼬임**인 경우가 많다.

---

## RULE: db-migration-verification.mdc

# DB 마이그레이션 실행 검증 (project1-next)

`_up.sql` 마이그레이션 **파일을 작성하는 것**과 **실제로 로컬 DB에 적용하는 것**은 별개 작업이다. 파일만 만들고 실행을 빼먹으면, 그 컬럼/테이블이 있다고 전제하고 짠 코드가 런타임에 전부 실패한다 — 그리고 그 코드가 fail-closed(에러 시 차단)로 짜여 있으면, 실제 요청을 날려보기 전까지는 "사이트 기능이 통째로 막혔다"는 사실 자체를 알아채기 어렵다.

## 필수 (스키마를 바꾸는 스프린트라면 하나라도 빠지면 미완료)

1. `_up.sql`을 작성했으면 **그 자리에서 로컬 DB에 실행**한다.
2. 실행 후 **`SHOW COLUMNS FROM <table>;`** 또는 **`SHOW CREATE TABLE <table>;`**로 스키마가 실제로 바뀌었는지 재확인한다. SQL 파일 내용을 눈으로 읽는 것은 확인이 아니다.
3. 그 스키마에 의존하는 함수·라우트가 있다면, **실제 요청(curl 등)으로 한 번 실행**해서 새 컬럼/테이블을 참조하는 쿼리가 에러 없이 도는지 확인한다 — 플랜의 "검증" 절에 적힌 curl 명령을 실제로 실행하고 응답을 확인할 것.
4. 새 컬럼에 fail-closed 분기(에러 시 차단/거부)가 걸려 있는 코드라면 특히 주의한다. 마이그레이션 누락은 "기능이 안 됨"이 아니라 "관련 기능 전체가 조용히 막힘"으로 나타나서 발견이 늦어진다.
5. **Raw output 증거 필수**: "적용했습니다", "테스트 통과했습니다" 같은 말로만 된 완료 선언은 인정하지 않는다. `SHOW COLUMNS`/`SHOW CREATE TABLE`/curl 명령을 실행한 **실제 터미널 출력(stdout/stderr)을 그대로 응답에 첨부**해야 완료로 인정한다. 요약·의역 금지 — 컬럼이 실제로 목록에 보이는지, curl 응답이 실제로 무엇이었는지 원문 그대로.
6. **에러 발견 시 셀프 교정 (최대 2회)**: 검증 중 에러(예: 403, `Unknown column`)가 나오면 사용자에게 보고하기 전에 **직접 원인을 고치고 재검증**한다. 2회 시도 후에도 실패하면 그 이상 반복하지 말고, 시도한 내용 + 마지막 raw 에러 출력과 함께 **블로커로 보고**한다. (무제한 재시도는 토큰만 태우고 수렴 안 될 수 있음 — 2회가 상한)

## 권장

- 게스트/로그인/관리자 등 세션 상태별로 나눠 curl 검증 (플랜에 명시된 케이스 전부)
- `_rollback.sql`도 실제로 한 번 되돌려서 구문 에러가 없는지 확인

## 확인 절차 (그대로 복사해 사용)

```bash
source .env.local

# 1) 사전 가드 — 컬럼/테이블이 이미 있는지
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
  -e "SHOW COLUMNS FROM <table> LIKE '<column>';"

# 2) 마이그레이션 실행
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
  < dev-records/<날짜>_<이름>_up.sql

# 3) 사후 확인 — 결과에 새 컬럼/테이블이 실제로 보여야 함
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASSWORD" "$DB_NAME" \
  -e "SHOW COLUMNS FROM <table>;"

# 4) 의존 라우트 실제 호출 (예시)
curl -s -i -X POST http://localhost:3000/<api-route> -H 'Content-Type: application/json' -d '{...}'
```

DB 접속 정보는 `.env.local`의 `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`.

## 위반 예시 (실제 발생, 재발 금지)

| 상황 | 결과 | 원인 |
|------|------|------|
| `ablocked_ips`에 `restriction_type` 컬럼을 추가하는 `_up.sql`을 작성했지만 로컬 DB에 실행하지 않음 | 컬럼을 참조하는 `isIpBlocked`/`getIpRestriction` 쿼리가 전부 `Unknown column` 에러 → fail-closed로 "차단"으로 간주 → 게스트·로그인 사용자 전원 글쓰기/댓글 작성 403 | 파일 작성 = 완료로 착각, `SHOW COLUMNS` 재확인과 curl 검증을 생략 |

## Harness (generator/evaluator) 연동

- **generator**는 `Bash` 툴이 없어 마이그레이션을 직접 실행할 수 없다 — `_up.sql` 작성 후 리포트에 "DB Contract, evaluator 실행 필요"를 명시한다.
- **evaluator**는 DB Contract가 있는 라운드에서 위 확인 절차를 실제로 실행하고, 실패 시 FAIL로 처리한다 (`evaluator.md` "## DB" 절 참조).
- Harness가 아닌 **일반 채팅/에이전트 모드**(플랜을 붙여넣고 바로 구현시키는 경우 포함)에서도 이 5·6번 항목은 동일하게 적용된다 — Bash 권한이 있는 에이전트라면 역할과 무관하게 raw output 증거 + 2회 상한 셀프 교정을 지킨다.

---

