# ProjectShop

멀티 셀러 쇼핑몰. 판매자가 여럿 입점하고, 고객이 사고, 관리자가 관리한다.
목적은 물건을 파는 게 아니라 **규칙이 지켜지는 자리를 코드에서 가장 낮은 층으로 내리는 법**을 익히는 것이다.

## 무엇을 증명하나

**법 요건이 문서가 아니라 제약으로 서 있다.** 전자상거래법·개인정보법·세법에서 온 요건 **41개**에 번호를
달고(`doc/reference/commerce-compliance.md`), 데이터로 표현되는 것은 DB 제약으로 내렸다 —
청약철회 제한 사유는 `check` 로 값을 닫았고, 거래기록 5년 보존은 **`shop_order` 에 `deleted_at` 을 안 두는 구조**다.
어느 요건이 어디에 걸렸는지는 `bash scripts/req-coverage.sh` 가 센다.

**어디에 걸어야 실제로 막히나를 순서로 정했다.** 타입·스키마 > DB 제약 > 앱 검증 > 테스트 > 문서 순이고
(`doc/reference/coding-rules.md` 「규칙의 우선순위」), 그 순서 자체를 테스트가 지킨다 —
`ArchitectureTest` 가 계층·의존·순환을, `EnumConstraintTest` 가 열거형과 `check` 목록이 갈리는 것을 막는다.
**게이트마다 「무엇을 막는지」와 「부순 날」을 적어 둔다**(`doc/reference/quality-gates.md`) —
켜 두기만 하고 아무것도 안 막는 게이트를 가리려는 것이다.

**주문이 두 층이고 권한이 행 단위다.** 한 주문에 셀러가 여럿이라 `shop_order`(고객이 낸 것)와
`seller_order`(셀러가 처리하는 것)가 갈려 있고, 권한은 역할에 스코프(`own`·`seller`·`all`)를 붙여
**행 하나에 대해** 판정한다(`doc/reference/permission-rules.md`).

## 구조

```
  브라우저 ──▶ Next.js (3000)  ──rewrite──▶ Spring Boot (8080) ──▶ PostgreSQL 17
               화면·세션 쿠키       /api/*        판정·업무 규칙        제약·트리거
                                                        │
                                                        └──▶ Redis (세션 저장소)
```

백엔드 패키지는 **자원 단위 18개**다(`order`·`payment`·`product`·`auth` 등). 관객 단위로 안 판다 —
「셀러용」·「관리자용」으로 가르면 같은 자원의 규칙이 두 곳에 생긴다.

## 읽는 순서

| 무엇이 궁금하면 | 어디를 |
|---|---|
| 무엇을 만들고 있나 | `PLAN.md` 의 청크 분할표. 어디까지 했는지는 `PROGRESS.md` |
| 기준 문서가 어디 있나 | `doc/reference/document-map.md` — 28개 문서의 지도 |
| 왜 그렇게 정했나 | `doc/reference/coding-rules.md` 「규칙의 우선순위」. 충돌하면 무엇이 이기고 어디에 거나 |
| 무엇이 실제로 막고 있나 | `doc/reference/quality-gates.md` — 게이트별 문턱과 부순 증거 |
| 설계 결정의 저울질 | `doc/adr/` |

**돌아가는 화면은 아직 링크가 없다.** 배포는 마지막 청크고(`Q39`), 그때 주소가 여기 붙는다.

## 구성

| 경로 | 무엇이 들어 있나 |
|---|---|
| `backend/` | Spring Boot 서버. 권한 판정, 상품·주문 API, 모의 결제 |
| `frontend/` | Next.js 앱. 로그인, 상품·주문 화면, 관리자 권한 편집 |
| `docker-compose.yml` | PostgreSQL·Redis 컨테이너 정의 |
| `doc/reference/` | 기준 문서 28개. 규칙을 정하는 자리고, 코드는 여기를 따른다 |
| `doc/adr/` | 설계 결정 기록. 무엇을 정했고 무엇과 저울질했는지 |
| `scripts/` | 검증과 대조. `verify.sh`(레인을 골라 돌리고 도장) · `doc-lint.sh` · `req-coverage.sh` |

## DB 띄우기

접속 정보를 `.env.example` 에서 `.env` 로 복사한 뒤 컨테이너를 올린다.
`.env` 는 커밋되지 않으니 로컬에서 값을 바꿔도 저장소에 안 남는다.

```bash
cp .env.example .env
docker compose up -d
```

떴는지 확인한다. `healthy` 가 나와야 붙일 수 있다.

```bash
docker compose ps
```

내리기. 볼륨은 남아서 데이터가 보존된다.

```bash
docker compose down
```

데이터까지 지우려면 `docker compose down -v` 를 쓴다.
스키마가 꼬였을 때 처음부터 다시 만드는 용도다.

**`pg_stat_statements` 가 켜져 있다**(`42-0`). 느린 쿼리가 쌓여서 청크 `42`(성능 측정)가
올 때 읽을 것이 이미 있다. **이 저장소를 예전에 띄워 본 기계라면 한 번은 `down -v` 가 필요하다** —
확장을 만드는 스크립트가 **볼륨이 비었을 때만** 돌기 때문이다(`stack.md`).

```bash
docker exec shop-db psql -U shop -d shop -c "select count(*) from pg_stat_statements"
```

## 백엔드 띄우기

DB가 뜬 뒤에 실행한다. 자세한 건 `backend/README.md`.

```bash
cd backend
./gradlew bootRun
curl localhost:8080/api/health
```

## 요구 사항

- Docker Desktop
- JDK 25. `JAVA_HOME` 이 JDK 17 미만이면 Gradle이 안 뜬다
- Node.js 20 이상 (청크 13부터)

### 한 번만 켜는 것 — 컨테이너 재사용

`~/.testcontainers.properties` 에 아래 한 줄을 넣으면 느린 레인이 14초 줄어든다.

```
testcontainers.reuse.enable=true
```

**저장소가 아니라 기계에 붙는 설정이다.** 안 넣어도 테스트는 통과하고 경고만 나간다.
CI 러너에서는 효과가 없다 — 매번 새 기계라 재사용할 컨테이너가 없다.

## 라이선스

Apache-2.0 이다. 루트의 `LICENSE` 가 원문이다.

인용한 바깥 자료의 라이선스는 `doc/reference/external-references.md` 「라이선스」 표에 있다.
**그중 `naming-rules.md` 의 SQL 절 하나만 CC BY-SA 4.0 이고** 그 절 첫머리에 적혀 있다(`2l-2`).
