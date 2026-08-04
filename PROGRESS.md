# 진행 로그

## 현재 상태

- **대상**: 멀티 셀러 쇼핑몰 (Next.js + Spring Boot, RBAC + 리소스 스코프, 로컬 전용)
- **진행중 청크**: 없음
- **다음에 할 것**: 청크 3b — 거부 규칙 스키마 (또는 D1·2a·2b·2c. 전부 의존이 풀려 있다)
- **마지막 갱신**: 2026-08-04

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

## 기록 규칙

청크를 끝내거나 중간에 멈출 때마다 위 두 곳을 같이 고친다.

- 완료했으면 「현재 상태」의 `다음에 할 것` 을 다음 청크로 바꾸고, 「이력」에 한 줄 추가한다
- 중간에 멈췄으면 `진행중 청크` 에 청크 번호를 적고, 남은 작업을 파일명 단위로 적는다.
  `나머지 마무리` 같은 뭉뚱그린 표현은 안 된다. 다음 세션의 내가 그걸 못 알아본다
