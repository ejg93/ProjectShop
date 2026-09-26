#!/usr/bin/env bash
# 커밋 하나의 검사 하나가 끝나기를 기다린다(`Q230`). `bash scripts/wait-check.sh <sha> <검사 이름> [시간초=900] [간격초=30]`.
#
# **왜 있나**: Dependabot 자동 머지(`dependabot-automerge.yml`)는 가지 보호의 필수 검사 넷만 기다린다. `npm audit` 는
# 필수가 아닌 별도 잡(`audit`, `Q220`)이라 새 취약점을 들이는 npm 갱신도 넷이 초록이면 머지됐다. 이 스크립트가 npm 갱신에서
# `audit` 결론을 기다려 준다. 워크플로 안에 셸로 두지 않고 여기 둔 것은 시험하려고다 — `scripts/hooks-test.sh` 가 가짜 `gh` 로 잰다.
#
# 끝 코드: 0 성공(`success`) · 1 다른 결론(`failure`·`cancelled`·`timed_out`…) · 2 시간 안에 안 끝났거나 안 나타났다 · 3 쓰는 법.
# 저장소는 `GH_REPO` 가 있으면 그것, 없으면 `gh repo view`.
set -u
sha=${1:-}
name=${2:-}
timeout=${3:-900}
interval=${4:-30}
[ -n "$sha" ] && [ -n "$name" ] || { echo "쓰는 법: bash scripts/wait-check.sh <sha> <검사 이름> [시간초] [간격초]" >&2; exit 3; }
repo=${GH_REPO:-$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)}
[ -n "$repo" ] || { echo "저장소를 모른다 — GH_REPO 를 준다" >&2; exit 3; }

deadline=$(( $(date +%s) + timeout ))
state=
while :; do
  # 검사가 아직 안 붙었으면 `null null` 이다 — 기다린다
  state=$(gh api "repos/$repo/commits/$sha/check-runs?check_name=$name" \
    --jq '.check_runs[0] | "\(.status) \(.conclusion)"' 2>/dev/null)
  case "$state" in
    "completed success") echo "$name: success"; exit 0 ;;
    completed\ *) echo "$name: ${state#completed } — 성공이 아니다" >&2; exit 1 ;;
  esac
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "$name: ${timeout}초 안에 안 끝났다(마지막 상태: ${state:-없음})" >&2
    exit 2
  fi
  sleep "$interval"
done
