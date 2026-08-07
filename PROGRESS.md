# 진행 로그

## 현재 상태

- **대상**: 멀티 셀러 쇼핑몰 (Next.js + Spring Boot, RBAC + 리소스 스코프, 로컬 전용)
- **진행중 청크**: 없음
- **다음에 할 것**: **5 → 8a → 4b-1**. 1차 점검이 정한 세로 관통 순서다. CSRF 벽(5b-0)은 뚫렸다.

  **POST 를 실제로 부를 수 있다** — `GET` 아무거나 한 번 치면 `XSRF-TOKEN` 쿠키가 오고,
  그 값을 `X-XSRF-TOKEN` 헤더에 실으면 지나간다. curl 로 가입까지 확인했다.

  **청크 5 (로그인·로그아웃)**: `formLogin` 을 껐으므로 세션 고정 방어가 자동으로 안 걸린다.
  `AuthenticationManager` 를 직접 부르고 `ChangeSessionIdAuthenticationStrategy` 도 직접 부른다 — 안 부르면 `D14` 요구가 조용히 빠진다.
  `InMemoryUserDetailsManager`(빈 저장소)를 DB 조회로 갈아 끼우는 자리이기도 하다.
  **새 경로는 `SecurityConfig.PUBLIC_PATHS` 에 넣어야 열린다** — 안 넣으면 401 이다. 가입·로그인이 거기 들어간다.
  **CSRF 가 켜져 있다** — POST 테스트는 `with(csrf())` 를 붙인다. 안 붙이면 4xx 가 나오는데 원인이 CSRF 로 안 보인다.
  로그인은 `InMemoryUserDetailsManager`(빈 저장소)를 DB 조회로 갈아 끼우는 자리다.
  가입 흐름이 지킬 것 둘: 필수 항목(`is_required`) 미동의는 거부, 야간 수신은 `depends_on_id` 가 가리키는 항목에 동의해야 받는다

### 문서 우선 방침

사용자가 **설계를 먼저 잡고 코드를 뒤에 하기로** 했다.
"다음 청크 해" 를 들어도 코드 청크를 잡기 전에 이 절을 먼저 본다.

문서에서 결정이 갈리는 지점은 **사용자에게 선택지를 제시하고 고르게 한다.**
기술 판단이라도 임의로 정하고 넘어가지 않는다. 정할 것이 없으면 그때 코드로 간다.

### 문서 진행 상태

| 문서 | 상태 | 파일 |
|---|---|---|
| D2 법·정책 요건표 | 완료 | `commerce-compliance.md` |
| D3 비즈니스 모델 | 완료 | `business-model.md` |
| D4 도메인 모델 | 완료 | `domain-model.md` |
| D5 API 설계 규약 | 완료 | `api-guidelines.md` |
| D6 권한 모델 명세 | 완료 | `permission-rules.md` |
| D8 금액·통화 | 완료 | `money-rules.md` |
| D9 식별자 | 완료 | `identifier-rules.md` |
| D13 데이터 수명 | 완료 | `data-lifecycle.md` |
| D15 테스트 전략 | 완료 | `testing-strategy.md` |
| D7 상태머신 | 완료 | `state-machines.md` |
| 스택·버전 (D 번호 없음) | 완료 | `stack.md` |
| D10 시각·영업일 | 완료 | `time-rules.md` |
| D1 문서 트리·용어집 | 완료 | `doc/README.md`, `glossary.md` |
| D14 보안 기준 | 완료 | `security-baseline.md` |
| D11 동시성 | 완료 | `concurrency-rules.md` |
| D12 이벤트 | 보류. 청크 29·32 에서 정한다 | |
| D16 관측 | 완료 | `observability-rules.md` |
| D17 파일 | 미착수. 청크 26 이 멀다 | |
| D18 알림 | 미착수. 청크 54 가 멀다 | |
| D19 배치 | 미착수. 청크 36 이 멀다 | |
| **D20 화면·문구** | 미착수. **미룬 것 중 제일 가깝다** — 청크 13 의 선행이 5·D5 뿐이다 | |
| D21 성능 목표 | 미착수. **측정값이 없어 지금 정하면 근거가 없다** | |

**문서 우선 방침이 여기서 끝났다.** 코드 청크를 막던 기준 문서가 다 나왔다.
남은 D12·D17~D21 은 해당 청크에서 정한다 — 지금 정하면 무엇을 정하는지 모르는 채로 정하게 된다.
D20 은 청크 13 을 잡을 때 같이 한다.

**결정에 붙은 전제를 각 문서가 같이 적었다.** 「로컬 전용」·「서버 1대」가 깨지면
`security-baseline.md`(세션·쿠키 `Secure`)와 `observability-rules.md`(로그 형식)를 다시 본다.

