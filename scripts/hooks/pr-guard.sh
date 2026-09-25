#!/usr/bin/env bash
# PreToolUse(Bash·PowerShell). `gh pr create`·`gh pr merge` 의 순서를 잡는다(`2g-1`).
# create — base 는 언제나 main, 열린 작업 PR 이 있으면 막는다. merge — 체크가 돌거나 빨가면 막는다.
export PATH="$PATH:/c/Program Files/GitHub CLI"
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

if printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*gh pr create'; then
  b=$(printf '%s\n' "$c" | sed -n 's/.*--base[= ][= ]*\([A-Za-z0-9._/-][A-Za-z0-9._/-]*\).*/\1/p' | head -1)
  if [ -n "$b" ] && [ "$b" != main ]; then
    echo 'PR 의 base 는 언제나 main 이다(2g-1). 쌓아 올리면 아래가 머지될 때 GitHub 이 base 없는 PR 을 닫고, 닫힌 PR 은 reopen 도 base 변경도 안 된다.' >&2
    exit 2
  fi
  o=$(gh pr list --state open --json headRefName --jq '[.[]|select(.headRefName|startswith("chunk/") or startswith("work/"))]|length' 2>/dev/null || echo 0)
  if [ "${o:-0}" -gt 0 ]; then
    echo "열린 작업 PR 이 ${o} 개 있다. 직렬로 간다(2g-1) — 앞 PR 을 머지하고 다음 묶음을 연다." >&2
    exit 2
  fi
elif printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*gh pr merge'; then
  n=$(printf '%s\n' "$c" | sed -n 's/.*gh pr merge[^0-9]*\([0-9][0-9]*\).*/\1/p')
  gh pr checks $n > /dev/null 2>&1
  rc=$?
  if [ $rc -eq 8 ]; then
    echo '체크가 아직 돈다. review 잡까지 초록이 된 뒤에 머지한다(2g-1) — 머지한 뒤에는 그 지적을 그 PR 에서 못 고친다.' >&2
    exit 2
  fi
  if [ $rc -eq 1 ]; then
    echo '빨간 체크가 있다. 머지 전에 고친다.' >&2
    exit 2
  fi
fi
exit 0
