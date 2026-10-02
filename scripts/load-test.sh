#!/usr/bin/env bash
# 목록 입구 여섯의 p95 를 재고 기준선과 견준다(`70`, `D21`). 기준선의 1.5배를 넘는 입구가 있으면 1 이다.
#
# **매번 `shop_load` 를 새로 붓는다.** 측정(`42`)처럼 배치를 돌린 DB 는 정산·확정이 늘어서 같은 질의도 다른 행 수를 읽는다 —
# 기준선은 갓 부은 상태에서만 견줄 수 있다. 붓기는 `shop` 이 최신 판이고 연결이 없어야 한다(`load-data.sh`).
#
# **대상은 컴포즈의 backend 이미지다**(`load` 프로필, 호스트 8081). `bootRun` 은 IDE·JIT 워밍이 수치를 흔든다.
# 짧은 예열 회차를 버리고 세 번 잰 중앙값을 쓴다(`load/compare.mjs`).
#
#   bash scripts/load-test.sh                    견준다
#   bash scripts/load-test.sh --update-baseline  기준선을 다시 쓴다 — `performance-goals.md` 표를 같은 커밋에서 고친다
set -euo pipefail

cd "$(dirname "$0")/.."

# Git Bash 가 `/load/lists.js` 를 Windows 경로로 바꿔 넘기지 않게 한다(`Q231` 의 경로 변환).
# **k6 줄에만 건다.** 전역으로 내보내면 `curl -o /dev/null` 의 `/dev/null` 도 안 바뀌어 curl 이 23 으로 죽고,
# 헬스 확인이 3분 내내 실패한다(2026-10-02 실측).
k6() {
  MSYS_NO_PATHCONV=1 docker compose --profile load run --rm "$@" k6 run --quiet /load/lists.js
}

stop_backend() {
  docker compose --profile load stop backend >/dev/null 2>&1 || true
}
trap stop_backend EXIT

# 이미지를 먼저 굽는다 — 붓고 나서 구우면 그 몇 분 동안 결제 대기 주문이 늙어 앱의 만료 작업(30분)에 가까워진다.
docker compose --profile load build backend

bash scripts/load-data.sh --clean
bash scripts/load-data.sh

docker compose --profile load up -d backend

echo "backend 를 기다린다(최대 3분)"
for i in $(seq 1 36); do
  if curl -sf -o /dev/null http://localhost:8081/api/health; then
    echo "떴다(약 $((i * 5))초)"
    break
  fi
  if [ "$i" -eq 36 ]; then
    echo "3분이 지나도 backend 가 안 떴다 — docker compose --profile load logs backend" >&2
    exit 1
  fi
  sleep 5
done

mkdir -p load/out
rm -f load/out/run-*.json

# 예열 — JIT 가 덜 돈 첫 몇 초를 기준에서 뺀다. 결과 파일 이름이 `run-<숫자>` 가 아니라 견주기에 안 든다.
k6 -e RUN=warmup -e DURATION=15s

for n in 1 2 3; do
  k6 -e RUN="$n"
done

node load/compare.mjs "$@"
