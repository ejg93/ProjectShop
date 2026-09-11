#!/usr/bin/env bash
# 아무도 안 건드린 자리가 썩고 있나를 주 1회 모은다(`2h`).
#
# **「어제 뭐 했나」를 안 쓴다** — 마무리가 이미 든다. 여기가 보는 것은 **안 움직인 것**이다.
#
# **도착 자리가 강제 지점을 대신한다.** 파일로만 남기면 「읽으라고 적은 것」이 되므로
# `SessionStart` 훅이 이 출력을 컨텍스트에 넣고, 그것이 **예열의 입력**이 된다 —
# 「오늘 어느 축을 보나」가 기억이 아니라 이 목록에서 나온다.
#
# **싼 것만 센다.** 훅이 부르므로 느리면 세션 여는 것이 느려진다. 테스트를 돌리지 않고
# 소스를 세며, 망 호출은 전부 짧은 시한을 걸고 실패해도 그 줄만 비운다.
set -uo pipefail
cd "$(dirname "$0")/.."

TODAY=$(date +%Y-%m-%d)
today_days=$(( $(date +%s) / 86400 ))

# `gh` 가 없거나 느릴 때 그 줄만 비우고 넘어간다. 리포트가 통째로 안 나오는 것보다 낫다.
gh_or_blank() {
  timeout 10 gh "$@" 2>/dev/null || true
}

echo "## 주간 리포트 ($TODAY)"
echo
echo "**안 움직인 것만 센다**(\`2h\`). 어제 한 일은 「이력」이 든다."
echo

# ── ① 오래 안 본 점검 줄 ────────────────────────────────────────────────
echo "### 오래 안 본 점검 줄"
echo
awk -F'|' '
  /^\| 줄 \| 마지막/ { table = 1; next }
  table && /^\|---/ { next }
  table && /^\|/ {
    axis = $2; last = $3
    gsub(/^[ \t]+|[ \t]+$/, "", axis)
    gsub(/\*\*/, "", last); gsub(/^[ \t]+|[ \t]+$/, "", last)
    if (axis == "") next
    # **정렬 키를 하나로 맞춘다.** 한 번도 안 본 줄이 제일 위다 — 날짜가 없다고 목록에서
    # 빠지면 **가장 오래된 것이 안 보인다.**
    if (last ~ /한 번도 안 봤다/) { print "0000-00-00\t" axis; next }
    if (last ~ /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/) print last "\t" axis
    next
  }
  table && !/^\|/ { table = 0 }
' PROGRESS.md | sort | while IFS=$'\t' read -r date axis; do
  [ -z "${axis:-}" ] && continue
  if [ "$date" = "0000-00-00" ]; then
    echo "- **$axis** — 한 번도 안 봤다"
  # `date -d` 가 없는 환경을 만나면 날짜만 적는다. 수를 못 세도 순서는 위가 오래된 것이다.
  elif days=$(date -d "$date" +%s 2>/dev/null); then
    echo "- **$axis** — $date ($(( today_days - days / 86400 ))일 전)"
  else
    echo "- **$axis** — $date"
  fi
done
echo

# ── ② 뒤처진 Dependabot PR ──────────────────────────────────────────────
# 가지 보호가 `strict: true` 라 뒤처진 PR 은 자동 머지가 멈춘다(`2f-3`). 그 워크플로는
# PR 이 열릴 때만 돌아서, 그 뒤 `main` 이 움직이면 아무도 안 본다. 사람이 rebase 를 단다.
echo "### 멈춘 Dependabot PR"
echo
behind=$(gh_or_blank pr list --author "app/dependabot" --state open \
  --json number,title,mergeStateStatus \
  --jq '.[] | select(.mergeStateStatus == "BEHIND") | "- #\(.number) \(.title) — `@dependabot rebase` 를 단다"')
echo "${behind:-- 없다}"
echo

# ── ③ 빨간 채 방치된 CI ─────────────────────────────────────────────────
echo '### `main` 의 마지막 CI'
echo
ci=$(gh_or_blank run list --branch main --limit 5 \
  --json name,conclusion,createdAt \
  --jq '.[] | select(.conclusion == "failure") | "- **\(.name)** 이 빨갛다 (\(.createdAt[0:10]))"')
echo "${ci:-- 초록이다}"
echo

# ── ④ 보안 경보 수 ─────────────────────────────────────────────────────
echo "### 경보"
echo
dep=$(gh_or_blank api "repos/{owner}/{repo}/dependabot/alerts?state=open&per_page=100" --jq 'length')
code=$(gh_or_blank api "repos/{owner}/{repo}/code-scanning/alerts?state=open&per_page=100" --jq 'length')
echo "- Dependabot: ${dep:-못 셌다}건 · CodeQL: ${code:-못 셌다}건"
echo "- **Gradle 쪽은 Dependabot 뿐이다** — 막는 게이트가 없다(\`quality-gates.md\`)"
echo

# ── ⑤ 빠른 레인 비율 ───────────────────────────────────────────────────
# **테스트를 안 돌린다.** 컨테이너를 타는 것은 `PostgresTestBase`·`HttpTestBase` 를 상속한
# 파일뿐이라(`D15`), 상속 여부로 센다. 실제 케이스 수가 아니라 **파일 수의 비율**이다.
echo "### 레인"
echo
slow=$(grep -rlE "extends (PostgresTestBase|HttpTestBase)" backend/src/test --include='*.java' 2>/dev/null | wc -l)
all=$(find backend/src/test -name '*Test.java' 2>/dev/null | wc -l)
fast=$(( all - slow ))
if [ "$all" -gt 0 ]; then
  echo "- 백엔드 테스트 파일 ${all}개 중 빠른 레인 ${fast}개 ($(( fast * 100 / all ))%)"
  echo "- 느린 쪽이 크면 순수 계산을 떼어내는 청크가 밀린 것이다(\`D15\`)"
else
  echo "- 못 셌다"
fi
echo

# ── ⑥ 조건이 찬 기준 문서 ──────────────────────────────────────────────
# **조건은 기계가 못 읽는다** — 「그 문서가 다루는 것이 코드에 하나라도 있나」라서다.
# 그래서 판정하지 않고 **그 줄을 그대로 보여 준다.** 읽는 사람이 센다.
echo "### 기준 문서 — 조건이 찼나"
echo
grep -E '^\| (조건 미달 기준 문서|찬 조건|곧 차는 조건) \|' PROGRESS.md | sed 's/^/  /' || true
echo
echo "**조건이 찼으면 코드 청크보다 먼저다**(\`CLAUDE.md\` 「문서가 코드보다 먼저다」)."
