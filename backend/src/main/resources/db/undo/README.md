# 되돌리는 마이그레이션

Flyway 무료판에 `undo` 가 없다. 그래서 되돌리는 SQL 을 손으로 쓰고 **여기 둔다**(`65`).

## Flyway 가 안 읽는다

`application.yml` 의 `locations` 는 `classpath:db/migration` 하나다.
이 폴더는 그 밖이라 **자동으로 안 돌아간다** — 사람이 골라서 부어야 한다.

```bash
docker compose exec -T db psql -U shop -d shop \
  < backend/src/main/resources/db/undo/U79__copyright_report.sql
docker compose exec -T db psql -U shop -d shop \
  -c "delete from flyway_schema_history where version = '79'"
```

**둘째 줄을 빼먹으면 안 된다.** SQL 만 되돌리고 이력을 두면 Flyway 는 그 판이
아직 적용돼 있다고 보고, 다시 올릴 때 **아무것도 안 한다.**

## 어디까지 있나

**`V78` 부터다.** 그 앞은 안 쓴다 — 일흔일곱 개를 소급해서 쓰면
**쓰는 데 하루가 가고 아무도 안 읽는다.** 규칙은 지켜질 때만 규칙이다.

`MigrationUndoTest` 가 그 경계를 들고 있고, **새 `V` 를 더하면서 짝을 안 쓰면 빨개진다.**

## 무엇을 되돌리나

**스키마만 되돌린다. 데이터는 못 되돌린다** — `drop table` 로 사라진 행은
어디에도 안 남는다. 데이터까지 되돌리려면 `64` 의 덤프가 먼저 있어야 한다.

**되돌리기가 손실인 자리는 그렇게 적는다.** 「되돌릴 수 있다」와 「되돌려도 안전하다」는 다른 말이고,
파일 첫 줄에 무엇을 잃는지 쓴다.
