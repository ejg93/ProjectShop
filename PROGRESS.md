# 진행 로그

## 현재 상태

- **대상**: 멀티 셀러 쇼핑몰 (Next.js + Spring Boot, RBAC + 리소스 스코프, 로컬 전용)
- **진행중 청크**: 없음
- **다음에 할 것**: 청크 4 — 권한 판정 엔진
- **마지막 갱신**: 2026-08-03

세션을 재개하면 이 블록만 보고 판단한다. 아래 이력은 필요할 때만 본다.

## 이력

| 날짜 | 청크 | 결과 | 커밋 |
|---|---|---|---|
| 2026-08-03 | 0. 저장소 뼈대 | 완료 — `CLAUDE.md`·`PLAN.md`·`PROGRESS.md`·`.gitignore` 생성 | 57841db |
| 2026-08-03 | 0-1. 대상 확정 | 완료 — 멀티 셀러 쇼핑몰로 확정, `PLAN.md` 재작성, 청크 16개 분할 | |
| 2026-08-03 | 1. 저장소 구조 + Docker Compose | 완료 — `docker-compose.yml`·`.env.example`·`README.md`·`backend/`·`frontend/` 생성. Postgres 17 컨테이너 `healthy` 확인, `psql` 접속 성공 | 19bfdcc |
| 2026-08-03 | 2. Spring Boot 기동 + DB 연결 | 완료 — Boot 4.1.0 / Java 25 / Gradle, `application.yml`, Flyway `V1__baseline.sql`, `GET /api/health`. `gradlew build`·`test` 통과, 기동 후 `/api/health` 가 `appliedMigrations: 1` 반환. ADR 0001·0002 기록 | 454af61 |
| 2026-08-03 | 3. 권한 스키마 | 완료 — `V2__auth_schema.sql`(app_user·role·permission·user_role·role_permission), `V3__auth_seed.sql`(역할 3, 권한 14, 매핑 30행). 기동 시 v3까지 적용, `/api/health` 가 `appliedMigrations: 3` 반환. ADR 0003 기록 | |

## 기록 규칙

청크를 끝내거나 중간에 멈출 때마다 위 두 곳을 같이 고친다.

- 완료했으면 「현재 상태」의 `다음에 할 것` 을 다음 청크로 바꾸고, 「이력」에 한 줄 추가한다
- 중간에 멈췄으면 `진행중 청크` 에 청크 번호를 적고, 남은 작업을 파일명 단위로 적는다.
  `나머지 마무리` 같은 뭉뚱그린 표현은 안 된다. 다음 세션의 내가 그걸 못 알아본다
