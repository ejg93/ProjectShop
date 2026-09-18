#!/usr/bin/env bash
# DB 를 통째로 떠서 파일 하나로 만든다(`64`).
#
# **마이그레이션은 데이터를 안 되살린다.** `V*.sql` 이 만드는 것은 표의 모양이고,
# 그 안에 있던 행은 어디에도 안 남는다 — 되살리려면 뜬 것이 있어야 한다.
#
# **대상을 인자로 받는다.** 로컬로만 뜨게 짜면 배포에서 같은 일을 다시 만든다 —
# `Q39` 가 이 스크립트에 호스팅 주소를 줘서 **데모 데이터를 붓는 데 쓴다**
# (기본 프로필은 `db/seed` 를 안 읽어서 그냥 올리면 빈 쇼핑몰이 뜬다).
#
#   bash scripts/db-dump.sh                          # 로컬 컴포즈
#   bash scripts/db-dump.sh "postgres://…" out.dump  # 다른 DB
set -euo pipefail

cd "$(dirname "$0")/.."

url=${1:-postgres://${POSTGRES_USER:-shop}:${POSTGRES_PASSWORD:-shop}@localhost:${POSTGRES_PORT:-5432}/${POSTGRES_DB:-shop}}
out=${2:-build/db-$(date +%Y%m%d-%H%M%S).dump}

mkdir -p "$(dirname "$out")"

# **커스텀 형식이다**(`-Fc`). 평문 SQL 보다 작고, `pg_restore` 가 표 단위로 골라 넣을 수 있다.
#
# **컨테이너 안에서 돈다.** 호스트에 `pg_dump` 가 깔려 있을 것을 전제하면
# 판이 갈려서 「server version mismatch」로 죽는다 — 컴포즈의 그 판을 그대로 쓴다.
docker compose exec -T db pg_dump -Fc --no-owner --no-privileges -d "$url" > "$out"

echo "떴다: $out ($(wc -c < "$out") 바이트)"
