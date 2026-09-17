#!/usr/bin/env bash
# 요건표(D2)의 R 번호마다 그것을 언급하는 테스트 파일을 세고, 0 인 R 을 낸다(`2v`).
#
# **게이트다**(`Q60`, 2026-09-17). 그전에는 리포트였다 — 12/41 이 0건인 채로 초록이었고,
# 그 열둘을 갈라 보니 **여섯은 라벨만 빠진 것**이고 하나는 행이 낡은 것이었다.
# 라벨을 붙이고 나니 남은 것이 전부 「테스트가 없는 것이 맞는」 갈래라, 기준선을 0 으로 잡는다.
#
# **제외는 네 갈래고 강제 지점 칸이 굵은 글로 선언한다.** 그 칸을 읽는 이유는
# 법 요건이 **코드에 흔적이 없어서**다 — 「있어야 할 것의 목록」에서 내려가야 보이고
# (coding-rules.md 「지켜지는지는 위에서 내려가며 확인한다」), 무엇이 없어도 되는지는
# 그 목록 자신이 근거와 함께 답해야 한다.
#
#   **미착수        아직 안 만들었다. 만드는 청크가 적혀 있다
#   **조건          바깥 조건이 안 찼다(고시·품목 지정 따위)
#   **일부러 안 다룬다   안 만들기로 정했고 근거가 코드나 ADR 에 있다
#   **문서뿐        막는 것이 문서(5위)뿐이라 테스트가 없는 것이 맞다
#
# **제외는 빠져나가는 구멍이 될 수 있다.** 그래서 갈래마다 수를 따로 찍는다 —
# 그 수가 조용히 느는 것을 사람이 보게 하는 것이 여기서 할 수 있는 전부고,
# 늘어난 것이 맞는지는 점검 가로·법 줄이 요건표를 열어서 묻는다.
set -uo pipefail
cd "$(dirname "$0")/.."

# **내리기만 한다.** 라벨을 붙이고 0 이 됐고, 0 이 아닌 값으로 올리려면
# 왜 그 R 이 테스트 없이 지나가야 하는지를 위 넷 중 하나로 요건표에 적는 편이 맞다.
req_uncovered_baseline=0

table=doc/reference/commerce-compliance.md
ids=$(grep -oE '^\| *R[0-9]+' "$table" | tr -d '| ')
files=$( (find backend/src/test -name '*.java'; \
          find frontend/src frontend/e2e \( -name '*.test.ts' -o -name '*.test.tsx' -o -name '*.spec.ts' \)) | sort)

total=0; missing=(); excluded=(); declare -A by_reason=()
printf '%-5s %s\n' 'R' '테스트 파일'
for id in $ids; do
  total=$((total+1))
  # -w: R1 이 R10 에 안 걸린다
  n=$(echo "$files" | xargs grep -lw "$id" 2>/dev/null | wc -l)
  printf '%-5s %s\n' "$id" "$n"
  [ "$n" -ne 0 ] && continue

  # 강제 지점은 넷째 칸이다. 굵은 글로 시작하는 선언만 본다 —
  # 본문 아무 데나 「미착수」가 있는 것으로는 안 빠진다.
  cell=$(grep -m1 "^| $id " "$table" | awk -F'|' '{print $5}' | sed 's/^ *//')
  reason=''
  case "$cell" in
    '**미착수'*)          reason='미착수' ;;
    '**조건'*)            reason='조건' ;;
    '**일부러 안 다룬다'*) reason='일부러 안 다룬다' ;;
    '**문서뿐'*)          reason='문서뿐' ;;
  esac

  if [ -n "$reason" ]; then
    excluded+=("$id")
    by_reason["$reason"]="${by_reason[$reason]:-}${by_reason[$reason]:+ }$id"
  else
    missing+=("$id")
  fi
done

echo
if [ "${#excluded[@]}" -ne 0 ]; then
  echo "제외 ${#excluded[@]} — 강제 지점 칸이 스스로 선언한 것들"
  for reason in '미착수' '조건' '일부러 안 다룬다' '문서뿐'; do
    [ -n "${by_reason[$reason]:-}" ] && printf '  %-14s %s\n' "$reason" "${by_reason[$reason]}"
  done
  echo
fi

covered=$((total - ${#missing[@]} - ${#excluded[@]}))
echo "테스트가 언급하는 요건: ${covered}/$((total - ${#excluded[@]})) (제외 ${#excluded[@]} 뺀 값)"

if [ "${#missing[@]}" -gt "$req_uncovered_baseline" ]; then
  echo
  echo "테스트가 언급하지 않는 요건이 ${#missing[@]} — 기준선 ${req_uncovered_baseline} 을 넘었다: ${missing[*]}" >&2
  echo "테스트에 그 R 번호를 적거나, 왜 테스트가 없어도 되는지를 요건표의 「강제 지점」 칸에" >&2
  echo "미착수 · 조건 · 일부러 안 다룬다 · 문서뿐 중 하나로 굵게 선언한다." >&2
  exit 1
fi

exit 0
