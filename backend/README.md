# backend

Spring Boot 서버. 권한 판정, 상품·주문 API, 모의 결제를 여기서 만든다.

- Spring Boot 4.1.0 / Java 25 / Gradle Kotlin DSL
- 스키마는 Flyway가 `src/main/resources/db/migration` 의 SQL을 번호순으로 적용한다
- JPA `ddl-auto` 는 `validate`. 엔티티와 실제 테이블이 어긋나면 기동이 실패한다

## 띄우기

DB가 먼저 떠 있어야 한다. 저장소 루트에서 `docker compose up -d`.

```bash
cd backend
./gradlew bootRun
```

`JAVA_HOME` 이 JDK 17 미만을 가리키면 Gradle이 안 뜬다.
그럴 때는 그 실행에만 값을 덮어쓴다.

```bash
JAVA_HOME="C:/Program Files/Java/jdk-25" ./gradlew bootRun
```

## 확인

```bash
curl localhost:8080/api/health
curl localhost:8080/actuator/health
```

`/api/health` 는 DB에 질의를 직접 던져서 접속한 DB 이름과 적용된 마이그레이션 수를 돌려준다.
커넥션은 잡혔는데 질의가 안 나가는 상태를 걸러내려는 것이다.

## 접속 정보

`application.yml` 이 환경변수를 읽고, 없으면 로컬 기본값으로 떨어진다.
루트 `.env` 와 이름을 맞춰 뒀다.

| 변수 | 기본값 |
|---|---|
| `DB_HOST` | `localhost` |
| `POSTGRES_PORT` | `5432` |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `shop` |
| `SERVER_PORT` | `8080`. 없으면 `PORT`(호스팅이 주입하는 이름)를 본다 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `REDIS_PASSWORD` | 빈 값(인증 없음). 관리형 Redis 는 넣어야 붙는다 |
| `SESSION_COOKIE_SECURE` | `false`. **https 로 올리면 `true` 가 필수다** — 안 켜면 세션 쿠키가 평문으로 흐른다. **프록시가 루프백 밖인데 꺼져 있으면 서버가 안 뜬다**(`Q44`) |
| `TRUSTED_PROXIES` | 루프백. `X-Forwarded-For` 를 믿어 줄 상대의 정규식. **아무 주소나 물게 넓히면 서버가 안 뜬다**(`Q44`, `SecuritySettingsCheck`) |
| `KAFKA_BOOTSTRAP` | `localhost:9092`. 브로커 주소. **켜져 있어도 발행기가 잠겨 있으면 안 쓰인다** |
| `STORAGE_ENDPOINT` | `http://localhost:9000`. 파일 저장소 주소. **배포는 Cloudflare R2 다**(사용자 결정 2026-09-18) — S3 호환이라 코드는 같고 이 값과 키 셋만 갈린다 |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | `shop` / `shopshop`. **배포는 R2 가 내주는 것을 넣는다** |
| `STORAGE_REGION` | `auto`. MinIO 는 안 보지만 SDK 가 서명할 때 값을 요구한다. R2 도 `auto` 를 받는다 |
| `STORAGE_PATH_STYLE` | `true`. `버킷.호스트` 대신 경로로 부른다 |
| `STORAGE_PUBLIC_BUCKET` / `STORAGE_PRIVATE_BUCKET` | `shop-public` / `shop-private`. 갈래마다 하나다(`media-rules.md`) |
| `STORAGE_BOOTSTRAP` | `false`. `true` 면 기동 뒤에 버킷 둘을 만든다. **켜는 것은 저장소를 띄운 로컬과 그 테스트뿐이다** |
| `EVENTS_SINK` | `none`. `kafka` 로 켜면 아웃박스 표의 사건이 브로커로 나간다. **배포는 `none` 이다** — 브로커를 안 올린다 |

## 호스팅에 올릴 때

`Dockerfile` 이 이미지를 만든다(빌드 문맥은 이 폴더 — Railway 면 Root Directory 를 `backend` 로).
로컬은 이 파일을 안 쓴다.

**위 표의 변수를 전부 배포 환경에 넣는다.** 안 넣은 것은 로컬 기본값으로 떨어져서
`localhost` 의 DB 를 찾다가 기동이 실패한다. Railway 의 관리형 Postgres·Redis 는
`PGHOST`·`PGPASSWORD`·`REDISHOST`·`REDISPASSWORD` 같은 이름으로 값을 주므로
그것을 위 이름으로 옮겨 적는다(`DB_HOST=${{Postgres.PGHOST}}` 식).

**`TRUSTED_PROXIES` 는 프록시가 어디 서느냐로 갈린다.**

| 화면(Next)이 어디 있나 | 값 | 왜 |
|---|---|---|
| 백엔드와 같은 사설망(Railway 안) | 그 망의 대역 | 그 대역 밖에서 온 `X-Forwarded-For` 는 안 믿는다 |
| 바깥(Vercel) | 못 좁힌다 — Vercel 의 나가는 IP 가 고정이 아니다 | 넓게 열면 백엔드 공개 주소를 직접 때리는 누구나 IP 를 속인다. `acted_ip` 가 동의 입증용이라(`application.yml` 주석) 이 구멍이 열린 채로 실사용자를 받지 않는다 |

**첫 줄로 간다**(`Q38`). `frontend/Dockerfile` 이 생겨서 화면도 같은 사설망에 올릴 수 있다 —
`docker build frontend` 로 이미지가 만들어지고, `BACKEND_ORIGIN` 을 그 망의 주소로 주면
**백엔드는 공개 도메인이 없어진다.** 둘째 줄(바깥)은 그때 안 고르는 것이 된다.

**실제 데이터가 있는 DB 에 올린 뒤에는 적용된 마이그레이션을 못 고친다**(`PLAN.md` `3e`·`Q36`).
데모라 실데이터가 없으면 스키마를 접을 때 그 DB 를 비우고 다시 올린다.
