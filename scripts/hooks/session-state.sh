#!/usr/bin/env bash
# SessionStart. `PROGRESS.md` 「현재 상태」를 컨텍스트에 넣는다(`2q`). 주 1회 주간 리포트를 뒤에 붙인다(`2h`).
# stdin 을 안 읽는다 — 칸이 필요 없다.
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
sed -n '/^## 현재 상태/,/^## 이력/p' PROGRESS.md | sed '$d'
st="$(git rev-parse --git-dir 2>/dev/null)/weekly-report-stamp"
last=$(cat "$st" 2>/dev/null || echo 0)
now=$(( $(date +%s) / 86400 ))
if [ $(( now - last )) -ge 7 ]; then
  echo "$now" > "$st"
  echo
  timeout 25 bash scripts/weekly-report.sh 2>/dev/null
fi
exit 0
