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
| `SPRING_PROFILES_ACTIVE` | 없음. **보여 주려고 올린 배포에는 `demo` 를 준다**(`Q134`) — 기본 프로필은 `db/migration` 만 봐서 `V900+` 시드가 통째로 안 돌고, 그러면 연습용 계정 아홉(`Q130`)과 데모 재고(`Q128`)가 그 DB 에 안 생긴다. 로컬에서 손으로 볼 때는 `local` 이다 |
| `TRUSTED_PROXIES` | 루프백. `X-Forwarded-For` 를 믿어 줄 상대의 정규식. **아무 주소나 물게 넓히면 서버가 안 뜬다**(`Q44`, `SecuritySettingsCheck`) |
| `KAFKA_BOOTSTRAP` | `localhost:9092`. 브로커 주소. **켜져 있어도 발행기가 잠겨 있으면 안 쓰인다** |
| `STORAGE_ENDPOINT` | `http://localhost:9000`. 파일 저장소 주소. **배포는 Cloudflare R2 다**(사용자 결정 2026-09-18) — S3 호환이라 코드는 같고 이 값과 키 셋만 갈린다 |
| `STORAGE_ACCESS_KEY` / `STORAGE_SECRET_KEY` | `shop` / `shopshop`. **배포는 R2 가 내주는 것을 넣는다** |
| `STORAGE_REGION` | `auto`. MinIO 는 안 보지만 SDK 가 서명할 때 값을 요구한다. R2 도 `auto` 를 받는다 |
| `STORAGE_PATH_STYLE` | `true`. `버킷.호스트` 대신 경로로 부른다 |
| `STORAGE_PUBLIC_BUCKET` / `STORAGE_PRIVATE_BUCKET` | `shop-public` / `shop-private`. 갈래마다 하나다(`media-rules.md`) |
| `STORAGE_BOOTSTRAP` | `false`. `true` 면 기동 뒤에 버킷 둘을 만든다. **켜는 것은 저장소를 띄운 로컬과 그 테스트뿐이다** |
| `EVENTS_SINK` | `none`. `kafka` 로 켜면 아웃박스 표의 사건이 브로커로 나간다. **배포는 `none` 이다** — 브로커를 안 올린다 |
| `RATE_LIMIT_ENABLED` | `true`. 끄면 로그인·가입 입구의 요청 제한 필터가 아예 안 붙는다(`SecurityConfig`). **배포에서 끄지 않는다** — 느린 레인이 401 자리에 429 를 받아서 끄는 값이라 시험 전용이다 |
| `RATE_LIMIT_KEY_PREFIX` | `rate:`. 제한 열쇠 앞에 붙는다. **관리형 Redis 를 남과 나눠 쓸 때만 고친다** — 세션의 `shop:session` 과 안 섞이게 갈라 둔 값이다 |
| `APP_PASSWORD_RESET_URL_TEMPLATE` | `http://localhost:3000/password-reset?token={token}`. 재설정 메일이 여는 화면(`Q180`). **지금은 발송기가 목업이라(`MockNotificationSender`) 메일이 안 나가서 배포에 영향이 없다** — 진짜 발송기를 붙이는 날 배포의 프론트 주소로 준다. 안 주면 메일의 링크가 받는 사람의 `localhost` 를 연다 |
| `APP_EMAIL_CHANGE_URL_TEMPLATE` | `http://localhost:3000/email-confirm?token={token}`. 이메일 변경 확인 메일이 여는 화면(`Q181`). 위 재설정과 같은 사정이다 — 발송기가 목업인 동안은 영향이 없고, 진짜 발송기를 붙이는 날 배포의 프론트 주소로 준다 |

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

### `BACKEND_ORIGIN` 은 빌드와 런타임 **둘 다**에 든다

**런타임에만 주면 화면의 조작이 전부 죽는다**(`Q116`). 경로가 둘이고 값을 읽는 시점이 다르다.

| 무엇이 부르나 | 어디서 값을 읽나 | 빠뜨리면 |
|---|---|---|
| 브라우저(`api()`) — 상대 경로라 Next 의 `rewrites()` 프록시를 탄다 | **빌드 때 굳는다.** Next 가 목적지를 `.next/routes-manifest.json` 에 박고 `standalone` 은 설정 파일을 안 진다 | **이미지 빌드가 선다.** 값이 없거나 루프백이면 `frontend/scripts/verify-proxy-target.mjs` 가 세운다 — 안 세우면 이미지가 로컬 주소를 지고 나가서 로그인·장바구니가 500 이고, 페이지는 떠서 성공처럼 보인다 |
| 서버(`apiPublic`·`apiSession`) | **런타임에 `process.env` 를 읽는다** | **컨테이너가 안 뜬다.** 없으면 진입점이 거절한다 — 그냥 두면 서버가 그리는 페이지만 500 이라 절반만 깨진 것으로 보인다 |