**문서가 여기서 끊겨도 코드로 갈 수 있다.** 다음 코드 청크는 4c 다.

**청크 16 을 할 때 반드시 볼 것** — 역할을 주거나 회수하는 모든 경로에서 `PermissionRuleLoader.evict(userId)`
를 부른다. 안 부르면 TTL 60초 동안 회수가 안 먹는다. 그걸 고정한 테스트가
`PermissionCacheTest.staleDecisionSurvivesWithoutEvict` 다.
- **마지막 갱신**: 2026-08-07

git 커밋 신원은 전역 `~/.gitconfig` 에 `EJG <64519398+ejg93@users.noreply.github.com>` 로 잡았다.
청크 3a 이전 커밋 4개는 플레이스홀더 이메일이라 GitHub 계정에 안 붙는다. 올릴 때 rebase 로 고칠지 정한다.

세션을 재개하면 이 블록만 보고 판단한다. 아래 이력은 필요할 때만 본다.

## 이력

| 날짜 | 청크 | 결과 | 커밋 |
|---|---|---|---|
| 2026-08-03 | 0. 저장소 뼈대 | 완료 — `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·`.gitignore` 생성 | 57841db |
| 2026-08-03 | 0-1. 대상 확정 | 완료 — 멀티 셀러 쇼핑몰로 확정, `PLAN.md` 재작성, 청크 16개 분할 | |
| 2026-08-03 | 1. 저장소 구조 + Docker Compose | 완료 — `docker-compose.yml`·`.env.example`·`README.md`·`backend/`·`frontend/` 생성. Postgres 17 컨테이너 `healthy` 확인, `psql` 접속 성공 | 19bfdcc |
| 2026-08-03 | 2. Spring Boot 기동 + DB 연결 | 완료 — Boot 4.1.0 / Java 25 / Gradle, `application.yml`, Flyway `V1__baseline.sql`, `GET /api/health`. `gradlew build`·`test` 통과, 기동 후 `/api/health` 가 `appliedMigrations: 1` 반환. ADR 0001·0002 기록 | 454af61 |
| 2026-08-03 | 3. 권한 스키마 | 완료 — `V2__auth_schema.sql`(app_user·role·permission·user_role·role_permission), `V3__auth_seed.sql`(역할 3, 권한 14, 매핑 30행). 기동 시 v3까지 적용, `/api/health` 가 `appliedMigrations: 3` 반환. ADR 0003 기록 | 47e0ebf |
| 2026-08-03 | 3-1. 계획 확장 | 완료 — 권한 축 4개 추가, 청크 9개(3a·3b·4a·4b·5a·11a·12a·16a·16b) 끼워 넣음. `doc/reference/permission-models.md` 에 Shopify·GitHub 모델 요약, ADR 0004 기록 | |
| 2026-08-04 | 3a. 셀러 조직 스키마 | 완료 — `V4__seller_org.sql`(seller·seller_member, role.is_org_role, user_role 대리키+seller_id, 소속·역할종류 검증 트리거). 기동 후 `/api/health` 가 `appliedMigrations: 4` 반환. psql 로 트리거 5케이스(소속없음·셀러누락·전역역할에셀러·정상부여·중복) 확인 후 테스트 행 삭제 | 1576aed |
| 2026-08-04 | 3a-1. 문서 축 추가 | 완료 — 문서·법 요건 청크 6개(D1·D2·2a·3c·10a·13a) 를 `PLAN.md` 에 끼워 넣고 청크 11 의존에 D2 추가. ADR 0005 기록. 문서 작성은 안 했다 | |
| 2026-08-04 | 3a-2. 보안·검증 축 추가 | 완료 — 청크 10개(4c·5b·5c·6b·7b·8a·10b·13b·2b·2c) 를 `PLAN.md` 에 끼워 넣음. 7b 응답 형식을 RFC 9457 로 고정. ADR 0006 기록. 코드 작성은 안 했다 | |
| 2026-08-04 | D2. 법·정책 요건표 | 완료 — `doc/reference/commerce-compliance.md` 에 요건 15개(R1~R15) 를 조문·설계 지점과 매핑. 청크 4·5 의 선행에 D2 추가(R8 컬럼 단위 판정, R7·R14 동의 이력 테이블). 조문 번호는 미검증이라 문서에 명시 | |
| 2026-08-04 | D2-1. 요건 반영 결정 | 완료 — R8·R15·R13 을 청크 4d·7c·11b 로 세우고 R4 를 청크 6·11a 에 보강. 이메일 파기·상태 이력·가격 정의·배송지 분리는 ADR 0007 로 확정 | |
| 2026-08-04 | 3b. 거부 규칙 스키마 | 완료 — `V5__deny_effect.sql`(role_permission.effect, PK 를 3열로 변경, 감사자 역할, 판매자 own 범위 deny). 기동 후 `/api/health` 가 `appliedMigrations: 5` 반환. psql 로 deny 10행 확인, seller 가 allow/seller + deny/own 동시 보유 확인, PK 중복·잘못된 effect 값 차단 확인 | 991866b |
| 2026-08-04 | 4. 권한 판정 엔진 | 완료 — `PermissionEvaluator`(deny 우선 훑기, seller 스코프를 부여 방식으로 가름, 판정 근거 문자열), `PermissionEvaluatorTest` 13개. `gradlew test` 전부 통과. 감사자가 새 권한으로 뚫리는 구멍을 `KnownHole` 테스트로 고정 | f082e3d |
| 2026-08-04 | 4d. 응답 필드 마스킹 | 완료 — `V6__field_visibility.sql`(permission_field_group·role_permission_field, 판매자·감사자의 order:read 에서 payment 제외), `Decision.visibleFieldGroups`, 허용 규칙을 전부 모으도록 판정 수정, `FieldVisibilityTest` 7개. 테스트 20개 전부 통과, `/api/health` 가 `appliedMigrations: 6` 반환 | 4ce52ec |
| 2026-08-05 | 4d-1. 문서 기준선 | 완료 — 기준 문서 21개(D1~D21) 를 `PLAN.md` 에 세우고 확장 청크 60개(17~76) 를 정식 청크로 올림. `doc/reference/document-map.md` 에 청크별 필요 문서 매핑, ADR 0008 기록. 문서 본문은 안 썼다 | |
| 2026-08-05 | D6. 권한 모델 명세 | 완료 — `doc/reference/permission-rules.md` 에 판정 순서·우선순위·스코프 해석·필드 그룹·역할×권한 매트릭스를 한 장으로. XACML 4결과와 대조해 Deny/NotApplicable 미구분과 Indeterminate 미정의를 7b 로 넘김. 알려진 구멍 4개 기록(감사자 뚫림, 액션 이름 의존, user 필드그룹 미연결, 목록 조회). D5 기준은 Zalando 로 확정 | |
| 2026-08-05 | D6-1. 바깥 참조 정리 | 완료 — `V7__user_field_groups.sql` 로 user 필드 그룹 미연결 수정(테스트 20개 통과, psql 확인). `doc/reference/external-references.md` 에 링크를 열어 확인하고 기록. 기준 자료 확정: D5=Zalando, D6=XACML 개념, D15=Test Pyramid+Spring Boot 4.1.0·Testcontainers 2.0.5, D3=Stripe Connect | d9f703f |
| 2026-08-05 | 4c-0. 판정 계산 분리 | 완료 — `PermissionEvaluator.evaluate` 를 static 으로 떼어 DB 없이 부를 수 있게 함. `PermissionRuleEvaluationTest` 16개 추가(Spring·DB 안 띄움). 케이스당 0.0012초로 통합 테스트의 0.04초 대비 30배 빠르다. 4c 매트릭스가 이 방식을 쓴다 | 9ac7548 |
| 2026-08-05 | D6-2. 참조 링크 확충 | 완료 — `external-references.md` 에 D1·D4·D8·D9·D11·D12·D14·D16·D17·D21 의 기준 자료를 링크 확인해 추가. 참조를 안 정한 6개(D7·D10·D13·D18·D19·D20)는 이유를 적음. 대안이 갈리던 셋을 확정: D14=OWASP Top 10(ASVS 는 무거워서 미룸), D16=Micrometer+traceparent 형식만(OpenTelemetry 는 62·63 에서), D12=보류 | |
| 2026-08-05 | D7·D10 부분 결정 | 완료 — ADR 0009. 공휴일은 `holiday` 테이블에 두고 임시공휴일을 배포 없이 추가한다. 주문 전이표는 도메인 규칙이라 코드에 선언하고 11a 의 상태×동작표도 같이 코드에 둔다. D13·D18·D19·D20 은 해당 청크에서 정한다 | |
| 2026-08-05 | D3. 비즈니스 모델 | 완료 — `doc/reference/business-model.md`. 수수료는 셀러 기본 + 상품 예외, 초기 일괄 10%(국내 오픈마켓 4~15% 의 가운데). 요율은 주문 시점 박제, 환불 시 수수료 반환, 정산 월 1회. 정산 후 환불의 음수 잔액 이월이 청크 17 요구사항이 됨. 청크 3c·10·17 에 반영 | |
| 2026-08-05 | D5. API 설계 규약 | 완료 — `doc/reference/api-guidelines.md`. Zalando 를 따르되 벗어난 것 2개를 맨 위에 명시(오프셋 페이징, 전체 개수 제공 — 둘 다 SHOULD). JSON 은 snake_case(MUST, Jackson 설정 한 줄로 DB 컬럼명과 일치). 마스킹된 필드는 생략하고 `_visible_field_groups` 로 알린다. 403·404 를 자원별로 정함. 상태 변경은 `POST /orders/{id}/cancel` 형태로만. 프론트는 받자마자 camelCase 로 바꿔 쓴다 — 변환 래퍼는 청크 13 | |
| 2026-08-05 | D8·D9. 금액·식별자 규약 | 완료 — `money-rules.md`(원 단위 정수 저장, 부가세 포함가, 수수료는 **항목별로 버림** — 부분 환불 때 다시 안 나누려고), `identifier-rules.md`(주문번호 = `20260805-4F2K91` 날짜+SecureRandom 난수 6자리, 혼동 문자 제외. 순번을 노출하면 독일 전차 문제로 거래량이 샌다). 청크 6·10 에 반영 | |
| 2026-08-05 | D4·D13. 도메인 모델·데이터 수명 | 완료 — `domain-model.md`·`data-lifecycle.md`. **수명(`deleted_at`)과 업무 상태(`status`)를 한 컬럼에 섞지 않는다**. 섞으면 복구할 때 이전 상태를 잃고 "살아 있는 것" 조건이 흔들린다. `V8__lifecycle_column.sql` 로 `app_user.withdrawn`·`seller.closed` 를 수명 컬럼으로 옮김. 테스트 36개 통과 | |
| 2026-08-05 | D15 + 청크 35. 테스트 전략·Testcontainers | 완료 — `testing-strategy.md`. 테스트가 Postgres 컨테이너를 직접 띄운다(`PostgresTestBase`). 로컬 DB 의존이 사라져 청크 35 를 앞당겨 끝냈다. JaCoCo 는 측정만 하고 목표를 안 둔다. 스냅샷은 마크다운 표, `src/test/resources/snapshots/`, `-Dsnapshot.update=true` 로만 갱신. **Testcontainers 1.21.4 미만은 Docker 29 에서 안 뜬다** — docker-java 가 API 1.32 를 잡는데 최소 지원이 1.44 다 |
| 2026-08-06 | D1. 문서 트리·용어집 | 완료 — `doc/README.md`(기준 문서와 ADR 을 참조자 수로 가른다, ADR 은 안 고치고 새 ADR 로 뒤집는다), `doc/reference/glossary.md`. 영문이 정본이고 한글은 대역. **`seller` 는 조직에만 쓴다** — `role.code='seller'` 를 `seller_owner` 로 바꾸는 것을 청크 3d 로 세웠고 `seller_staff` 는 5a 에서 생긴다. 헷갈리는 짝 7개(취소/반품/청약철회, 환불/취소, 상품/SKU 등)와 금지어 5개를 못박음 | a7c5b36 |
| 2026-08-06 | 3d. 역할 코드 rename | 완료 — `V9__rename_seller_role.sql` 로 `role.code` 를 `seller`→`seller_owner` 로. **main 코드는 안 고쳤다** — `PermissionEvaluator` 의 `seller` 는 전부 scope 값과 `sellerId` 라서 역할 코드 리터럴이 없었다. 테스트 3파일의 역할 코드 문자열과 금지어 '판매자' 표기만 바꿈. `gradlew test` 36개 통과. `seller_staff` 는 권한 범위가 안 정해져서 안 만들었다(5a) | 40bf67e |
| 2026-08-06 | D14. 보안 기준 | 완료 — `doc/reference/security-baseline.md`. OWASP Top 10 2021 열 항목을 청크에 매핑(해당 없는 A08 은 이유를 적음). 정한 것 넷: **bcrypt**(`{bcrypt}` 접두사가 있어 argon2 로 갈아탈 때 일괄 재해시가 없다), **비밀번호는 길이만 8~64 + ASCII 만**(NIST SP 800-63B. ASCII 제한이 bcrypt 72바이트 절단 구간을 없앤다 — 한글은 24자에서 닿는다), **서버 메모리 세션**(강제 로그아웃이 필요해지면 `SessionRegistry` → 재기동·다중서버가 필요해지면 JDBC 순), **(계정, IP) 5회/15분 차단**(계정만 잠그면 남 계정 잠그는 공격이 된다). 쿠키는 HttpOnly + SameSite=Lax, `Secure` 는 로컬 http 라 끈다. 분산 IP 방어는 청크 71 | 473036e |
| 2026-08-06 | D11. 동시성 규약 | 완료 — `doc/reference/concurrency-rules.md`. 격리 수준은 **Read Committed 그대로** — 올리면 모든 트랜잭션에 재시도 코드가 붙는다. 재고는 **조건부 UPDATE** 한 문장(`where stock >= :qty`). Postgres 가 잠금이 풀린 뒤 행을 다시 읽고 `where` 를 다시 평가해서 낡은 값으로 판단하는 경로가 없다. 대가는 0행의 이유를 따로 조회해야 하는 것. 여러 `sku` 는 **id 오름차순**으로 잠가 데드락을 막는다. 멱등키는 클라이언트가 만든 UUIDv4 를 `Idempotency-Key` 헤더로, 실패 응답도 저장하고 24시간 보관. 재시도는 `40001`·`40P01` 만 3회 | 64d307e |
| 2026-08-06 | D10. 시각·영업일 | 완료 — `doc/reference/time-rules.md`. **저장은 UTC(`timestamptz`), 업무 판단은 KST** — "8월 거래" 가 어느 쪽으로 세느냐에 따라 갈린다. API 는 `Z` 로 내리고 프론트가 바꾼다. 기간은 날짜 단위·초일 불산입·말일 24시, 말일이 쉬는 날이면 다음 영업일. **계산한 기한은 박제한다** — 임시공휴일이 나중에 추가되면 매번 계산하는 방식은 지난 기한까지 흔들린다. 영업일 계산은 말일 보정과 정산 지급일 둘뿐. 정산은 매월 10일. 배치는 04:00 KST 에 돌되 **기준 시각은 전날 24시로 고정** — 몇 시에 몇 번 돌든 결과가 같다 | 93f5e4a |
| 2026-08-06 | D16. 관측 규약 | 완료 — `doc/reference/observability-rules.md`. 감사 로그(4b)와 목적·보관 위치가 다르다는 것부터 가름. 추적 ID 는 `traceparent` 를 MDC 로, 로그엔 앞 6자리만, 응답은 **오류(RFC 9457 본문)에만** `trace_id`. 형식은 텍스트 — 수집기가 없어서 JSON 이 값을 못 한다. 프로필별로 안 가른다(안 돌려본 설정은 깨져 있다). **`ERROR` 는 사람이 개입할 것에만** — 4xx 는 INFO, 권한 거부도 INFO(13b 전까지 정상 거부가 잦다). 개인정보는 **식별자만 찍는다** — 마스킹은 새 필드를 빠뜨리면 조용히 새고, 안 찍으면 샐 게 없다. 객체·요청 본문 통째 로깅 금지 | 60afef5 |
| 2026-08-06 | 실서비스 비교 메모 | 완료 — `doc/notes/real-service-comparison.md`. 기준 문서도 ADR 도 아니라 `doc/notes/` 갈래를 냈고 **청크 실행 때 안 본다**를 `doc/README.md` 에 박음 | af235dd |
| 2026-08-06 | D14 보강. 캡차 | 완료 — reCAPTCHA v3 를 D14 에 절로 넣고 **청크 5d** 를 세움(선행 5c·13). 점수 0.5, `action` 검증 필수(안 보면 다른 페이지 토큰을 갖다 쓴다), **fail-open** — 캡차가 죽어도 비밀번호와 (계정,IP) 차단이 살아 있고, 막으면 남의 장애가 내 장애가 된다. `CaptchaVerifier` 인터페이스 뒤에 둬서 테스트가 외부를 안 탄다. **첫 외부 호출**이라 처리방침 제3자 제공을 13a 에 추가 | 1a0b1ce |
| 2026-08-06 | D7. 상태머신 | 완료 — `doc/reference/state-machines.md`. **주문을 두 층으로 쪼갰다** — `order`(결제) / `seller_order`(배송·취소·반품). 한 주문에 셀러가 여럿이면 A 배송완료·B 준비중이 성립하는데 상태가 한 군데면 표현이 안 된다. 셀러 권한(`scope=seller`)이 이 경계와 그대로 맞는다. 항목 단위 부분 취소는 안 한다. 재고는 결제 전에 잡고(`payment_pending`) 30분 뒤 만료 — 이 배치만 **5분마다** 돌아서 D10 의 04:00 규칙에서 예외다(판정이 경과 시간이라 고정 기준이 필요 없다). 자동확정 8일(청약철회 7일 다음날), 반품 접수된 건은 확정 대상에서 뺀다. **`delivered` 지나면 셀러 전이 권한이 닫힌다** — 안 닫으면 셀러가 청약철회 기산점을 조작한다 | e117ad2 |
| 2026-08-06 | 스택·버전 문서 | 완료 — `doc/reference/stack.md`. **API 레퍼런스가 아니다** — 메서드·설정 키를 옮겨 적으면 틀린 것이 굳고 낡는다. 적는 건 버전이 어느 파일에 박혀 있는지와 **기억으로 쓰면 틀리는 자리**뿐. **Boot 4 는 스타터 이름이 3.x 와 다르다**(`-webmvc`, `-flyway`, 테스트 스타터가 모듈별 분리) — 의존성 추가할 때 기억으로 쓰지 말고 `build.gradle.kts` 형태를 따른다. **`data-jpa` 스타터가 있는데 엔티티가 0개고 `JdbcClient` 만 6곳** — 엔티티가 있다고 가정하고 코드 쓰지 말 것. JPA 로 갈지는 청크 6 에서 정한다 | a2d1dc2 |
| 2026-08-06 | 4c. 권한 매트릭스 회귀 테스트 | 완료 — `PermissionMatrixTest`, `src/test/resources/snapshots/permission-matrix.md`. 표 3개(규칙 하나 12행, 규칙 여럿 8행, 판정 근거 36행)를 떠서 고정. **순서 바꾼 짝을 넣어서 deny 먼저 훑기가 실제로 동작하는 것을 고정**했다. 스냅샷이 진짜 잡는지 확인하려고 deny 훑기를 일부러 죽여 보고 실패하는 것을 봤다(원복 확인). 테스트 37개 통과. **목록 조회 스코프 누출은 못 했다** — 목록 쿼리가 없다. PLAN 에서 청크 8 로 옮김 | 214cddc |
| 2026-08-06 | 4a. 판정 캐시와 무효화 | 완료 — Caffeine(TTL 60초, 상한 1만), `PermissionCacheConfig`·`PermissionRuleLoader` 신설, `AuthFixture`·`PermissionCacheTest` 추가. **캐시하는 건 규칙·소속 조회 둘뿐이고 `Decision` 은 안 한다** — 키에 대상 행이 들어가면 행마다 키가 생기고 11a 의 상태 축이 붙으면 조용히 틀린다. **`@Cacheable` 이 private·자기호출에 안 먹어서** 조회를 별도 빈으로 뺐다(SQL 은 그대로 이동). 규칙 캐시 무효화는 Caffeine 에 키 패턴 삭제가 없어 통째로 비운다. 캐시를 꺼 보고 테스트 2개가 실패하는 것을 확인했다. 테스트 44개 통과 | 346dcdd |
| 2026-08-06 | 4a 중 드러난 것 | **`seller_owner` 는 전역 부여가 스키마에서 막힌다** — V4 트리거가 조직 역할에 셀러를 요구한다. `permission-rules.md` 의 "전역 부여 seller 스코프" 분기는 데이터가 없는 게 아니라 만들 수가 없다. 문서에 반영. **테스트 헬퍼가 두 벌 복제돼 있어서** 세 번째를 만드는 대신 `AuthFixture` 로 뺐고, 앞의 두 벌 이관은 청크 4a-1 로 세움 | |
| 2026-08-06 | 4a-1. 테스트 fixture 통합 | 완료 — `PermissionEvaluatorTest`·`FieldVisibilityTest` 의 복제 헬퍼를 `AuthFixture` 로 옮김. 테스트 코드 **126줄 삭제, 39줄 추가**. 테스트 44개 그대로 통과 — 동작이 안 바뀐 리팩터링이라 개수가 같은 것이 맞다 | ac98f67 |
| 2026-08-06 | 4b. 감사 로그 | 완료 — `V10__audit_log.sql`, `AuditLog`, `AuditLogTest`. **거부는 `decide()` 안에서 자동 기록** — 호출자가 부르면 새 API 에서 빠뜨려도 알 방법이 없다. 역할 변경 같은 사건은 호출자가 명시적으로 부른다. **업무 트랜잭션에 얹혀 간다**(롤백되면 같이 사라짐 — 안 일어난 일이 기록에 안 남게). **보존 3년** — `D13` 이 여기로 미뤄둔 결정. 주문 5년보다 짧은 건 `actor_user_id` 가 개인정보 파기의 예외라 예외 기간 자체가 비용이라서다. `actor_user_id` 에 **외래키를 안 건다** — 걸면 파기 배치가 이 행을 끌고 가거나 파기가 막힌다. 테스트 51개 통과, 기동 후 `appliedMigrations: 10` 확인, `\d audit_log` 로 인덱스 3개 확인 | 202115a |
| 2026-08-06 | 4b 중 드러난 것 | **Boot 4 는 Jackson 3 이다** — 패키지가 `com.fasterxml.jackson` → `tools.jackson` 으로 통째로 바뀌었고 `JacksonException` 이 unchecked 다. 컴파일 에러로 발견해서 `stack.md` 에 적었다. 애너테이션(`jackson-annotations`)만 아직 2.x 라 트리에 두 이름이 같이 보인다 | |
| 2026-08-07 | 5-0. 동의 이력 스키마 | 완료 — `V11__consent.sql`(`consent_item`·`user_consent`·`current_consent` 뷰), `ConsentSchemaTest` 11개. **상태가 아니라 사건을 적는다** — 철회를 update 로 갈면 철회 시점은 남고 동의 시점을 잃는데 R7 은 둘 다 요구한다. 항목은 `(code, version)` 이 한 행이라 개정해도 옛 판이 남는다(무엇에 동의했는지 입증). 마케팅은 채널별(이메일·문자)로 쪼갰다 — 하나로 두면 기존 동의가 어느 채널 것이었는지 몰라서 나중에 못 쪼갠다. 야간 수신은 `depends_on_id` 로 이메일에 걸었다. **`user_consent` 는 계정에 cascade** — `audit_log` 와 반대다. 감사는 계정이 없어져도 남아야 하지만 동의 이력은 개인정보 그 자체고 계약이 끝나면 입증할 상대가 없다(R9). 뷰 정렬에 `uc.id desc` 를 넣었다 — 한 트랜잭션 안에서는 `now()` 가 같은 값이라 `acted_at` 만으로 순서가 안 난다. 이 키를 `asc` 로 뒤집어 보고 해당 테스트 1개만 실패하는 것을 확인(원복). 테스트 62개 통과, 기동 후 `appliedMigrations: 11` 확인 | 5b8c339 |
| 2026-08-07 | 5-1. 인증 기반 | 완료 — `spring-boot-starter-security`(Security 7.1.0), `SecurityConfig`, `application.yml` 쿠키 절, `SecurityConfigTest` 11개. **기본이 `authenticated` 고 공개 경로만 `PUBLIC_PATHS` 목록에 적는다** — 기본이 열림이면 새 엔드포인트가 아무도 모르는 채로 공개된다. **폼 로그인·HTTP Basic 을 껐다** — 켜 두면 인증 없을 때 401 이 아니라 302 가 나가서 JSON 클라이언트가 HTML 을 받는다. **CSRF 는 안 끈다** — 5b 가 "켠다" 로 잡혀 있었지만 껐다 켜면 그 사이 엔드포인트가 토큰 없이 짜이고 켜는 순간 전부 막힌다. 5b 를 프론트 연동으로 고쳐 적었다. `InMemoryUserDetailsManager` 를 빈 채로 등록해 Boot 의 기본 계정·기동 로그의 무작위 비밀번호를 없앴다. 테스트 73개 통과. 기동해서 확인: `/api/health` 200, `/api/orders` 401 에 `Location` 헤더 없음, 열린 경로만 훑으면 세션 쿠키 안 생김, `Using generated security password` 로그 0건 | fdd1ec2 |
| 2026-08-07 | 5-1 중 드러난 것 | **Boot 4 는 자동설정 클래스도 모듈별로 옮겼다** — `ServerProperties` 가 `boot.web.server.autoconfigure`, `@AutoConfigureMockMvc` 가 `boot.webmvc.test.autoconfigure` 다. 기술 이름이 앞으로 나오고 `autoconfigure` 가 뒤로 간다. **토큰 없는 POST 의 거부 코드가 MockMvc(403)와 기동한 서버(401)에서 갈린다** — 이유는 안 밝혔고, 테스트는 코드를 못박는 대신 `is4xxClientError` 로 뒀다. 둘 다 `stack.md` 에 적었다 | |
| 2026-08-07 | **1차 점검** | 완료 — `doc/notes/checkpoint-1.md`. 청크 0~5-1 을 거시로 훑었다. **결함 2개를 고쳤다**: `PLAN.md` 160행이 깨져 청크 4 행이 3d 행 끝에 붙어 있었고(표를 훑는 재개 절차가 못 본다), 이력의 커밋 해시 4칸이 비어 청크와 커밋을 대조할 수 없었다. **가장 큰 관찰: 23% 왔는데 엔드포인트가 `/api/health` 하나뿐이다** — 판정 엔진·감사 로그·마스킹·캐시가 전부 호출자 없이 서 있고, 설계가 실제 요청 흐름에서 맞는지 확인된 적이 없다. 청크 규칙(파일 1~3개)은 커밋의 54%만 지켰다. 계획은 16 → 128 청크로 불었고 늘린 청크 5번 중 4번이 코드를 안 썼다. 2차 점검은 API 가 붙은 뒤에 한다 | d61ec48, 0b6aa86 |
| 2026-08-07 | 5-2. 회원가입 | 완료 — `SignupService`·`AuthController`·`AuthSignupTest` 17개, `application.yml` 에 JSON snake_case 와 Problem Details 를 켰다(`D5`). **계정·기본 역할·동의를 한 트랜잭션에 남긴다** — 계정만 생기고 동의가 빠지면 동의 없이 개인정보를 든 계정이 되고 나중에 알아볼 방법이 없다. 롤백을 테스트로 고정했다. **이메일 중복은 미리 조회하지 않고 유니크 인덱스가 던지는 것을 409 로 바꾼다** — 조회와 삽입 사이에 남이 끼어들 수 있어서 어차피 인덱스가 최종 판단이고, 둘 다 두면 같은 규칙이 두 군데가 된다. 비밀번호는 길이 8~64 + ASCII 만(`D14`). **`Location` 헤더를 안 붙였다** — `D5` 가 201 에 요구하지만 계정 조회 API 가 없어서 없는 경로를 가리키게 된다. 동의는 **담긴 것만** 기록해 거부(행 있음)와 안 건드림(행 없음)을 가른다. 테스트 90개 통과. 기동해서 `/api/health` 가 `applied_migrations` 로 바뀐 것을 확인하고 `CLAUDE.md` 검증 표를 같이 고쳤다 | |
| 2026-08-07 | 5-2 중 드러난 것 | **실제 서버에서 가입을 못 부른다** — CSRF 가 켜져 있는데 토큰을 받을 경로가 없어서 `curl` POST 가 401 이다. MockMvc 는 `with(csrf())` 로 우회하므로 테스트만으로는 안 드러난다. 5b 를 5 앞으로 당겨서 이 벽부터 뚫는다 | 1390a4c |
| 2026-08-07 | 5b-0. CSRF 토큰 발급 | 완료 — `SecurityConfig` 에 `CookieCsrfTokenRepository`·토큰 렌더링 필터·SPA 핸들러, `CsrfTokenTest` 6개. **설정 한 줄로 안 끝났다.** 저장소만 갈면 쿠키가 안 나간다 — 토큰이 지연 생성이라 아무도 안 읽으면 응답에 안 실린다(필터로 한 번 읽는다). 그걸 고쳐도 POST 가 막혔다 — 저장소는 쿠키에 **평문**을 넣는데 `XorCsrfTokenRequestAttributeHandler` 는 돌아온 값을 인코딩된 것으로 보고 디코딩한다. 내보낼 때만 XOR 를 쓰고 헤더로 온 값은 평문 비교하도록 갈랐다. 둘 다 실패를 눈으로 보고 고쳤고 `stack.md` 에 적었다. **세로 관통이 처음으로 실제로 됐다** — 기동한 서버에 curl 로 가입해서 201/`user_id`, 대소문자 다른 중복 409, 필수 동의 거부 422 를 전부 Problem Details 형식으로 받았고, psql 로 `app_user`·`user_role`·`user_consent`(source·acted_ip 포함)·`audit_log` 4곳에 들어간 것을 확인했다. 계정을 지워서 동의 cascade 도 실서버에서 확인했다. 테스트 96개 통과 | |
| 2026-08-07 | 5b-0 중 드러난 것 | **`current_consent` 뷰에 `source`·`acted_ip` 가 없다.** 현재 상태만 내리는 뷰라 의도한 모양이지만, 동의를 입증하려면 `user_consent` 원본을 봐야 한다. 뷰만 보고 입증하려 들면 근거가 빈다. **Git Bash 의 curl 로 한글 본문을 보내면 400 이 난다** — 로케일이 UTF-8 로 안 넘겨서다. 서버 문제가 아니라 MockMvc 로는 한글이 통과한다. curl 로 손검증할 때 이름은 ASCII 로 넣는다 | |
## 기록 규칙

청크를 끝내거나 중간에 멈출 때마다 위 두 곳을 같이 고친다.

- 완료했으면 「현재 상태」의 `다음에 할 것` 을 다음 청크로 바꾸고, 「이력」에 한 줄 추가한다
- 중간에 멈췄으면 `진행중 청크` 에 청크 번호를 적고, 남은 작업을 파일명 단위로 적는다.
  `나머지 마무리` 같은 뭉뚱그린 표현은 안 된다. 다음 세션의 내가 그걸 못 알아본다
