#!/usr/bin/env bash
# 훅·도구 회귀 시험(`Q221`). 훅 아홉과 `doc-lint` 의 위반 검사를 stdin JSON·임시 저장소로 잰다 — **실제 저장소는 안 건드린다.**
# 경우마다 「입력 → 기대 exit → 기대 문구 앞머리」 세 칸이다. `verify.sh` 의 도구 레인이 돈다(검증 도구를 고친 커밋마다).
#
# **왜 있나**: `Q215` 가 훅 아홉을 손으로 31경우 쟀는데 그 표는 이력에만 있었다. 도구 레인은 `bash -n` 과
# `settings.json` 파싱뿐이라 훅 하나가 조용히 `exit 0` 으로 바뀌어도 초록이었다.
#
# **격리하는 법**
# - 도장·가지를 보는 훅(commit·push·stop)은 임시 git 저장소에서 돈다 — `CLAUDE_PROJECT_DIR` 를 그리로 돌리고
#   `verify-fingerprint.sh` 를 복사해 둔다. `origin/main` 은 `git update-ref` 로 만든다.
# - `pr-guard` 의 `gh` 는 `PATH` 앞의 가짜로 끊는다 — 열린 PR 수와 `gh pr checks` 종료 코드를 환경변수로 준다.
# - 편집 훅 둘은 가짜 `doc-lint.sh`(받은 인자를 적고 `FAKE_LINT_RC` 로 끝난다)로 배선만 잰다.
# - `doc-lint` 자체의 위반 다섯은 임시 사본에서 범위 모드로 잰다.
set -u
R=$(cd "$(dirname "$0")/.." && pwd)
H=$R/scripts/hooks
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail=0; n=0

json_cmd() { node -e 'process.stdout.write(JSON.stringify({tool_input:{command:process.argv[1]}}))' "$1"; }
json_file() { node -e 'process.stdout.write(JSON.stringify({tool_input:{file_path:process.argv[1]}}))' "$1"; }

# 경우 하나: 이름, 훅 파일, 기대 exit, stdin, [기대 stderr 앞머리], [작업 디렉터리]
case_() {
  local name=$1 hook=$2 want=$3 input=$4 head=${5:-} dir=${6:-$T/repo}
  n=$((n + 1))
  local err got
  err=$(cd "$dir" && printf '%s' "$input" | bash "$H/$hook" 2>&1 >/dev/null); got=$?
  if [ "$got" != "$want" ]; then
    echo "  [실패] $n $hook — $name: 기대 exit $want, 실제 $got. ${err:0:80}"; fail=1
  elif [ -n "$head" ] && [ "${err#"$head"}" = "$err" ]; then
    echo "  [실패] $n $hook — $name: stderr 가 「$head」로 안 시작한다: ${err:0:80}"; fail=1
  else
    echo "  [통과] $n $hook — $name"
  fi
}

# ── 임시 저장소: origin/main 하나, work/x 가지에 backend 한 줄을 더한 커밋 ─────────────────────────
mkrepo() {
  rm -rf "$T/repo"; mkdir -p "$T/repo/scripts" "$T/repo/backend/src" "$T/repo/.claude"
  cp "$R/scripts/verify-fingerprint.sh" "$T/repo/scripts/"
  (
    cd "$T/repo" || exit 1
    git init -q -b main
    git config user.email t@t; git config user.name t; git config commit.gpgsign false; git config core.autocrlf false
    echo base > backend/src/A.java; echo '{}' > .claude/settings.json
    git add -A; git commit -qm base
    git update-ref refs/remotes/origin/main HEAD
    git checkout -qb work/x
    echo change >> backend/src/A.java; git commit -qam change
  )
}
stamp_head() { # 레벨 — HEAD 지문을 그 레벨로 찍는다
  (cd "$T/repo" && bash scripts/verify-fingerprint.sh HEAD | sed "s/\$/ $1/" > .git/verify-stamp)
}
stamp_worktree() { # 작업 트리 지문을 fast 로 — verify.sh 와 같은 방법
  (cd "$T/repo" && tmp=$(mktemp) && GIT_INDEX_FILE="$tmp" git read-tree HEAD && GIT_INDEX_FILE="$tmp" git add -A . \
    && tree=$(GIT_INDEX_FILE="$tmp" git write-tree) && rm -f "$tmp" \
    && bash scripts/verify-fingerprint.sh "$tree" | sed 's/$/ fast/' > .git/verify-stamp)
}