**Railway 는 서비스 변수 하나로 둘 다 덮는다** — 선언한 `ARG` 에 같은 이름의 변수를 빌드 때 넣어 주고,
런타임에도 환경변수로 들어간다. **`ARG` 선언이 그 전제다**(`frontend/Dockerfile`). 손으로 빌드할 때는 두 번 적는다.

```
docker build --build-arg BACKEND_ORIGIN=http://backend:8080 -t shop-frontend ./frontend
docker run -e BACKEND_ORIGIN=http://backend:8080 -p 3000:3000 shop-frontend
```

**루프백은 못 준다.** 컨테이너 안의 `localhost` 는 자기 자신이라 백엔드가 없다 — 그 값은 빌드가 거절한다.


### 머지하면 배포된다 — 2026-09-20 부터

**두 서비스가 `main` 에 붙어 있다.** 머지하면 알아서 올라간다.

**그전에는 안 됐고 왜 안 됐는지는 `stack.md` 「머지가 배포를 안 걸고 있었다」가 든다** —
손으로 거는 법, `redeploy` 가 왜 소용없는지, 건드리면 안 되는 연동까지 거기 한 자리에 있다.
여기 옮겨 적지 않는다(`Q89` — 사본은 원본이 바뀌어도 안 따라온다).

**배포가 나갔는지 보는 법**: `curl https://frontend-production-b83c.up.railway.app/api/health` 의
`applied_migrations` 가 **`db/migration` + `db/seed` + `db/seed-demo` 파일 수**와 같아야 한다.
**`db/seed-demo` 는 `demo` 프로필에만 실린다**(`Q143`) — 로컬(`local`)은 그만큼 적다.
`SPRING_PROFILES_ACTIVE=demo` 가 빠지면 시드 몫만큼 모자란다(`Q134`).

### 2026-09-20 에 실제로 한 순서

**첫 배포가 이 순서로 됐다**(`Q39`). 프로젝트 하나에 넷을 세운다 — Postgres·Redis·`backend`·`frontend`.

| 순서 | 무엇 | 걸린 것 |
|---|---|---|
| 1 | Postgres·Redis 템플릿 배포 | 각 10초 |
| 2 | 빈 서비스 둘을 만들고 **변수를 먼저 넣는다** | 소스를 붙이면 바로 빌드가 돌고, `BACKEND_ORIGIN` 이 없으면 그 빌드가 선다(`Q116`) |
| 3 | Root Directory 를 `backend`·`frontend` 로, backend 에 healthcheck `/actuator/health` | |
| 4 | 저장소를 붙인다(`main`) | 프론트 52초, 백엔드 2분 |
| 5 | **프론트에만** 공개 도메인 | 백엔드는 도메인을 안 만든다 |
| 6 | 데모 데이터를 붓는다 | 아래 |
| 7 | `deployed-baseline` 에 그때 커밋 해시를 적고 커밋 | 그 순간부터 마이그레이션 불변 게이트가 실제로 잰다 |
| 8 | **객체 저장소를 붙인다** | 아래 「열쇠는 사람이 받는다」. 안 하면 업로드가 통째로 안 된다 |

**포트를 서비스 변수로 못 박는다.** `PORT=8080`(backend)·`PORT=3000`(frontend). Railway 가 주입하는
`PORT` 가 `Dockerfile` 의 `ENV` 를 이기므로, 안 박으면 컨테이너와 도메인의 포트가 어긋나 **502** 다
(`stack.md` 「Railway 는 `PORT` 를 주입한다」).

**DB·Redis 는 참조로 건다** — `DB_HOST=${{Postgres.PGHOST}}` 식으로 전부. 주소를 손으로 적은 자리가 없다.

**`TRUSTED_PROXIES` 는 사설망 대역이다.** Railway 사설망이 IPv6 ULA 라 `fd[0-9a-f]{2}:[0-9a-f:]*` 를 썼고,
`SESSION_COOKIE_SECURE=true` 와 짝이다 — 안 켜면 `SecuritySettingsCheck` 가 기동을 세운다(`Q44`).

**데모 데이터를 붓는 자리에 사람 손이 든다.** 덤프는 로컬에서 뜨고 복구는 이 기계에서 도는데,
`postgres.railway.internal` 은 Railway 안에서만 닿는다. 그래서 **임시 TCP 프록시**를 열고 붓고 닫는다.
MCP 로는 비밀번호를 못 읽으므로(`valuesRedacted`) 그 한 줄은 사람이 돌린다.

```
# 1. 빈 DB 에 처음부터 올려 시드까지 채운다 — 쓰던 DB 는 V900+ 때문에 Flyway 가 막는다
docker compose exec -T db psql -U shop -d postgres -c 'create database shop_demo owner shop'
POSTGRES_DB=shop_demo ./gradlew bootRun --args='--spring.profiles.active=local'   # 뜨면 끈다

# 2. 뜬다
bash scripts/db-dump.sh "postgres://shop:shop@localhost:5432/shop_demo" build/demo.dump

# 3. 붓는다 — Git Bash 에서. WSL 은 docker 가 안 잡힌다
bash scripts/db-restore.sh build/demo.dump "postgresql://postgres:<PGPASSWORD>@<프록시>/railway"
```

