# Harness Rule Context — Harness Generator
- generated: 2026-08-03T22:24:38.272202+00:00
- role: generator
- type: (none)
- round: 2
- files: 5

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

## RULE: nextjs-performance.mdc

# Next.js 성능 규칙 (project1-next)

## 금지 패턴 (layout.tsx 수정 시 자동 확인)

### 1. 중복 CSS/JS 로드 금지
- Font Awesome: /css/font-awesome.min.css 외 추가 금지 (maxcdn/cdnjs Font Awesome 금지)
- jQuery: ajax.googleapis.com 1개만 유지
- Google Analytics: GA4(G-*) 1개만. UA- 형식 추가 절대 금지 (폐기됨)
- 새 <Script> 또는 <link> 추가 전: 반드시 layout.tsx 전체를 Read하여 동일 라이브러리 중복 여부 확인

### 2. beforeInteractive 남용 금지
- 허용: /js/fouc-prevention.js 1개만 (FOUC 방지)
- 금지: 위 외 모든 새 Script에 beforeInteractive 사용
- 기본값: strategy="afterInteractive" (사용자 인터랙션 전 필요) 또는 strategy="lazyOnload" (분석·위젯)

### 3. 외부 CDN 신규 도메인 추가 제한
승인된 도메인: ajax.googleapis.com, cdnjs.cloudflare.com, www.google.com, cse.google.com, apis.google.com, www.googletagmanager.com, cdn.jsdelivr.net, pagead2.googlesyndication.com, maps.googleapis.com, maps.gstatic.com, js.tosspayments.com
- 기존 승인 도메인 외 신규 추가 시 사용자 확인 후 진행
- 도메인 승인 후 반드시 함께 업데이트:
  1. 이 규칙 파일의 승인 도메인 목록
  2. next.config.ts images.remotePatterns (이미지 도메인인 경우)

### 4. <img> 태그 금지
- ❌ <img src="...">
- ✅ import Image from 'next/image'; <Image src="..." width={N} height={N} ... />
- PHP 프록시 이미지(/img/*, /forum_images/*): `unoptimized` prop 필수
  → 이유: Next.js Image optimizer는 next.config.ts remotePatterns에 없는 PHP 프록시 URL을 처리하지 못함
  → 대안: remotePatterns에 phpOrigin 등록 후 unoptimized 없이 사용 가능 (단, dev/prod URL 분기 필요)

### 5. layout.tsx 수정 시 AI 필수 실행 절차 (if/then)

IF layout.tsx에 새 <Script src="..."> 또는 <link href="..."> 추가 요청을 받으면:
  → THEN: layout.tsx 전체 Read → 동일 src URL 또는 라이브러리 존재 여부 확인
  → IF 이미 동일 라이브러리 존재:
    → THEN: 추가 차단 + "이미 N번째 줄에 로드됨" 사용자에게 알림
  → IF strategy="beforeInteractive" 요청:
    → THEN: 차단 + "beforeInteractive는 렌더링을 블로킹합니다. fouc-prevention.js 외 사용 금지. afterInteractive 또는 lazyOnload를 사용하세요." 안내
  → IF Font Awesome CDN URL(maxcdn, cdnjs FA):
    → THEN: 차단 + "로컬 /css/font-awesome.min.css(4.7.0)가 이미 로드됨. CDN 추가 금지."
  → IF UA- 형식 Analytics:
    → THEN: 차단 + "Universal Analytics는 2023년 폐기됨. GA4(G-*)만 사용."

IF <img src="..."> 추가 요청을 받으면:
  → THEN: 차단 + "next/image의 <Image> 컴포넌트를 사용하세요. PHP 프록시(/img/*, /forum_images/*)는 unoptimized prop 추가."

IF PHP 프록시 경로(/img/*, /forum_images/*)가 아닌 외부 이미지에 unoptimized prop 요청을 받으면:
  → THEN: "unoptimized는 Next.js 이미지 최적화를 완전히 비활성화합니다. 외부 도메인은 next.config.ts remotePatterns에 등록하고 unoptimized를 제거하세요."

## 위반 예시 (재발 금지)

| 위반 | 수정 |
|------|------|
| maxcdn.bootstrapcdn.com Font Awesome (중복) | 삭제 |
| UA-104707747-2 Universal Analytics (폐기) | 삭제 |
| jQuery beforeInteractive | afterInteractive |
| not-found.tsx `<img src="/img/404.jpg">` | `<Image unoptimized src="/img/404.jpg" width={400} height={400} style={{ height: "auto" }} />` |

## 스크립트 전략 결정 트리

새 Script 추가 시 다음 순서로 판단:
1. 첫 페인트 전에 DOM/body 클래스를 변경해야 하는가?
   → YES: beforeInteractive (팀 리뷰 필수 — 현재 허용: fouc-prevention.js 1개뿐)
   → NO: 다음 단계
2. 사용자가 페이지를 조작하기 전에 로드되어야 하는가? (폼, 드롭다운, jQuery 의존 기능)
   → YES: afterInteractive
   → NO: lazyOnload (GA4, 위젯, 핑거프린트 등)

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

