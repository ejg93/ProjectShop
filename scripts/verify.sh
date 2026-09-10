#!/usr/bin/env bash
# 건드린 것이 무엇을 돌릴지 정한다(`/verify`, `2z`). `origin/main` 대비 backend 지문이 다르면 backend 레인,
# frontend 지문이 다르면 frontend 레인. 지문은 `verify-fingerprint.sh` 가 낸다(코드·빌드 파일만, `2z-1`).
#
# **단계가 둘이다**(`2z-2`). 기본은 **빠른 도장** — backend `gradlew test`(컨테이너 없이 10초),
# frontend `tsc --noEmit`·lint·test. `--full` 은 backend `gradlew build`(느린 레인 930개 포함),
# frontend `next build`·lint·test. 청크를 닫을 땐 빠른 도장이면 되고(Stop hook), **push 앞엔 full 이어야 한다**(push hook).
# DB 를 타는 결함은 그래서 청크 여럿 뒤에 드러날 수 있다 — 청크가 커밋 하나라 `git bisect` 가 답한다.
#
# 통과하면 `.git/verify-stamp` 에 「레인 지문 단계」를 적는다. 표의 손 줄(마이그레이션 기동·e2e·프록시·시드·로그)은
# 여기 없다 — `/verify` 스킬이 든다.
set -uo pipefail
cd "$(dirname "$0")/.."

level=fast
[ "${1:-}" = "--full" ] && level=full

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
  ran=1
  if [ "$level" = full ]; then
    echo "== backend 지문이 origin/main 과 다르다 → ./gradlew build (두 레인)"
    (cd backend && ./gradlew build -q) || ok=0
  else
    echo "== backend 지문이 origin/main 과 다르다 → ./gradlew test (빠른 레인)"
    (cd backend && ./gradlew test -q) || ok=0
  fi
fi
if changed frontend; then
  ran=1
  if [ "$level" = full ]; then
    echo "== frontend 지문이 origin/main 과 다르다 → next build · lint · test"
    (cd frontend && npm run build >/dev/null && npm run lint && npm test 2>&1 | tail -4) || ok=0
  else
    echo "== frontend 지문이 origin/main 과 다르다 → tsc --noEmit · lint · test"
    (cd frontend && npx tsc --noEmit && npm run lint && npm test 2>&1 | tail -4) || ok=0
  fi
fi
[ "$ran" -eq 0 ] && echo "두 지문이 origin/main 과 같다 — 돌릴 것이 없다"
[ "$ok" -eq 1 ] || { echo "빨갛다 — 도장을 안 찍는다"; exit 1; }

# 안 돈 레인(origin/main 과 같은 것)은 full 로 적는다 — 돌릴 것이 없어서다.
st="$(git rev-parse --git-dir)/verify-stamp"
for d in backend frontend; do
  h=$(echo "$fp_work" | grep "^$d " | cut -d' ' -f2)
  if changed "$d"; then echo "$d $h $level"; else echo "$d $h full"; fi
done > "$st"
echo "초록($level). 도장: $st"
