#!/usr/bin/env bash
# 건드린 것이 무엇을 돌릴지 정한다(`/verify`, `2z`). `origin/main` 대비 backend 지문이 다르면 `gradlew build`,
# frontend 지문이 다르면 build·lint·test. 지문은 `verify-fingerprint.sh` 가 낸다(코드·빌드 파일만, `2z-1`).
# 통과하면 `.git/verify-stamp` 에 그 순간의 지문을 적고, Stop hook 이 `HEAD` 의 지문과 대조한다.
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

fp_work=$(bash scripts/verify-fingerprint.sh "$tree"); fp_main=$(bash scripts/verify-fingerprint.sh origin/main)
changed() { [ "$(echo "$fp_work" | grep "^$1 ")" != "$(echo "$fp_main" | grep "^$1 ")" ]; }

ran=0; ok=1
if changed backend; then
  ran=1; echo "== backend 지문이 origin/main 과 다르다 → ./gradlew build"
  (cd backend && ./gradlew build -q) || ok=0
fi
if changed frontend; then
  ran=1; echo "== frontend 지문이 origin/main 과 다르다 → build · lint · test"
  (cd frontend && npm run build >/dev/null && npm run lint && npm test 2>&1 | tail -4) || ok=0
fi
[ "$ran" -eq 0 ] && echo "두 지문이 origin/main 과 같다 — 돌릴 것이 없다"
[ "$ok" -eq 1 ] || { echo "빨갛다 — 도장을 안 찍는다"; exit 1; }

st="$(git rev-parse --git-dir)/verify-stamp"
echo "$fp_work" > "$st"
echo "초록. 도장: $st"
