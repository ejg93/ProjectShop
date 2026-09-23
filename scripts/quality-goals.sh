#!/usr/bin/env bash
# 품질 목표의 지금 값을 파일에서 세어 찍고 기준선과 견준다(`D26`, doc/reference/quality-goals.md).
#
# **나쁜 수는 천장을 내리기만, 좋은 수는 바닥을 올리기만 한다** — doc-lint.sh 의 기준선과 같은 수다.
# 값이 좋아지면 「기준선 옮길 것」을 찍는다. 그때 아래 수를 그 값으로 옮긴다.
# 후퇴하면 0 이 아닌 값으로 끝난다.
set -u
cd "$(dirname "$0")/.."

fail=0

# 이름 · 지금 값 · 기준 · 방향(floor 는 바닥, ceiling 은 천장)
report() {
  local name=$1 now=$2 base=$3 dir=$4
  if [ "$dir" = floor ]; then
    echo "$name: $now (바닥 $base)"
    if [ "$now" -lt "$base" ]; then
      echo "  [목표 후퇴] 바닥 $base 밑으로 내려갔다"
      fail=1
    elif [ "$now" -gt "$base" ]; then
      echo "  [기준선 옮길 것] scripts/quality-goals.sh 의 바닥을 $now 로 올린다"
    fi
  else
    echo "$name: $now (천장 $base)"
    if [ "$now" -gt "$base" ]; then
      echo "  [목표 후퇴] 천장 $base 를 넘었다"
      fail=1
    elif [ "$now" -lt "$base" ]; then
      echo "  [기준선 옮길 것] scripts/quality-goals.sh 의 천장을 $now 로 내린다"
    fi
  fi
}

axe_files=$(( $(grep -rl 'expectNoAxeViolations' frontend/src --include='*.test.tsx' | wc -l) ))
report "접근성 검사가 걸린 화면 조각" "$axe_files" 15 floor

e2e=$(( $(cat frontend/e2e/*.spec.ts | grep -cE '^[[:space:]]*test\(') ))
report "E2E 시나리오" "$e2e" 3 floor

# req-coverage.sh 는 구멍이 있을 때만 그 수를 **stderr 로** 찍는다. 없으면 0 이다.
# stderr 를 버리면 그 줄을 못 읽어서 천장이 영영 안 빨개진다(마무리 48차 독립 리뷰).
holes=$(bash scripts/req-coverage.sh 2>&1 | sed -n 's/^테스트가 언급하지 않는 요건이 \([0-9][0-9]*\).*/\1/p')
report "시험 없는 법 요건" "${holes:-0}" 0 ceiling

exit $fail
