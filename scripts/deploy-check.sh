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
# **견주는 커밋에서 센다** — 작업 트리에서 세면 새 V 를 든 다음 가지에서 돌릴 때 거짓 빨강이 난다(마무리 48차 독립 리뷰).
db=backend/src/main/resources/db
expected=$(git ls-tree -r --name-only "$want" -- "$db/migration" "$db/seed" "$db/seed-demo" | grep -c '/V[0-9]*__')

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

# 사진 주소에서 서명을 떼면 저장소(R2)가 거절하나(`28`, `Q229`). 로컬 S3Mock 은 자격 증명을 안 봐서
# 이 반쪽을 못 재고, 우리 코드는 서명을 붙이는 것까지가 몫이다 — 저장소가 실제로 막는지는 여기서만 본다.
first_id=$(curl -fsS -m 15 "$base/api/products?size=1" 2>/dev/null | sed -n 's/.*"product_id":\([0-9]*\).*/\1/p' | head -1)
signed=$(curl -fsS -m 15 "$base/api/products/${first_id:-0}" 2>/dev/null | sed -n 's/.*"image_urls":\["\([^"]*\)".*/\1/p' | head -1)
if [ -z "$signed" ]; then
  echo "주의 — 사진을 든 상품이 없어 서명 뗀 주소를 못 쟀다"
else
  bare=$(echo "$signed" | sed 's/\\u0026/\&/g; s/?.*$//')
  code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$bare")
  # 「안 열린다」는 2xx 가 아니라는 뜻이다 — R2 는 서명이 없으면 400 InvalidArgument(Authorization) 이고 MinIO 였으면 403 이었다.
  case "$code" in
    2*) echo "빨강 — 서명을 뗀 사진 주소가 $code 로 열린다: $bare"; exit 1 ;;
    000) echo "빨강 — 서명을 뗀 사진 주소를 못 불렀다(시간 초과·DNS). 「안 열린다」와 「못 봤다」를 가른다: $bare"; exit 1 ;;
  esac
fi
echo "초록 — 두 서비스가 $want 이고 마이그레이션 $applied 개가 파일 수와 같다, 서명 뗀 사진 주소는 ${code:-안 잼} 이다"
