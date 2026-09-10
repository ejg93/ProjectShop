#!/usr/bin/env bash
# 요건표(D2)의 R 번호마다 그것을 언급하는 테스트 파일을 세고, 0 인 R 을 낸다(`2v`).
#
# **게이트가 아니다 — 리포트다.** 법 요건은 코드에 흔적이 없어서 코드를 훑으면 안 보이고,
# 「있어야 할 것의 목록」에서 내려가야 한다(coding-rules.md 「지켜지는지는 위에서 내려가며 확인한다」).
# 그 목록이 요건표고, 이 스크립트는 점검 가로·법 줄의 첫 입력이다.
#
# 0 이 곧 구멍은 아니다 — 제약이나 구조로 막아서 테스트가 없는 R 이 있을 수 있다.
# 그래서 여기서는 안 빨개지고, 게이트로 올릴지는 부순 증거를 모은 뒤 `D25` 가 정한다.
set -uo pipefail
cd "$(dirname "$0")/.."

ids=$(grep -oE '^\| *R[0-9]+' doc/reference/commerce-compliance.md | tr -d '| ')
files=$( (find backend/src/test -name '*.java'; \
          find frontend/src frontend/e2e \( -name '*.test.ts' -o -name '*.test.tsx' -o -name '*.spec.ts' \)) | sort)

total=0; missing=()
printf '%-5s %s\n' 'R' '테스트 파일'
for id in $ids; do
  total=$((total+1))
  # -w: R1 이 R10 에 안 걸린다
  n=$(echo "$files" | xargs grep -lw "$id" 2>/dev/null | wc -l)
  printf '%-5s %s\n' "$id" "$n"
  [ "$n" -eq 0 ] && missing+=("$id")
done

echo
if [ "${#missing[@]}" -eq 0 ]; then
  echo "테스트가 언급하는 요건: ${total}/${total}"
else
  echo "테스트가 언급하지 않는 요건: ${#missing[@]}/${total} — ${missing[*]}"
  echo "구멍인지 제약으로 막은 것인지는 요건표의 「강제 지점」 칸이 답한다(commerce-compliance.md)."
fi
exit 0
