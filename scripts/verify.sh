#!/usr/bin/env bash
# 건드린 것이 무엇을 돌릴지 정한다(`/verify`, `2z`). `origin/main` 대비 `backend/` 가 다르면 `gradlew build`,
# `frontend/` 가 다르면 build·lint·test. 통과하면 `.git/verify-stamp` 에 그 순간의 두 서브트리 해시를 적고,
# Stop hook 이 `HEAD` 의 서브트리와 대조한다 — 도장 없이 코드 커밋을 남기고 멈추면 막힌다.
#
# 표의 나머지 줄(마이그레이션 기동·e2e·프록시·시드·로그)은 손이다. 그건 `/verify` 스킬이 든다.
set -uo pipefail
cd "$(dirname "$0")/.."

# 이 환경의 JAVA_HOME 은 JDK 11 이라 Gradle 이 안 뜬다(CLAUDE.md). 리눅스 러너는 그대로.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export JAVA_HOME="C:/Program Files/Java/jdk-25" ;; esac

git rev-parse -q --verify origin/main >/dev/null || { echo "origin/main 이 없다 — 기준이 없어서 못 잰다"; exit 1; }

# 작업 트리(커밋 안 된 것 포함)의 트리 해시. 임시 인덱스라 진짜 인덱스를 안 건드린다.
tmp=$(mktemp); trap 'rm -f "$tmp"' EXIT
GIT_INDEX_FILE="$tmp" git read-tree HEAD
GIT_INDEX_FILE="$tmp" git add -A . 2>/dev/null
tree=$(GIT_INDEX_FILE="$tmp" git write-tree)

changed() { [ "$(git rev-parse -q --verify "$tree:$1" 2>/dev/null)" != "$(git rev-parse -q --verify "origin/main:$1" 2>/dev/null)" ]; }

ran=0; ok=1
if changed backend; then
  ran=1; echo "== backend 가 origin/main 과 다르다 → ./gradlew build"
  (cd backend && ./gradlew build -q) || ok=0
fi
if changed frontend; then
  ran=1; echo "== frontend 가 origin/main 과 다르다 → build · lint · test"
  (cd frontend && npm run build >/dev/null && npm run lint && npm test 2>&1 | tail -4) || ok=0
fi
[ "$ran" -eq 0 ] && echo "backend/·frontend/ 가 origin/main 과 같다 — 돌릴 것이 없다"
[ "$ok" -eq 1 ] || { echo "빨갛다 — 도장을 안 찍는다"; exit 1; }

st="$(git rev-parse --git-dir)/verify-stamp"
{ echo "backend $(git rev-parse -q --verify "$tree:backend")"; echo "frontend $(git rev-parse -q --verify "$tree:frontend")"; } > "$st"
echo "초록. 도장: $st"
