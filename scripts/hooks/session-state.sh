#!/usr/bin/env bash
# SessionStart. `PROGRESS.md` 「현재 상태」를 컨텍스트에 넣는다(`2q`). 주 1회 주간 리포트를 뒤에 붙인다(`2h`).
# stdin 을 안 읽는다 — 칸이 필요 없다.
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
sed -n '/^## 현재 상태/,/^## 이력/p' PROGRESS.md | sed '$d'
# **열린 보안 경보를 세션마다 센다**(`Q296`). 수가 주간 리포트에만 있어서 Jackson 경보 열넷이 3주 동안 행을 못 세웠다.
# 0 이면 안 찍는다 — 늘 찍히는 줄은 안 읽힌다. 오프라인·권한 없음(빈 값)도 침묵이다. 한 번에 5초라 훅 예산 안이다.
dep=$(timeout 5 gh api "repos/{owner}/{repo}/dependabot/alerts?state=open&per_page=100" --jq length 2>/dev/null || true)
code=$(timeout 5 gh api "repos/{owner}/{repo}/code-scanning/alerts?state=open&per_page=100" --jq length 2>/dev/null || true)
case "$dep$code" in
  ''|*[!0-9]*) ;;
  *) if [ "${dep:-0}" != 0 ] || [ "${code:-0}" != 0 ]; then
       echo
       # 한 쪽(100)만 받는다 — 다 넘기면 예산을 더 쓴다. 100 은 「그 이상」으로 읽는다(마무리 68차 독립 리뷰)
       [ "$dep" = 100 ] && dep="100+"; [ "$code" = 100 ] && code="100+"
       echo "**열린 보안 경보**: Dependabot ${dep:-?}건 · CodeQL ${code:-?}건 — \`quality-goals.md\` 목표 0. 맡은 행이 없으면 세운다(\`Q296\`)"
     fi ;;
esac
st="$(git rev-parse --git-dir 2>/dev/null)/weekly-report-stamp"
last=$(cat "$st" 2>/dev/null || echo 0)
now=$(( $(date +%s) / 86400 ))
if [ $(( now - last )) -ge 7 ]; then
  echo "$now" > "$st"
  echo
  timeout 25 bash scripts/weekly-report.sh 2>/dev/null
fi
exit 0
