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
| `SERVER_PORT` | `8080` |