mkrepo
export CLAUDE_PROJECT_DIR=$T/repo

echo "pr-guard(가짜 gh):"
mkdir -p "$T/bin"
cat > "$T/bin/gh" <<'GH'
#!/usr/bin/env bash
case "$1 $2" in
  "pr list") echo "${FAKE_OPEN_PRS:-0}" ;;
  "pr checks") exit "${FAKE_CHECKS_RC:-0}" ;;
esac
GH
chmod +x "$T/bin/gh"
PATH="$T/bin:$PATH"
case_ "base develop" pr-guard.sh 2 "$(json_cmd 'gh pr create --base develop --title x')" "PR 의 base 는 언제나 main"
FAKE_OPEN_PRS=0 case_ "base main, 열린 작업 PR 0" pr-guard.sh 0 "$(json_cmd 'gh pr create --base main --title x')"
FAKE_OPEN_PRS=1 case_ "열린 작업 PR 1" pr-guard.sh 2 "$(json_cmd 'gh pr create --base main --title x')" "열린 작업 PR 이 1 개"
FAKE_CHECKS_RC=8 case_ "merge — 체크가 돈다" pr-guard.sh 2 "$(json_cmd 'gh pr merge 7 --merge')" "체크가 아직 돈다"
FAKE_CHECKS_RC=1 case_ "merge — 빨간 체크" pr-guard.sh 2 "$(json_cmd 'gh pr merge 7 --merge')" "빨간 체크가 있다"
FAKE_CHECKS_RC=0 case_ "merge — 초록" pr-guard.sh 0 "$(json_cmd 'gh pr merge 7 --merge')"
case_ "ls" pr-guard.sh 0 "$(json_cmd 'ls')"

echo "push-guard:"
case_ "push origin main" push-guard.sh 2 "$(json_cmd 'git push origin main')" "main 에 직접 안 민다"
case_ "push HEAD:refs/heads/main" push-guard.sh 2 "$(json_cmd 'git push origin HEAD:refs/heads/main')" "main 에 직접 안 민다"
stamp_head fast
case_ "work 가지, fast 도장뿐" push-guard.sh 2 "$(json_cmd 'git push')" "full 도장이 없다"
stamp_head full
case_ "work 가지, full 도장" push-guard.sh 0 "$(json_cmd 'git push')"
(cd "$T/repo" && git checkout -q main)
case_ "현재 가지가 main" push-guard.sh 2 "$(json_cmd 'git push')" "현재 가지가 main 이다"
(cd "$T/repo" && git checkout -q work/x)
case_ "ls" push-guard.sh 0 "$(json_cmd 'ls')"

echo "commit-guard:"
: > "$T/repo/.git/verify-stamp"
echo more >> "$T/repo/backend/src/A.java"
case_ "work 가지, 도장 없음" commit-guard.sh 2 "$(json_cmd 'git commit -m x')" "도장 없는 커밋은 work/* 에 안 올린다"
case_ "verify && commit 한 체인" commit-guard.sh 2 "$(json_cmd 'bash scripts/verify.sh && git commit -m x')" "도장 없는 커밋은"
case_ "git status" commit-guard.sh 0 "$(json_cmd 'git status')"
stamp_worktree
case_ "작업 트리 지문이 도장에 있다" commit-guard.sh 0 "$(json_cmd 'git commit -m x')"
(cd "$T/repo" && git stash -q && git checkout -q main)
case_ "main 가지" commit-guard.sh 0 "$(json_cmd 'git commit -m x')"
(cd "$T/repo" && git checkout -q work/x && git stash pop -q)

