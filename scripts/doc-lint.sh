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

# 절차 스킬(`2r`)도 프롬프트 역할 문서다. `design-taste-frontend` 는 바깥 스킬이라 뺀다.
skill_files=()
for f in .claude/skills/*/SKILL.md; do
  case "$f" in *design-taste-frontend*) ;; *) skill_files+=("$f") ;; esac
done

title_check_files=(
  CLAUDE.md
  backend/CLAUDE.md
  PLAN.md
  PROGRESS.md
  "${skill_files[@]}"
  doc/reference/*.md
)

dup_check_files=(
  CLAUDE.md
  PLAN.md
  PROGRESS.md
  frontend/CLAUDE.md
  backend/CLAUDE.md
  frontend/AGENTS.md
  "${skill_files[@]}"
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
  backend/CLAUDE.md
  frontend/AGENTS.md
  "${skill_files[@]}"
  doc/reference/*.md
)

# 기준 문서 제목에 날짜가 박히는 것을 막는다(`2c-2`).
#
# `doc/README.md` 가 「지나간 작업 이력은 문서에 안 쓴다 — PROGRESS.md 와 git log 가 답한다」고
# 정했는데, 「2026-08-20 규약 대조 — 처분 완료」 같은 제목이 열 파일에 눌어붙어 있었다(260줄).
# **제목에 날짜가 있으면 그 절은 규칙이 아니라 그날의 기록이다.**
#
# `stack.md` 의 절 제목들은 날짜가 없어서 안 걸린다 — 「기억으로 쓰면 틀리는 자리」는
# 언제 알았든 지금도 유효한 사실이라 날짜를 안 단다.
dated_title_files=(
  doc/reference/*.md
)

fail=0

for f in "${title_check_files[@]}"; do
  [ -f "$f" ] || continue
  first_line=$(head -n 1 "$f")
  # 스킬 파일은 frontmatter 가 먼저다 — 닫는 `---` 다음의 첫 줄을 본다.
  if [ "$first_line" = "---" ]; then
    first_line=$(awk 'NR>1 && /^---$/{f=1; next} f && NF{print; exit}' "$f")
  fi
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

for f in "${dated_title_files[@]}"; do
  [ -f "$f" ] || continue
  # `external-references.md` 는 뺀다. **거기는 날짜가 내용이다** — 인용 자료를 언제 원문으로
  # 확인했는지가 그 문서의 값이고(`document-map.md`), 낡았는지는 그 날짜로만 판정한다.
  case "$f" in doc/reference/external-references.md) continue ;; esac


  dated=$(grep -nE '^#+[[:space:]].*[12][0-9]{3}-[0-9]{2}-[0-9]{2}' "$f")
  if [ -n "$dated" ]; then
    echo "[제목에 날짜] $f — 기준 문서는 「지금 무엇이 맞나」만 답한다. 이력은 PROGRESS.md 로(doc/README.md):"
    echo "$dated" | sed 's/^/    /'
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