**부은 DB 는 기본 프로필로 뜬다** — 시드가 `V900+` 로 적용돼 있어도 Flyway 가 막지 않는다(실측).


### 열쇠는 사람이 받는다 — 2026-09-20 에 한 것

**버킷은 아래 「버킷은 사람이 만든다」가 든다.** 여기는 **열쇠와 변수**만 적는다 —
같은 것을 두 절에 적으면 하나를 고칠 때 다른 하나가 안 따라온다(`Q89`).

| 무엇 | 값 |
|---|---|
| 쓰는 것 | Cloudflare R2(사용자 결정 2026-09-18). S3 호환이라 값만 갈린다 |
| 토큰 | R2 → Account Details → API Tokens → **Create Account API token**, 권한 **Object Read & Write**, 대상은 그 두 버킷 |
| 넣을 변수 | `STORAGE_ENDPOINT`(= 화면의 S3 API 주소) · `STORAGE_ACCESS_KEY` · `STORAGE_SECRET_KEY` |
| 안 넣는 것 | `STORAGE_REGION`·`STORAGE_PATH_STYLE`·버킷 이름. **기본값이 R2 에 맞다** |

**Secret Access Key 는 만든 화면을 벗어나면 다시 못 본다.** 놓치면 토큰을 새로 만든다.

**Account 쪽이지 User 쪽이 아니다.** User 토큰은 그 사람 계정에 묶여서 떠나면 죽는다 —
서버가 쓰는 자격증명을 사람 계정에 매달지 않는다(Cloudflare 도 Account 쪽을 권한다).

**됐는지 재는 법**: 판매자 세션으로 `POST /api/seller/products/{id}/images` 에 진짜 이미지를 올려
`201` 과 객체 키 둘이 오면 된다. `NoSuchBucket` 이면 버킷 이름이, `403` 이면 토큰 대상이 어긋난 것이다.

### 버킷은 사람이 만든다 — 첫 업로드가 `NoSuchBucket` 이 안 되게

**버킷 만들기는 기본이 꺼짐이다**(`STORAGE_BOOTSTRAP=false`). 켜는 것은 저장소를 띄운 로컬과
그 시험뿐이라, **배포에서는 그 코드가 안 돈다** — 안 만들고 올리면 첫 업로드가 `NoSuchBucket` 이다.

| 무엇 | 값 |
|---|---|
| 만들 버킷 | `shop-public` · `shop-private` (`STORAGE_PUBLIC_BUCKET`·`STORAGE_PRIVATE_BUCKET` 기본값) |
| 공개 접근 | **둘 다 끈다.** R2 는 기본이 비공개고 공개 개발 URL 을 따로 켜야 한다 — 켜지 않는다 |
| 여는 방법 | 앱이 내주는 만료 5분 서명 URL 하나뿐이다(`media-rules.md` 「여는 법」) |

**`STORAGE_BOOTSTRAP=true` 로 한 번 올려서 만들지 않는다.** 그 값이 켜진 채로 남으면
기동마다 저장소를 부르고, **저장소가 늦게 뜨는 날 기동이 같이 실패한다.**

### 올리고 나면 빈 쇼핑몰이 뜬다 — 데모 데이터를 부어야 한다

**기본 프로필은 시드를 안 읽는다.** `application.yml` 의 `locations` 가
`classpath:db/migration` 하나고 `db/seed` 는 거기 없다. `DemoOrderSeeder` 도
`@Profile("local")` 이라 안 돈다 — **그냥 올리면 상품 0·셀러 0·주문 0 이다.**

**프로필을 새로 안 가른다**(`Q37` 결정 — 프로필마다 설정이 갈리면 안 돌려본 조합이 생긴다).
대신 `64` 의 스크립트로 **로컬의 찬 DB 를 떠서 호스팅에 붓는다.**

| 순서 | 무엇을 |
|---|---|
| 1 | 로컬을 시드가 찬 상태로 만든다 — `local` 프로필로 한 번 띄우면 `V900+` 가 올라간다 |
| 2 | `bash scripts/db-dump.sh "" build/demo.dump` |
| 3 | `bash scripts/db-restore.sh build/demo.dump "postgres://…호스팅…"` |

**덤프는 코드와 짝이다.** 뜬 뒤에 마이그레이션 파일을 고치면 같은 덤프가 더는 안 올라간다
(`stack.md` 「덤프는 그때의 마이그레이션 판에 묶인다」) — **올리기 직전에 뜬다.**

**실제 데이터가 있는 DB 에 올린 뒤에는 적용된 마이그레이션을 못 고친다**(`PLAN.md` `3e`·`Q36`).
데모라 실데이터가 없으면 스키마를 접을 때 그 DB 를 비우고 다시 올린다.
