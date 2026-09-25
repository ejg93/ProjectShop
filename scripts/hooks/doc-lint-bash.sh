#!/usr/bin/env bash
# PostToolUse(Bash·PowerShell). 셸로 프롬프트 역할 문서를 고쳤으면 `doc-lint.sh` 를 돌린다(`2c-1`).
# 명령에서 파일명을 믿고 못 뽑아서 늘 전체를 돈다 — 편집 훅(`doc-lint-edit.sh`)과 다른 자리다.
. "$(dirname "$0")/_tool-input.sh"
c=$(tool_field command)

case "$c" in *doc-lint*) exit 0 ;; esac
if printf '%s\n' "$c" | grep -qE '(^|[;&|])[[:space:]]*(sed -i|tee |cp |mv |Set-Content|Add-Content|Out-File|Copy-Item|Move-Item)|(^|[;&|])[^;&|"]*>>?[[:space:]]*[^&|;[:space:]]*\.md' \
   && printf '%s\n' "$c" | grep -qE 'CLAUDE\.md|AGENTS\.md|PLAN\.md|PROGRESS\.md|doc.{0,4}reference|skills.{0,4}[a-z-]+.{0,4}SKILL'; then
  out=$(bash "${CLAUDE_PROJECT_DIR:-.}/scripts/doc-lint.sh") || { echo "$out" >&2; exit 2; }
fi
exit 0
