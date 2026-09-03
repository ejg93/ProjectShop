#!/usr/bin/env bash
# 프롬프트 역할 문서(CLAUDE.md·doc/reference/*)가 구조적으로 부서졌는지 훑는다.
# "규칙을 읽는 것"과 "지켜졌는지 훑는 것"은 다른 일이다(coding-rules.md) — 이건 후자를
# 세션이 아니라 기계가 하게 만드는 자리다. 문서를 고쳤으면 커밋 전에 한 번 돌린다.
#
# 표 헤더·짧은 라벨은 여러 표에서 정당하게 반복되므로 제외한다. 잡는 것은 "문장 하나가
# 통째로 두 번"인 사고(frontend-rules.md 사례)와 "제목 앞에 표 행이 눌어붙는" 사고
# (batch-catalog.md·state-machines.md·PLAN.md 사례) 둘이다.
set -uo pipefail

cd "$(dirname "$0")/.."

title_check_files=(
  CLAUDE.md
  PLAN.md
  PROGRESS.md
  doc/reference/*.md
)

dup_check_files=(
  CLAUDE.md
  PLAN.md
  PROGRESS.md
  frontend/CLAUDE.md
  frontend/AGENTS.md
  doc/reference/*.md
)

fail=0

for f in "${title_check_files[@]}"; do
  [ -f "$f" ] || continue
  first_line=$(head -n 1 "$f")
  case "$first_line" in
    "#"*) ;;
    *)
      echo "[제목 손상] $f — 첫 줄이 '#' 로 안 시작함: ${first_line:0:60}..."
      fail=1
      ;;
  esac
done

for f in "${dup_check_files[@]}"; do
  [ -f "$f" ] || continue

  # 코드펜스 안 내용을 지우고, 표 행(|로 시작)·20자 미만 줄을 뺀 뒤에 완전 중복만 본다.
  dups=$(awk '/^```/{c=!c; next} !c' "$f" \
    | grep -vE '^[[:space:]]*(\||#+[[:space:]])' \
    | sed '/^[[:space:]]*$/d' \
    | awk 'length($0) >= 20' \
    | sort | uniq -d)
  if [ -n "$dups" ]; then
    echo "[중복 문장] $f:"
    echo "$dups" | sed 's/^/    /'
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "이상 없음 — 검사한 파일 전부 통과"
fi
exit "$fail"
