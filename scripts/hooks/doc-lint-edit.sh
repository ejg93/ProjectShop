#!/usr/bin/env bash
# PostToolUse(Edit·Write·NotebookEdit). 프롬프트 역할 문서를 고쳤으면 그 파일만 `doc-lint.sh` 로 훑는다(`2c-1`, `Q217` 범위 모드).
# 전체는 16초라 편집마다 붙는다. PLAN·PROGRESS 의 구조 검사는 그 파일을 넘길 때만 돈다.
. "$(dirname "$0")/_tool-input.sh"
f=$(tool_field file_path)

case "$f" in
  *CLAUDE.md|*AGENTS.md|*PLAN.md|*PROGRESS.md|*doc*reference*.md|*skills*SKILL.md)
    out=$(bash "${CLAUDE_PROJECT_DIR:-.}/scripts/doc-lint.sh" "$f") || { echo "$out" >&2; exit 2; } ;;
esac
exit 0