echo "java-home-guard:"
case_ "gradlew 맨몸" java-home-guard.sh 2 "$(json_cmd 'cd backend && ./gradlew test')" "backend 명령 앞에 JDK 를 지정한다"
case_ "JAVA_HOME= 붙임" java-home-guard.sh 0 "$(json_cmd 'JAVA_HOME="C:/x" ./gradlew test')"
case_ "PowerShell \$env:JAVA_HOME" java-home-guard.sh 0 "$(json_cmd '$env:JAVA_HOME = "C:/x"; ./gradlew test')"
case_ "한글 줄의 gradlew" java-home-guard.sh 0 "$(json_cmd 'echo "설명 ./gradlew 는 느리다"')"

echo "편집 훅 둘(가짜 doc-lint):"
cat > "$T/repo/scripts/doc-lint.sh" <<'LINT'
#!/usr/bin/env bash
echo "$*" > "$(dirname "$0")/../.lint-args"
echo "[가짜 린트] rc=${FAKE_LINT_RC:-0}"
exit "${FAKE_LINT_RC:-0}"
LINT
FAKE_LINT_RC=1 case_ "CLAUDE.md 편집, 린트 빨강" doc-lint-edit.sh 2 "$(json_file "$T/repo/CLAUDE.md")" "[가짜 린트] rc=1"
n=$((n + 1))
if grep -q "CLAUDE.md" "$T/repo/.lint-args" 2>/dev/null; then echo "  [통과] $n doc-lint-edit.sh — 편집한 파일 하나를 넘긴다(범위 모드)"
else echo "  [실패] $n doc-lint-edit.sh — 받은 인자: $(cat "$T/repo/.lint-args" 2>/dev/null)"; fail=1; fi
FAKE_LINT_RC=0 case_ "CLAUDE.md 편집, 린트 초록" doc-lint-edit.sh 0 "$(json_file "$T/repo/CLAUDE.md")"
FAKE_LINT_RC=1 case_ "Java 편집은 안 부른다" doc-lint-edit.sh 0 "$(json_file "$T/repo/backend/src/A.java")"
FAKE_LINT_RC=1 case_ "sed -i PLAN.md, 린트 빨강" doc-lint-bash.sh 2 "$(json_cmd "sed -i 's/a/b/' PLAN.md")" "[가짜 린트] rc=1"
FAKE_LINT_RC=1 case_ "ls 는 안 부른다" doc-lint-bash.sh 0 "$(json_cmd 'ls')"
FAKE_LINT_RC=1 case_ "doc-lint 자신은 안 부른다" doc-lint-bash.sh 0 "$(json_cmd 'bash scripts/doc-lint.sh > PLAN.md.log')"
rm -f "$T/repo/scripts/doc-lint.sh" "$T/repo/.lint-args"

echo "session-state:"
printf '## 현재 상태\n\n| 무엇 | 상태 |\n|---|---|\n| 가 | 나 |\n\n## 이력\n' > "$T/repo/PROGRESS.md"
n=$((n + 1))
out=$(printf '{}' | bash "$H/session-state.sh" 2>/dev/null); got=$?
if [ "$got" = 0 ] && printf '%s' "$out" | grep -q "| 가 | 나 |"; then echo "  [통과] $n session-state.sh — 「현재 상태」를 낸다"
else echo "  [실패] $n session-state.sh — exit $got, 출력 ${out:0:60}"; fail=1; fi
rm -f "$T/repo/PROGRESS.md"

echo "stop 훅 둘:"
case_ "uncommitted — stop_hook_active" stop-uncommitted.sh 0 '{"stop_hook_active":true}'
echo dirty >> "$T/repo/backend/src/A.java"
case_ "uncommitted — 더러운 트리" stop-uncommitted.sh 2 '{"stop_hook_active":false}' "커밋 안 된 작업물이 있다"
(cd "$T/repo" && git checkout -q -- backend/src/A.java)
case_ "uncommitted — 깨끗한 트리" stop-uncommitted.sh 0 '{"stop_hook_active":false}'
case_ "stamp — stop_hook_active" stop-stamp.sh 0 '{"stop_hook_active":true}'
stamp_head fast
case_ "stamp — HEAD 지문 도장 있음" stop-stamp.sh 0 '{"stop_hook_active":false}'
: > "$T/repo/.git/verify-stamp"
case_ "stamp — 도장 비움" stop-stamp.sh 2 '{"stop_hook_active":false}' "검증 도장이 없다"

