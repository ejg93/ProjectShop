#!/usr/bin/env bash
# 게이트를 일부러 부숴 막는지 본다(`Q225`, `D25` 「게이트는 부순 증거가 있어야 닫힌다」). `bash scripts/gate-probe.sh <이름>`.
# 부수는 절차를 `scripts/probes/<이름>.patch` 로 남겨, 게이트를 고친 뒤 **같은 부수기를 다시 돌린다** — 전에는 부순 증거가
# 이력 문장뿐이라 다시 부수려면 그 문장을 읽고 손으로 다시 만들었다.
#
# **worktree 를 안 판다**(2026-09-26 사용자 결정). ProjectTicket 이 worktree 를 지우다 junction 을 못 끊어 진짜
# `frontend/node_modules` 를 날렸다(Git for Windows 2.45.1). 그래서 **제자리에서** 한다:
#   깨끗한 트리에서만 돈다 → 작업 트리 해시를 적는다 → `git apply` → 패치 머리의 `# run:` 명령 → **빨강이면 통과** →
#   `git apply -R` → 트리 해시가 전과 같은지. 다르면 「복원 실패」로 빨갛고 손으로 되돌리라고 한다.
# **`git checkout -- .` 은 어디서도 안 부른다** — 같은 파일의 미커밋 작업까지 날린다(2026-09-23 실측).
#
# 패치 꼴: 머리에 `# gate: <게이트 이름>` · `# run: <부서져야 할 명령>` 을 적고 그 아래가 `git diff` 출력이다.
# `git apply` 는 첫 `diff --git` 앞의 줄을 설명으로 보고 건너뛴다.
set -u
cd "$(dirname "$0")/.."
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export JAVA_HOME="C:/Program Files/Java/jdk-25" ;; esac

name=${1:-}
patch=scripts/probes/$name.patch
[ -n "$name" ] && [ -f "$patch" ] || { echo "쓰는 법: bash scripts/gate-probe.sh <이름> — 있는 것: $(ls scripts/probes/*.patch 2>/dev/null | xargs -n1 basename 2>/dev/null | sed 's/\.patch$//' | tr '\n' ' ')" >&2; exit 2; }

if [ -n "$(git status --porcelain)" ]; then
  echo "트리가 깨끗하지 않다 — 탐침은 제자리에서 패치를 대고 되돌리므로 미커밋 작업이 있으면 안 돈다. 커밋하거나 치운다" >&2
  exit 2
fi

worktree_hash() { # verify.sh 와 같은 방법 — 임시 인덱스라 진짜 인덱스를 안 건드린다
  local tmp tree; tmp=$(mktemp)
  GIT_INDEX_FILE="$tmp" git read-tree HEAD
  GIT_INDEX_FILE="$tmp" git add -A . 2>/dev/null
  tree=$(GIT_INDEX_FILE="$tmp" git write-tree); rm -f "$tmp"; echo "$tree"
}

gate=$(sed -n 's/^# gate: //p' "$patch" | head -1)
run=$(sed -n 's/^# run: //p' "$patch" | head -1)
[ -n "$run" ] || { echo "패치 머리에 # run: 이 없다: $patch" >&2; exit 2; }

before=$(worktree_hash)
if ! git apply "$patch" 2>/tmp/gate-probe-apply.err; then
  echo "빨강 — 패치가 안 붙는다(게이트 코드가 그 뒤 바뀌었다). 「낡음」이다 — 패치를 다시 만든다: $patch" >&2
  sed 's/^/    /' /tmp/gate-probe-apply.err >&2
  exit 1
fi

echo "== $name — ${gate:-게이트} 를 부순 채로: $run"
bash -c "$run" > /tmp/gate-probe-run.log 2>&1; rc=$?

if ! git apply -R "$patch" 2>/tmp/gate-probe-apply.err; then
  echo "빨강 — 복원 실패: git apply -R 이 안 붙는다. 손으로 되돌린다(git checkout -- . 은 쓰지 않는다): $patch" >&2
  exit 1
fi
after=$(worktree_hash)
if [ "$before" != "$after" ]; then
  echo "빨강 — 복원 실패: 트리가 전과 다르다(부서진 명령이 파일을 만들었거나 고쳤다). git status 를 보고 손으로 되돌린다" >&2
  git status --short >&2
  exit 1
fi

if [ "$rc" -eq 0 ]; then
  echo "빨강 — 게이트가 안 막혔다: 부순 채로 「$run」 이 초록이다. 게이트를 고치거나 패치를 다시 만든다" >&2
  tail -5 /tmp/gate-probe-run.log | sed 's/^/    /' >&2
  exit 1
fi
echo "통과 — 부순 채로 빨강(rc=$rc)이었고 트리는 전과 같다"
