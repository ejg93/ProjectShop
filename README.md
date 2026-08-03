# ProjectShop

멀티 셀러 쇼핑몰. 판매자가 여럿 입점하고, 고객이 사고, 관리자가 관리한다.
목적은 물건을 파는 게 아니라 권한 체계를 정갈하게 짜는 법을 익히는 것이다.

만드는 대상과 근거는 `PLAN.md`, 어디까지 했는지는 `PROGRESS.md` 에 있다.

## 구성

| 경로 | 무엇이 들어 있나 |
|---|---|
| `backend/` | Spring Boot 서버. 권한 판정, 상품·주문 API, 모의 결제 |
| `frontend/` | Next.js 앱. 로그인, 상품·주문 화면, 관리자 권한 편집 |
| `docker-compose.yml` | PostgreSQL 컨테이너 정의 |

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

## 요구 사항

- Docker Desktop
- JDK 21 (청크 2부터)
- Node.js 20 이상 (청크 13부터)
