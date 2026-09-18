#!/usr/bin/env bash
# 뜬 파일을 DB 에 되붓는다(`64`).
#
# **「덤프가 있다」와 「되살아난다」는 다른 말이다.** 뜨기만 하고 안 부어 보면
# 부을 때 처음 알게 된다 — 그때는 원본이 없다.
#
#   bash scripts/db-restore.sh build/db-….dump               # 로컬 컴포즈
#   bash scripts/db-restore.sh build/db-….dump "postgres://…"  # 다른 DB
set -euo pipefail

cd "$(dirname "$0")/.."

dump=${1:?되부을 파일을 인자로 준다}
url=${2:-postgres://${POSTGRES_USER:-shop}:${POSTGRES_PASSWORD:-shop}@localhost:${POSTGRES_PORT:-5432}/${POSTGRES_DB:-shop}}

# **`--clean --if-exists` 다.** 빈 DB 에도 붓고 이미 찬 DB 에도 붓는다 —
# 붓기 전에 사람이 「지금 이 DB 가 비었나」를 판단하게 하면 그 판단이 틀리는 날이 온다.
#
# `--no-owner` 는 뜰 때와 짝이다. 호스팅은 역할 이름이 달라서, 안 주면
# 「role "shop" does not exist」로 전부 실패한다.
docker compose exec -T db pg_restore --clean --if-exists --no-owner --no-privileges \
    -d "$url" < "$dump"

echo "되살렸다: $dump"
