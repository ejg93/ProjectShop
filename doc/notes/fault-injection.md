# 장애 주입 — 바깥 의존이 죽으면 앱이 어떻게 답하나

무엇을 죽였고 무엇이 어떻게 답했나를 적는 절차 파일이다(`68`). **같은 실험을 다시 돌려 「고쳤다」를 잰다** —
처분한 행(`Q260`~`Q263`)을 닫을 때 이 표의 「실제」 칸이 바뀌어야 닫힌다.

## 돌리는 법

대상은 컴포즈의 부하용 백엔드(`shop-backend-load`, 8081, `shop_load`)다 — `bootRun` 을 안 죽여도 된다.
**요청 제한이 꺼져 있다**(`RATE_LIMIT_ENABLED=false`) — 운영과 다른 자리라 Redis 실험의 공개 경로 줄은 코드로 보탠다.

1. `docker compose --profile load up -d --build backend` — `/api/health` 가 200 일 때까지
2. 탐침: 공개 목록(`GET /api/products?size=5`) · 이미 로그인한 세션의 내 주문(`GET /api/orders`, 손님 계정) ·
   새 로그인(`POST /api/auth/login`, **다른 계정** — 같은 계정이면 동시 세션 제한이 앞 세션을 내보내 401 로 오진한다) · `/api/health`.
   `curl -m 40` 으로 코드와 시간을 잰다
3. 죽인다 → 탐침 → 되살린다 → 탐침
4. **같은 컴포즈를 쓰는 세션이 있으면 먼저 알린다** — `db`·`redis` 를 멈추면 그 세션도 끊긴다

## 결과 — 2026-10-11

| 실험 | 죽인 것 | 요청 | 기대 | 실제 | 되살린 뒤 |
|---|---|---|---|---|---|
| A | `db` | 공개 목록 | 빨리 503 | **30초 뒤 500** — Hikari `connection-timeout` 기본 30초를 다 기다린다 | 4초 안에 풀이 스스로 회복, 200 |
| A | `db` | 내 주문(세션) | 빨리 503 | 30초 뒤 500. 세션은 안 버려진다 | 200 — 그 세션 그대로 |
| A | `db` | 새 로그인 | 503 | **30초 뒤 401** — DB 실패가 `InternalAuthenticationServiceException` 으로 와서 「비밀번호 틀림」과 같은 답이고 **`recordFailure` 가 센다** — 장애 동안 시도가 계정 잠금으로 쌓인다 | 200 |
| A | `db` | `/api/health` | 빨리 503 | 30초 뒤 500 — Railway 헬스체크도 30초를 기다린다 | 200 |
| B | `redis` | 공개 목록 | 산다 | 200, 13ms. 운영처럼 요청 제한이 켜져 있으면 `RateLimitFilter.count` 가 `DataAccessException` 을 0 으로 받아 통과시킨다(fail-open) — 요청마다 Redis 대기(약 2초)가 붙는다(코드로 읽음) | 200 |
| B | `redis` | 내 주문(세션) | 401 또는 503 | **4초 뒤 500** — 세션 조회가 `timeout: 2s` 를 두 번 | 200 — 세션 그대로 |
| B | `redis` | 새 로그인 | 503 | **6초 뒤 500** | 200 |
| B | `redis` | `/api/health` | 503 | **200** — Redis 를 안 본다. 로그인한 손님 요청이 다 실패하는데 건강하다고 답한다 | 200 |
| C | 백엔드 | 홈·상품·로그인·내 주문 화면 | `error.tsx`(「화면을 여는 데 실패했습니다」) | **넷 다 본문 없는 500**(`next start`) — 루트 레이아웃의 `SiteHeader` 가 `apiSessionOptional("/api/me/permissions")` 에서 `fetch failed`(ECONNREFUSED)로 던지고, 레이아웃 오류는 `error.tsx` 가 못 잡는데 `global-error.tsx` 가 없다. **로그인 화면까지 죽는다** | — |

**개발 서버(`npm run dev`)로 보지 않는다** — 오류를 JS 오버레이로만 그려 손님이 보는 화면과 다르다. `next build` 뒤 `next start` 로 본다.

## 처분

| 무엇 | 어디로 |
|---|---|
| DB 장애 때 모든 요청이 30초 매달린다 | 신규 `Q260` |
| DB 장애를 로그인 실패로 세어 계정이 잠긴다 | 신규 `Q261` |
| `/api/health` 가 Redis 를 안 본다 | 신규 `Q262` |
| 백엔드가 죽으면 프론트 전 화면이 본문 없는 500 | 신규 `Q263` |
| Redis 가 죽으면 요청 제한이 열린 채 요청마다 대기가 붙는다 | 정보 — `stack.md` 「바깥 의존이 죽으면」 |
