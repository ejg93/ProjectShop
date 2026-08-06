# 진행 로그

## 현재 상태

- **대상**: 멀티 셀러 쇼핑몰 (Next.js + Spring Boot, RBAC + 리소스 스코프, 로컬 전용)
- **진행중 청크**: 없음
- **다음에 할 것**: **청크 4c (권한 매트릭스 회귀 테스트).** 코드 청크를 막던 문서가 다 끝났다.
  남은 것은 아래 「문서 진행 상태」를 본다. 문서가 끝나면 청크 4c 로 간다

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
| D7 상태머신 | **부분** — 전이표는 코드에 둔다(ADR 0009). 상태 목록·전이 주체·자동 전이가 남음 | |
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

**남은 것은 D7 잔여(상태 목록·전이 주체·자동 전이) 하나다.** 청크 11 이 쓴다.
나머지는 해당 청크가 멀어서 지금 정하면 무엇을 정하는지 모르는 채로 정하게 된다.
D20 은 청크 13 을 잡을 때 같이 한다.

**결정에 붙은 전제를 각 문서가 같이 적었다.** 「로컬 전용」·「서버 1대」가 깨지면
`security-baseline.md`(세션·쿠키 `Secure`)와 `observability-rules.md`(로그 형식)를 다시 본다.

**문서가 여기서 끊겨도 코드로 갈 수 있다.** 다음 코드 청크는 4c 다.

`PermissionEvaluator.decide` 가 매번 쿼리 2개를 던지므로 4a 캐시가 붙을 자리는 `loadRules` 와
`loadSellerMemberships` 다. 4d 에서 멤버십 조회를 항상 부르게 바꿔서 쿼리가 2개로 고정됐다.
- **마지막 갱신**: 2026-08-06

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
| 2026-08-06 | D14 보강. 캡차 | 완료 — reCAPTCHA v3 를 D14 에 절로 넣고 **청크 5d** 를 세움(선행 5c·13). 점수 0.5, `action` 검증 필수(안 보면 다른 페이지 토큰을 갖다 쓴다), **fail-open** — 캡차가 죽어도 비밀번호와 (계정,IP) 차단이 살아 있고, 막으면 남의 장애가 내 장애가 된다. `CaptchaVerifier` 인터페이스 뒤에 둬서 테스트가 외부를 안 탄다. **첫 외부 호출**이라 처리방침 제3자 제공을 13a 에 추가 | | |

## 기록 규칙

청크를 끝내거나 중간에 멈출 때마다 위 두 곳을 같이 고친다.

- 완료했으면 「현재 상태」의 `다음에 할 것` 을 다음 청크로 바꾸고, 「이력」에 한 줄 추가한다
- 중간에 멈췄으면 `진행중 청크` 에 청크 번호를 적고, 남은 작업을 파일명 단위로 적는다.
  `나머지 마무리` 같은 뭉뚱그린 표현은 안 된다. 다음 세션의 내가 그걸 못 알아본다