echo "지문(Q231):"
n=$((n + 1))
(cd "$T/repo" && git update-ref refs/remotes/origin/main HEAD)
a=$(cd "$T/repo" && bash scripts/verify-fingerprint.sh HEAD); b=$(cd "$T/repo" && bash scripts/verify-fingerprint.sh origin/main)
if [ "$a" = "$b" ]; then echo "  [통과] $n verify-fingerprint.sh — 같은 커밋을 HEAD·origin/main 으로 불러도 같다(.claude 경로 포함)"
else echo "  [실패] $n verify-fingerprint.sh — HEAD 와 origin/main 이 다르다(윈도 경로 변환?)"; diff <(echo "$a") <(echo "$b") | head -4; fail=1; fi

echo "doc-lint 위반 다섯(임시 사본, 범위 모드):"
L=$T/lint; mkdir -p "$L/scripts" "$L/doc/reference"; cp "$R/scripts/doc-lint.sh" "$L/scripts/"
lint_case() { # 이름, 파일(사본 상대), 본문, 기대 라벨(빈 값이면 초록이어야)
  local name=$1 f=$2 body=$3 label=$4
  n=$((n + 1)); printf '%b' "$body" > "$L/$f"
  local out rc; out=$(bash "$L/scripts/doc-lint.sh" "$f" 2>&1); rc=$?
  if [ -z "$label" ]; then
    if [ "$rc" = 0 ]; then echo "  [통과] $n doc-lint — $name"; else echo "  [실패] $n doc-lint — $name: rc=$rc ${out:0:80}"; fail=1; fi
  elif [ "$rc" != 0 ] && printf '%s' "$out" | grep -qF "$label"; then echo "  [통과] $n doc-lint — $name"
  else echo "  [실패] $n doc-lint — $name: rc=$rc, 「$label」 없음. ${out:0:80}"; fail=1; fi
  rm -f "$L/$f"
}
lint_case "깨끗한 문서" doc/reference/ok.md '# 제목\n\n평서형으로 적었다.\n' ''
lint_case "존댓말 어미(해요)" doc/reference/h.md '# 제목\n\n이렇게 해요.\n' '[존댓말]'
lint_case "30자 넘는 같은 셀 둘" doc/reference/c.md '# 제목\n\n| 가 | 서른 자가 넘는 긴 셀 하나를 두 행에 똑같이 적었다 |\n| 나 | 서른 자가 넘는 긴 셀 하나를 두 행에 똑같이 적었다 |\n' '[중복 셀]'
lint_case "깨진 UTF-8" doc/reference/u.md '# 제목\n\n\xff\xfe 깨짐\n' '[UTF-8 깨짐]'
lint_case "설계 행에 실패 사다리 없음" PLAN.md '# 계획\n\n## 청크 분할표\n\n| # | 청크 | 무엇 | 선행 |\n|---|---|---|---|\n| X1 | 무엇 | **결정**: 가. **번호 절차**: ① 하나 ② 시험. **축**: 규약. **강제 지점**: 시험. **닫힘**: ② | — |\n\n## 이 계획을 고칠 때\n' '[설계 행 누락]'
lint_case "새 이력 행의 커밋 칸 빔(마지막 아님)" PROGRESS.md '# 진행\n\n## 현재 상태\n\n| 가 | 나 |\n\n## 이력\n\n| 날짜 | 청크 | 결과 | 커밋 |\n|---|---|---|---|\n| 2099-01-01 | `A1`. 가 | 완료 — 한 것 | |\n| 2099-01-02 | `A2`. 나 | 완료 — 한 것 | |\n' '[이력 해시 빈 칸]'

echo
if [ "$fail" -eq 0 ]; then echo "훅·도구 회귀 시험 통과 — ${n}경우"; else echo "훅·도구 회귀 시험 실패"; fi
exit "$fail"
