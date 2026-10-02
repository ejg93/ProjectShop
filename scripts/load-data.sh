#!/usr/bin/env bash
# 부하 데이터 10만 건을 로컬 컴포즈 Postgres 의 `shop_load` DB 에 붓는다(`70-0`). 측정(`42`)·부하(`70`)의 입력이다.
#
# **`shop` 을 틀로 복사한다**(`create database … template shop`). 마이그레이션·시드를 다시 안 돌려도 표와 데모 계정이
# 그대로 서고, 개발 DB 는 안 건드린다. 그래서 `shop` 이 최신 판이어야 하고 그 DB 에 붙은 연결이 없어야 한다.
#
# **지우기는 `--clean` 하나다** — 주문은 지울 길이 없어서(거래기록 5년) DB 를 통째로 버린다.
#
#   bash scripts/load-data.sh          # 붓는다. 이미 있으면 선다
#   bash scripts/load-data.sh --clean  # 버린다
set -euo pipefail

cd "$(dirname "$0")/.."

user=${POSTGRES_USER:-shop}
source_db=${POSTGRES_DB:-shop}
target_db=shop_load

psql_in() {
  local db=$1; shift
  docker compose exec -T db psql -U "$user" -d "$db" -v ON_ERROR_STOP=1 -Atq "$@"
}

if [ "${1:-}" = "--clean" ]; then
  psql_in postgres -c "drop database if exists $target_db with (force)"
  echo "$target_db 를 버렸다"
  exit 0
fi

if [ "$(docker compose ps --format '{{.Health}}' db 2>/dev/null)" != "healthy" ]; then
  echo "컴포즈 db 가 healthy 가 아니다 — docker compose up -d db" >&2
  exit 1
fi

# 틀이 최신 판인가. 시드(`V900+`)는 빼고 본다.
want=$(ls backend/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)
have=$(psql_in "$source_db" -c "select max(version::int) from flyway_schema_history where version ~ '^[0-9]+$' and version::int < 900")
if [ "$have" != "$want" ]; then
  echo "$source_db 가 V$have 이고 마이그레이션은 V$want 다 — bootRun(local) 을 한 번 돌려 올린 뒤 다시" >&2
  exit 1
fi

others=$(psql_in postgres -c "select count(*) from pg_stat_activity where datname = '$source_db'")
if [ "$others" != "0" ]; then
  echo "$source_db 에 연결이 $others 개 있다 — 틀 복사가 안 된다. bootRun 을 내린 뒤 다시" >&2
  exit 1
fi

exists=$(psql_in postgres -c "select count(*) from pg_database where datname = '$target_db'")
if [ "$exists" != "0" ]; then
  echo "$target_db 가 이미 있다 — bash scripts/load-data.sh --clean 뒤 다시" >&2
  exit 1
fi

started=$(date +%s)
psql_in postgres -c "create database $target_db template $source_db"
psql_in "$target_db" -1 -f - < load/data/load-100k.sql
# 재고 행이 낳은 사건이다. 바깥에 알릴 일이 아니라 비운다(시드·`DemoOrderSeeder` 와 같다).
psql_in "$target_db" -c "delete from outbox_event"
psql_in "$target_db" -c "vacuum analyze"
echo "부었다: $(( $(date +%s) - started ))초"

# 표 이름 순으로 찍는다 — 두 번 부은 출력을 `diff` 로 견준다(`70-0` ⑤).
psql_in "$target_db" -F '=' -c "
  select * from (
  select 'app_user', count(*) from app_user union all
  select 'product', count(*) from product union all
  select 'sku', count(*) from sku union all
  select 'shop_order', count(*) from shop_order union all
  select 'seller_order', count(*) from seller_order union all
  select 'order_item', count(*) from order_item union all
  select 'payment', count(*) from payment union all
  select 'refund', count(*) from refund
  ) as counts order by 1"
