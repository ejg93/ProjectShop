#!/usr/bin/env bash
# 머지 뒤 두 서비스가 그 커밋으로 떴는지 본다(`Q205`, quality-goals.md 「배포 건강」).
#
# **Railway 토큰 없이 잰다** — 두 서비스가 스스로 커밋을 말한다(백엔드 /api/health 의 commit, 프론트 /version).
# 둘이 origin/main 과 같아질 때까지 기다리고, 그다음 백엔드가 적용한 마이그레이션 수가 파일 수(V + 배포 시드)와
# 같은지 본다. 하나라도 다르면 빨강이다 — 헬스체크에 실패한 배포는 Railway 가 앞 판을 그대로 돌려서 조용하다(#70~#74).
#
# DEPLOY_BASE_URL     기본은 운영 프론트. /api/health 는 프론트가 백엔드로 넘긴다
# DEPLOY_WAIT_SECONDS 기본 600(10분)
set -u
cd "$(dirname "$0")/.."

base=${DEPLOY_BASE_URL:-https://frontend-production-b83c.up.railway.app}
wait_seconds=${DEPLOY_WAIT_SECONDS:-600}

git fetch -q origin main || { echo "빨강 — origin/main 을 못 받았다"; exit 1; }
want=$(git rev-parse origin/main)

# 배포 프로필은 demo 다 — db/seed 와 db/seed-demo 를 둘 다 붓는다(`Q143`).
db=backend/src/main/resources/db
expected=$(( $(ls "$db/migration" | grep -c '^V[0-9]*__') + $(ls "$db/seed" "$db/seed-demo" | grep -c '^V[0-9]*__') ))

commit_of() { # JSON 한 줄에서 commit 값을 꺼낸다
  sed -n 's/.*"commit":"\([^"]*\)".*/\1/p'
}

# 그 서비스의 뿌리가 그 커밋 뒤로 안 바뀌었으면 앞 커밋이 떠 있어도 같은 판이다(배포를 거르는 설정이 생겨도 거짓 빨강이 안 난다).
same_build() { # 떠 있는 커밋, 서비스 뿌리
  git merge-base --is-ancestor "$1" "$want" 2>/dev/null && git diff --quiet "$1" "$want" -- "$2"
}

deadline=$(( $(date +%s) + wait_seconds ))
while :; do
  health=$(curl -fsS -m 15 "$base/api/health" 2>/dev/null || true)
  version=$(curl -fsS -m 15 "$base/version" 2>/dev/null || true)
  backend=$(echo "$health" | commit_of)
  frontend=$(echo "$version" | commit_of)

  for pair in "백엔드:$backend" "프론트:$frontend"; do
    name=${pair%%:*}
    value=${pair#*:}
    if [ "$value" = "unknown" ]; then
      echo "빨강 — $name 판이 제 커밋을 모른다(unknown). RAILWAY_GIT_COMMIT_SHA 가 없는 판이다 — 로컬이거나 변수가 빠졌다"
      exit 1
    fi
  done

  if [ "$backend" = "$want" ] && [ "$frontend" = "$want" ]; then
    break
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    stale=""
    [ "$backend" = "$want" ] || same_build "$backend" backend || stale="$stale 백엔드(${backend:-응답 없음})"
    [ "$frontend" = "$want" ] || same_build "$frontend" frontend || stale="$stale 프론트(${frontend:-응답 없음})"
    if [ -n "$stale" ]; then
      echo "빨강 — ${wait_seconds}초를 기다려도 origin/main($want)이 아니다:$stale"
      exit 1
    fi
    break
  fi
  sleep 20
done

applied=$(echo "$health" | sed -n 's/.*"applied_migrations":\([0-9]*\).*/\1/p')
if [ "$applied" != "$expected" ]; then
  echo "빨강 — 적용한 마이그레이션 ${applied:-?} ≠ 파일 $expected (V + 배포 시드)"
  exit 1
fi

echo "초록 — 두 서비스가 $want 이고 마이그레이션 $applied 개가 파일 수와 같다"
