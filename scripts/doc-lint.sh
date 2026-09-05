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
  doc/process/*.md
  doc/reference/*.md
)

dup_check_files=(
  CLAUDE.md
  PLAN.md
  PROGRESS.md
  frontend/CLAUDE.md
  frontend/AGENTS.md
  doc/process/*.md
  doc/reference/*.md
)

# 존댓말 금지(「글 작성 규칙」 4번)를 기계로 내린다. 금지어가 문자열로 정해져 있어서
# grep 으로 100% 잡히는 유일한 조항이다 — 나머지 여섯은 뜻을 읽어야 판정돼서 못 내린다.
#
# **`screen-rules.md` 는 뺀다.** 그 문서는 화면 문구를 정의하는 자리라
# 존댓말이 인용이 아니라 본문이다(좋은 예/나쁜 예 표).
honorific_check_files=(
  CLAUDE.md
  PLAN.md
  PROGRESS.md
  frontend/CLAUDE.md
  frontend/AGENTS.md
  doc/process/*.md
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

for f in "${honorific_check_files[@]}"; do
  [ -f "$f" ] || continue
  case "$f" in doc/reference/screen-rules.md) continue ;; esac

  # **인용은 위반이 아니다.** 화면 문구·법조문·남의 오류 메시지를 옮겨 적을 때는
  # 존댓말이 그대로 들어온다. 실제로 스무 건 중 열여섯이 인용이었다.
  # 그래서 백틱·「」·큰따옴표 안을 걷어내고 남은 것만 본다.
  #
  # **코드펜스는 지우지 않고 빈 줄로 바꾼다** — 줄을 없애면 줄 번호가 밀려서
  # 어디를 고쳐야 하는지 못 짚는다.
  #
  # `perl -CSD` 를 쓰는 이유: `sed 's/「[^」]*」//g'` 가 **조용히 안 먹는다.**
  # 멀티바이트 문자를 문자 클래스에 넣으면 바이트 단위로 갈라져서, 걸러진 척하고 통과한다.
  hits=$(awk '/^```/{c=!c; print ""; next} c{print ""; next} {print}' "$f" \
    | perl -CSD -pe 's/`[^`]*`//g; s/\x{300C}.*?\x{300D}//g; s/"[^"]*"//g' \
    | grep -nE '(습니다|합니다|하세요|입니다)')
  if [ -n "$hits" ]; then
    echo "[존댓말] $f — 개발자가 읽는 글은 평서형이다(CLAUDE.md 「글 작성 규칙」 4번):"
    echo "$hits" | sed 's/^/    /'
    fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "이상 없음 — 검사한 파일 전부 통과"
fi
exit "$fail"
