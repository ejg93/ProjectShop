# 장애 주입 — 바깥 의존이 죽으면 앱이 어떻게 답하나

무엇을 죽였고 무엇이 어떻게 답했나를 적는 절차 파일이다(`68`). **같은 실험을 다시 돌려 「고쳤다」를 잰다** —
처분한 행(`Q260`~`Q263`)을 닫을 때 이 표의 「실제」 칸이 바뀌어야 닫힌다.

## 돌리는 법

대상은 컴포즈의 부하용 백엔드(`shop-backend-load`, 8081, `shop_load`)다 — `bootRun` 을 안 죽여도 된다.
**요청 제한이 꺼져 있다**(`RATE_LIMIT_ENABLED=false`) — 운영과 다른 자리라 Redis 실험의 공개 경로 줄은 코드로 보탠다.

1. `docker compose --profile load up -d --build backend` — `/api/health` 가 200 일 때까지
2. 탐침: 공개 목록(`GET /api/products?limit=5`) · 이미 로그인한 세션의 내 주문(`GET /api/orders`, 손님 계정) ·
   새 로그인(`POST /api/auth/login`, **다른 계정** — 같은 계정이면 동시 세션 제한이 앞 세션을 내보내 401 로 오진한다) · `/api/health`.
   `curl -m 40` 으로 코드와 시간을 잰다
3. 죽인다 → 탐침 → 되살린다 → 탐침
4. **같은 컴포즈를 쓰는 세션이 있으면 먼저 알린다** — `db`·`redis` 를 멈추면 그 세션도 끊긴다

## 결과 — 2026-10-11

| 실험 | 죽인 것 | 요청 | 기대 | 실제 | 되살린 뒤 |
|---|---|---|---|---|---|
| A | `db` | 공개 목록 | 빨리 503 | **30초 뒤 500** — Hikari `connection-timeout` 기본 30초를 다 기다린다 → **2026-10-12 `Q260`: 3초 뒤 503**(`Retry-After: 5`, `ApiExceptionHandler`) | 4초 안에 풀이 스스로 회복, 200 |
| A | `db` | 내 주문(세션) | 빨리 503 | 30초 뒤 500. 세션은 안 버려진다 → **`Q260`: 3초 뒤 503**. 생존 확인이 캐시에 있으면 `ApiExceptionHandler` 가, 없으면(캐시가 빈 계정) 필터 앞에서 터져 `DependencyFailureFilter` 가 받는다 — 둘 다 쟀다 | 200 — 그 세션 그대로 |
| A | `db` | 새 로그인 | 503 | **30초 뒤 401** — DB 실패가 `InternalAuthenticationServiceException` 으로 와서 「비밀번호 틀림」과 같은 답이고 **`recordFailure` 가 센다** — 장애 동안 재시도가 그 이메일+주소의 15분 차단으로 쌓인다(`LoginAttemptService.key`) → **`Q261`: 3초 뒤 503, 안 센다**(`login:fail:*` 0) | 200 |
| A | `db` | `/api/health` | 빨리 503 | 30초 뒤 500 — Railway 헬스체크도 30초를 기다린다 → **`Q260`: 3초 뒤 503** | 200 — 되살린 뒤 2초 |
| B | `redis` | 공개 목록 | 산다 | 200, 13ms. 운영처럼 요청 제한이 켜져 있으면 `RateLimitFilter.count` 가 `DataAccessException` 을 0 으로 받아 통과시킨다(fail-open) — 요청마다 Redis 대기(약 2초)가 붙는다(코드로 읽음) | 200 |
| B | `redis` | 내 주문(세션) | 401 또는 503 | **4초 뒤 500** — 세션 조회가 `timeout: 2s` 를 두 번 → **2026-10-12 `Q262`: 2초 뒤 503**(`DependencyFailureFilter`). 그 사이 한 판은 톰캣 HTML 500 이었다 — 예외가 `QueryTimeoutException` 이라 `Q260` 의 판정에 없었다 | 200 — 세션 그대로 |
| B | `redis` | 새 로그인 | 503 | **6초 뒤 500** — 실패 카운터는 Redis 가 죽으면 `DataAccessException` 을 받아 넘어간다(`LoginAttemptService`). 6초는 차단 확인 2초 + 카운터 초기화 2초 + 세션 저장 실패로 읽힌다(로그 대조는 안 했다 — 추정) → **`Q262`: 6초 뒤 503** | 200 |
| B | `redis` | `/api/health` | 503 | **200** — Redis 를 안 본다. 로그인한 손님 요청이 다 실패하는데 건강하다고 답한다 → **`Q262`: 2초 뒤 503**(PING) | 200 — 되살린 뒤 1초 |
| C | 백엔드 | 홈·상품·로그인·내 주문 화면 | `error.tsx`(「화면을 여는 데 실패했습니다」) | **넷 다 본문 없는 500**(`next start`) — 루트 레이아웃의 `SiteHeader` 가 `apiSessionOptional("/api/me/permissions")` 에서 `fetch failed`(ECONNREFUSED)로 던지고, 레이아웃 오류는 `error.tsx` 가 못 잡는데 `global-error.tsx` 가 없다. **로그인 화면까지 죽는다** → **2026-10-12 `Q263`**: 넷 다 셸이 선다(머리는 비로그인) — 홈은 제 본문, 로그인은 폼, 상품·내 주문은 셸 안의 `error.tsx`(`loading.tsx` 가 셸을 먼저 흘려 200). 백엔드는 살고 DB 만 죽은 판(로그인한 손님)은 머리가 503 을 던져 `global-error.tsx` — 500, 우리 문구·`lang="ko"`·「다시 시도」 | — |

**`/actuator/health` 는 2026-10-11 에 못 쟀다** — 같은 컨테이너에 `63` 이 관리 포트(9091, 호스트에 안 묶임)를 줘서 actuator 가 8080 에서 빠졌다. 다시 돌릴 때는 `docker exec` 로 컨테이너 안에서 9091 을 부르거나 관리 포트를 빼고 띄운다. **2026-10-12 에 쟀다**: `docker run --rm --network container:shop-backend-load redis:7-alpine wget -qO- -S http://localhost:9091/actuator/health` — 평시 200, `db` 정지 503, `redis` 정지 503. Boot 기본 지표가 DB·Redis 를 둘 다 본다.

**개발 서버(`npm run dev`)로 보지 않는다** — 오류를 JS 오버레이로만 그려 손님이 보는 화면과 다르다. `next build` 뒤 `next start` 로 본다.

## 처분

| 무엇 | 어디로 |
|---|---|
| DB 장애 때 모든 요청이 30초 매달린다 | 신규 `Q260` |
| DB 장애를 로그인 실패로 세어 그 이메일+주소가 15분 막힌다 | 신규 `Q261` |
| `/api/health` 가 Redis 를 안 본다 | 신규 `Q262` |
| 백엔드가 죽으면 프론트 전 화면이 본문 없는 500 | 신규 `Q263` |
| Redis 가 죽으면 요청 제한이 열린 채 요청마다 대기가 붙는다 | 정보 — `stack.md` 「바깥 의존이 죽으면」 |
| DB 장애 동안 스케줄 작업이 ERROR 한 줄(스택)을 남긴다(2026-10-12 `Q260` 재실행) | 정보 — `stack.md` 「바깥 의존이 죽으면」 |
