#!/usr/bin/env bash
# 트리 하나(HEAD·origin/main·임시 트리)의 레인별 지문을 낸다(`2z-1`). 출력: `backend <sha>` / `frontend <sha>` / `compare <sha>` / `tools <sha>`.
#
# 서브트리 해시를 쓰면 `backend/CLAUDE.md` 같은 문서도 코드로 센다 — `2z` 가 그 자리에서 자기 훅에 막혔다.
# 그래서 빌드·테스트 결과를 바꾸는 경로만 고른다. 경로를 더할 때 여기 한 곳만 고친다.
set -uo pipefail
cd "$(dirname "$0")/.."
# **받은 이름을 트리 해시로 먼저 푼다**(`Q231`). Git Bash(MSYS)는 `origin/main:.claude/settings.json` 을 경로 목록으로 보고
# 슬래시를 역슬래시로, 콜론을 세미콜론으로 바꿔 넘긴다 — `.` 으로 시작하는 경로가 방아쇠라 도구 레인만 틀렸고, 그래서 윈도에서는
# `origin/main` 의 도구 지문이 늘 달라 도구 레인이 언제나 「바뀌었다」였다. 콜론 앞이 16진수면 안 건드린다. 리눅스 CI 는 무관하다.
tree=$(git rev-parse -q --verify "${1:-HEAD}^{tree}") || { echo "트리를 못 풀었다: ${1:-HEAD}" >&2; exit 1; }
# **경로마다 `git rev-parse` 를 부르지 않는다**(`Q221`). 레인 넷의 경로가 서른넷이라 윈도에서 한 번에 4.5초였고,
# 지문을 두 번 부르는 Stop·commit·push 훅이 매번 10초를 먹었다. `git cat-file --batch-check` 한 번으로 받아 셸 안에서 고른다 —
# 해시 입력(「경로 해시」 줄, 없으면 `-`)이 전과 글자까지 같아 찍어 둔 도장이 그대로 맞는다.
# 아래 목록은 **빠른 길**일 뿐이다 — 레인에 경로를 더하고 여기 안 올려도 그 경로는 예전처럼 `rev-parse` 로 따로 풀려서
# 지문이 조용히 비지 않는다(느려질 뿐이다). `lane compare …` 줄은 `BuildInputTest` 가 글자로 읽어서 레인 줄을 배열로 못 바꾼다.
listed=(
  backend/src backend/config backend/Dockerfile backend/build.gradle.kts backend/settings.gradle.kts backend/gradle backend/gradlew backend/gradle.properties
  frontend/src frontend/e2e backend/src/main/resources/db/seed frontend/package.json frontend/package-lock.json frontend/tsconfig.json
  frontend/next.config.ts frontend/eslint.config.mjs frontend/vitest.config.ts frontend/vitest.setup.ts
  frontend/playwright.config.ts frontend/postcss.config.mjs frontend/Dockerfile frontend/scripts
  docker-compose.yml PLAN.md PROGRESS.md doc/reference doc/erd scripts/verify-fingerprint.sh
  scripts .claude/settings.json .claude/skills
)
# `git cat-file --batch-check` 는 `트리:경로` 를 표준 입력으로 줄마다 받아 한 프로세스로 푼다. `git ls-tree` 는 안 된다 —
# `backend/src` 와 그 안의 `…/db/seed` 를 같이 주면 안으로 내려가서 바깥 줄을 안 찍는다(처음 판에서 그렇게 틀렸다).
# 없는 경로는 `<입력> missing` 이라 `-` 로 적는다. 입력이 표준 입력이라 Git Bash 경로 변환(`Q231`)도 안 탄다.
declare -A entry
mapfile -t resolved < <(printf "$tree:%s\n" "${listed[@]}" | git cat-file --batch-check='%(objectname)')
for i in "${!listed[@]}"; do
  h=${resolved[$i]:-}
  case "$h" in *" missing"|"") h=- ;; esac
  entry[${listed[$i]}]=$h
done
lane() {
  local name=$1; shift
  local p h text=
  for p in "$@"; do
    if [ -n "${entry[$p]+x}" ]; then h=${entry[$p]}
    else h=$(git rev-parse -q --verify "$tree:$p" 2>/dev/null || echo -)
    fi
    text+="$p $h"$'\n'
  done
  printf '%s %s\n' "$name" "$(printf '%s' "$text" | git hash-object --stdin)"
}
# `backend/config` 는 SpotBugs 제외 목록이다(`점검 K`). **빌드 결과를 바꾼다** —
# 제외를 넓히면 진짜 검출이 숨는데, 여기 없으면 도장이 안 바뀌어 Stop hook 이 안 막는다.
#
# **다만 빠른 레인은 SpotBugs 를 안 돈다**(`gradlew test` 에 안 달려 있다). 그래서 이 경로가
# 실제로 막는 것은 **push 앞 `--full`** 이고, 그 전까지는 「도장을 다시 받아야 한다」까지다.
lane backend  backend/src backend/config backend/Dockerfile backend/build.gradle.kts backend/settings.gradle.kts backend/gradle backend/gradlew backend/gradle.properties
# **`db/seed` 가 frontend 레인에도 있다**(`Q131`). `demo-accounts.test.ts` 가 시드 SQL 을 읽어
# 로그인 화면의 계정 목록과 대조하는데, 그 파일은 backend 레인에만 있어서 **시드만 고친 청크가
# 그 시험을 안 돌리고 초록으로 닫혔다.** 대조 레인은 반대 방향(backend 시험이 바깥 파일을 읽는 것)이라
# 여기에 못 쓴다 — 이쪽은 frontend 시험이 backend 파일을 읽는다.
lane frontend frontend/src frontend/e2e backend/src/main/resources/db/seed frontend/package.json frontend/package-lock.json frontend/tsconfig.json \
              frontend/next.config.ts frontend/eslint.config.mjs frontend/vitest.config.ts frontend/vitest.setup.ts \
              frontend/playwright.config.ts frontend/postcss.config.mjs frontend/Dockerfile frontend/scripts
# **대조 레인**(`Q111`). backend 테스트가 **읽기만 하는** 저장소 밖 파일이다 — 화면 소스·컴포즈·계획·문서·ERD.
# backend 레인이 아닌데 backend 테스트의 결과를 바꾼다. 그래서 따로 센다: 이 지문이 `origin/main` 과 다르면
# `verify.sh` 가 backend 대조를 돌리고, Stop hook 이 그 도장을 요구한다.
#
# **목록은 `build.gradle.kts` 의 신고(`comparedIn*Lane`)에서 backend 안쪽을 뺀 것과 같아야 한다** —
# `BuildInputTest` 가 그것을 잰다. 한 줄로 적는다(그 시험이 이 줄을 글자로 읽는다).
lane compare frontend/src docker-compose.yml PLAN.md PROGRESS.md doc/reference doc/erd scripts/verify-fingerprint.sh
# **도구 레인**(`Q216`). 검증 도구 자체 — 훅·스크립트·스킬. 앞 셋 어디에도 안 들어서 **도구만 고친 청크는 아무것도 안 돌았다.**
# `verify.sh` 가 셸 문법·`settings.json` 파싱·`doc-lint` 전체를 돈다. `scripts/verify-fingerprint.sh` 는 대조 레인과 **겹친다** —
# `BuildInputTest` 가 이 파일을 읽는 backend 시험의 입력이라 거기서 못 뺀다. 겹침은 해가 없다(두 레인이 다 돈다).
lane tools    scripts .claude/settings.json .claude/skills
