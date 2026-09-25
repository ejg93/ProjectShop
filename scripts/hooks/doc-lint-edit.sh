#!/usr/bin/env bash
# PostToolUse(Edit·Write·NotebookEdit). 프롬프트 역할 문서를 고쳤으면 `doc-lint.sh` 를 돌린다(`2c-1`).
. "$(dirname "$0")/_tool-input.sh"
f=$(tool_field file_path)

case "$f" in
  *CLAUDE.md|*AGENTS.md|*PLAN.md|*PROGRESS.md|*doc*reference*.md|*skills*SKILL.md)
    out=$(bash "${CLAUDE_PROJECT_DIR:-.}/scripts/doc-lint.sh") || { echo "$out" >&2; exit 2; } ;;
esac
exit 0
