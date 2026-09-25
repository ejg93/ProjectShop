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

# 세션을 여는 말(`Q158`)도 같은 종류다 — 스킬이 「세션이 따르는 절차」라면 이쪽은
# 「사람이 붙여넣는 말」이고, 둘 다 저장소가 드는 규칙 문서다. 올려 놓고 검사에서 빼면
# **기계가 안 보는 규칙 문서 종류가 하나 생긴다**(마무리 43차 독립 리뷰).
prompt_files=()
for f in .claude/prompts/*.md; do
  [ -e "$f" ] && prompt_files+=("$f")
done

title_check_files=(
  CLAUDE.md
  backend/CLAUDE.md
  PLAN.md
  PROGRESS.md
  "${skill_files[@]}"
  "${prompt_files[@]}"
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
  "${prompt_files[@]}"
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
  "${prompt_files[@]}"
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

# **표 행은 문장 중복 검사가 안 본다** — 아래 루프가 `|` 로 시작하는 줄을 걷어낸다. 그 구멍으로
# 2026-09-21 첫 합치기가 `PROGRESS.md` 이력 다섯 행을 **바이트까지 같은 채로** 두 번씩 넣었고
# 그 가지의 CI 가 전부 초록이었다 — `PlanProgressConsistencyTest` 는 칸 수와 커밋 칸만 봐서
# 같은 행이 둘인 것을 안 센다. 원인은 `awk 'NR==N{print; print "새 행"} {print}'` 의 `next` 누락이라
# **사람이 다시 낼 수 있는 모양**이다.
#
# **표 머리는 뺀다** — 다음 줄이 구분선(`|---|`)이면 머리다. 같은 머리가 여러 표에 정당하게 반복된다
# (`PLAN.md` 의 `| # | 청크 | 무엇을 하나 | 선행 |` 이 실측 셋이다).
# **40자 미만도 뺀다** — `| 〃 | 〃 |` 같은 짧은 칸은 반복이 정상이다.
dup_table_rows() {
  awk '/^```/{c=!c; next} !c' "$1" \
    | awk '{L[NR]=$0} END{for(i=1;i<=NR;i++){nx=(i<NR?L[i+1]:""); if (nx ~ /^[[:space:]]*\|[-:| ]+$/) continue; print L[i]}}' \
    | grep -E '^[[:space:]]*\|' \
    | grep -vE '^[[:space:]]*\|[-:| ]+$' \
    | awk 'length($0) >= 40' \
    | sort | uniq -d
}

# **게이트를 세워 놓고 게이트 표에 안 올리는 것**(`Q147`, `D25`). 2026-09-21 하루에 세 번 났고
# 세 번 다 PR 리뷰가 잡았다 — 규칙은 `quality-gates.md` 에 이미 있는데 청크마다 적용이 갈렸다.
#
# **무엇이 게이트인가를 「저장소 원문을 읽나」로 가른다.** 기능 시험은 자기가 넣은 데이터를 보고
# 게이트는 소스·스키마·문서를 읽어서 저장소 전체의 불변을 잰다. 실측이 그 잣대를 지지한다 —
# 원문을 읽는 시험 22개 중 **19개가 이미 표에 있다**(2026-09-21). 새로 생긴 `*Test.java` 전부를
# 재면 절반이 기능 시험이라 노이즈가 된다.
#
# **기준선은 내리기만 한다.** `Q153` 이 남은 셋(`SchemaErdTest`·`PermissionMatrixTest`·`DataLifecycleCoverageTest`)을
# 열어 보고 셋 다 게이트라 표에 올렸다 — 그래서 지금은 0이다.
gate_rows_missing() { # 시험 뿌리, 게이트 표
  grep -rl 'Files\.read\|Path\.of("\|Paths\.get("' "$1" --include=*.java 2>/dev/null \
    | while read -r f; do
        n=$(basename "$f" .java)
        grep -q "$n" "$2" || echo "$n"
      done | sort
}

# **화면 쪽도 같은 잣대다**(`Q156`). `Q147` 이 backend 만 봐서, 오늘 선 프론트 게이트 둘을
# 사람이 손으로 표에 올렸다 — **그것이 `Q147` 이 막으려던 바로 그 모양**이다.
# 여기서도 「저장소 원문을 읽나」로 가른다(`readFileSync`). **파일 이름을 확장자째 쓴다** —
# `.ts` 와 `.tsx` 가 섞여 있어서 떼면 서로 다른 게이트가 한 이름이 된다.
gate_rows_missing_screen() { # 화면 뿌리, 게이트 표
  grep -rl "readFileSync" "$1" --include=*.ts --include=*.tsx 2>/dev/null \
    | while read -r f; do
        n=$(basename "$f")
        grep -q "$n" "$2" || echo "$n"
      done | sort
}

# 원문을 정규식으로 읽는 게이트는 자기 시험을 같이 세운다(`quality-gates.md`). **빠진 것은
# 게이트가 스스로 못 알려 준다** — 실물이 초록인 것은 「구멍이 없다」와 「정규식이 아무것도 안 잡는다」가
# 구별이 안 된다.
if [ "${1:-}" = "--selftest" ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  st_fail=0
  st_check() { # 이름, 본문, 걸려야 하나(1/0)
    printf '%s\n' "$2" > "$tmp/doc.md"
    got=$(dup_table_rows "$tmp/doc.md" | grep -c . || true)
    if { [ "$3" = 1 ] && [ "$got" -eq 0 ]; } || { [ "$3" = 0 ] && [ "$got" -ne 0 ]; }; then
      echo "  [실패] $1 — 걸려야 하나=$3 실제=$got"; st_fail=1
    else
      echo "  [통과] $1"
    fi
  }
  echo "중복 표 행 검사 다섯 모양을 잰다:"
  st_check "같은 이력 행이 두 번 — 걸려야 한다" \
'| 2026-09-21 | `Q144` 시행 시각 시험이 벽시계에 매달린다 | 데이터 기준으로 바꿨다 | abc1234 |
| 2026-09-21 | `Q144` 시행 시각 시험이 벽시계에 매달린다 | 데이터 기준으로 바꿨다 | abc1234 |' 1
  st_check "같은 표 머리가 두 표에 — 안 걸려야 한다" \
'| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 1 | 첫째 | 무엇 | 없음 |

| # | 청크 | 무엇을 하나 | 선행 |
|---|---|---|---|
| 2 | 둘째 | 무엇 | 없음 |' 0
  st_check "구분선이 여러 번 — 안 걸려야 한다" \
'| 가 | 나 | 다 | 라 | 마 | 바 | 사 | 아 | 자 | 차 |
|---|---|---|---|---|---|---|---|---|---|
| 가 | 나 | 다 | 라 | 마 | 바 | 사 | 아 | 자 | 차차 |
|---|---|---|---|---|---|---|---|---|---|' 0
  st_check "40자 미만 짧은 행이 반복 — 안 걸려야 한다" \
'| 〃 | 없다 |
| 〃 | 없다 |' 0
  st_check "코드펜스 안의 중복 표 행 — 안 걸려야 한다" \
'```
| 2026-09-21 | `Q144` 시행 시각 시험이 벽시계에 매달린다 | 데이터 기준 | abc1234 |
| 2026-09-21 | `Q144` 시행 시각 시험이 벽시계에 매달린다 | 데이터 기준 | abc1234 |
```' 0
  st_gate() { # 이름, 시험 본문, 표 본문, 걸려야 하나(1/0)
    rm -rf "$tmp/t"; mkdir -p "$tmp/t"
    printf '%s\n' "$2" > "$tmp/t/SomeGateTest.java"
    printf '%s\n' "$3" > "$tmp/gates.md"
    got=$(gate_rows_missing "$tmp/t" "$tmp/gates.md" | grep -c . || true)
    if { [ "$4" = 1 ] && [ "$got" -eq 0 ]; } || { [ "$4" = 0 ] && [ "$got" -ne 0 ]; }; then
      echo "  [실패] $1 — 걸려야 하나=$4 실제=$got"; st_fail=1
    else
      echo "  [통과] $1"
    fi
  }
  echo "게이트 표 누락 검사 세 모양을 잰다:"
  st_gate "원문을 읽는데 표에 없다 — 걸려야 한다" \
    'var text = Files.readString(Path.of("PLAN.md"));' '| 다른 게이트 | 4 테스트 |' 1
  st_gate "원문을 읽고 표에도 있다 — 안 걸려야 한다" \
    'var text = Files.readString(Path.of("PLAN.md"));' '| `SomeGateTest` | 4 테스트 |' 0
  st_gate "원문을 안 읽는 기능 시험 — 표에 없어도 안 걸려야 한다" \
    'var order = repository.save(new Order());' '| 다른 게이트 | 4 테스트 |' 0

  st_screen() { # 이름, 시험 본문, 표 본문, 걸려야 하나(1/0)
    rm -rf "$tmp/s"; mkdir -p "$tmp/s"
    printf '%s\n' "$2" > "$tmp/s/some-gate.test.ts"
    printf '%s\n' "$3" > "$tmp/gates.md"
    got=$(gate_rows_missing_screen "$tmp/s" "$tmp/gates.md" | grep -c . || true)
    if { [ "$4" = 1 ] && [ "$got" -eq 0 ]; } || { [ "$4" = 0 ] && [ "$got" -ne 0 ]; }; then
      echo "  [실패] $1 — 걸려야 하나=$4 실제=$got"; st_fail=1
    else
      echo "  [통과] $1"
    fi
  }
  echo "화면 게이트 표 누락 검사 세 모양을 잰다:"
  st_screen "원문을 읽는데 표에 없다 — 걸려야 한다" \
    'const SOURCE = readFileSync(resolve(dir, "page.tsx"), "utf8");' '| 다른 게이트 | 4 테스트 |' 1
  st_screen "원문을 읽고 표에도 있다 — 안 걸려야 한다" \
    'const SOURCE = readFileSync(resolve(dir, "page.tsx"), "utf8");' '| `some-gate.test.ts` | 4 테스트 |' 0
  st_screen "원문을 안 읽는 화면 시험 — 표에 없어도 안 걸려야 한다" \
    'render(<Form />); expect(screen.getByRole("button")).toBeTruthy();' '| 다른 게이트 | 4 테스트 |' 0

  [ "$st_fail" -eq 0 ] && echo "자기 시험 통과"
  exit "$st_fail"
fi

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

  rows=$(dup_table_rows "$f")
  if [ -n "$rows" ]; then
    echo "[중복 표 행] $f — 같은 행이 두 번이다. 합치다 겹친 것이면 한쪽을 지운다:"
    echo "$rows" | cut -c1-160 | sed 's/^/    /'
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

# 분할표에서 안 닫힌 행만 뽑는다. **아래 두 검사가 같은 판정을 쓴다** — 두 벌로 두면 갈린다.
# 행 판정은 `PlanProgressConsistencyTest` 와 같다 — 번호 칸이나 이름 칸의 취소선, 선행 칸의 `완료`.
# `#`·`칸` 은 표 머리다(분할표와 그 앞의 칸 설명 표).
plan_open_rows() {
  awk '/^## 청크 분할표/{on=1} on && /^\| [^-|*][^|]*\|/{
    n=split($0,c,"|"); id=c[2]; gsub(/^ +| +$/,"",id); nm=c[3]; gsub(/^ +/,"",nm);
    last=c[n-1]; gsub(/^ +| +$/,"",last);
    if (id=="#" || id=="칸" || id ~ /^~~/ || nm ~ /^~~/ || last=="완료") next;
    print
  }' PLAN.md
}

# 안 닫힌 행에 축·강제 지점·닫힘이 다 있나(`2t`). 셋 중 하나라도 빠진 행 수가
# 기준선을 넘으면 빨갛다 — **기준선은 내리기만 한다.** 지난 행 74개에 「닫힘」이 없어서
# 0 으로 시작할 수 없었고, 새 행이 그 수를 늘리는 것만 막는다. 수가 줄면 여기 숫자를 같이 내린다.
plan_open_incomplete_baseline=0
plan_open_incomplete=$(plan_open_rows | awk '
    $0 !~ /\*\*축\*\*/ || $0 !~ /\*\*강제 지점\*\*/ || $0 !~ /\*\*닫힘\*\*/ {k++}
  END{print k+0}')
if [ "$plan_open_incomplete" -gt "$plan_open_incomplete_baseline" ]; then
  echo "[분할표 칸 누락] PLAN.md — 안 닫힌 행 중 축·강제 지점·닫힘이 빠진 것이 ${plan_open_incomplete}개 (기준선 ${plan_open_incomplete_baseline}). 새 행에는 넷을 다 적는다(PLAN.md 「청크 분할표」)"
  fail=1
elif [ "$plan_open_incomplete" -lt "$plan_open_incomplete_baseline" ]; then
  echo "[기준선 내릴 것] PLAN.md — 칸 빠진 행이 ${plan_open_incomplete}개로 줄었다. scripts/doc-lint.sh 의 plan_open_incomplete_baseline 을 그 수로 내린다"
fi

# 구간 표(「구간 — 배포를 결승선으로 놓는다」)가 안 닫힌 청크를 다 담나. 담기는 것이 **차례**라
# 빠진 행은 「나중에 한다」가 아니라 **아무 문서도 차례를 안 드는 상태**가 된다 —
# `PROGRESS.md` 「현재 상태」가 순서를 이 표에 넘겼기 때문이다.
#
# **래칫이 아니라 0 기준이다.** 칸 누락과 달리 과거 부채가 없다 — 표를 세운 날 흘린 열을
# `Q87` 이 같이 채웠다. 하루 만에 흘린 것이라 되돌리는 diff 가 작았다.
#
# **청크 칸만 본다.** 「왜 여기」 칸의 산문이 번호를 스쳐도 담긴 것으로 안 친다 —
# 산문은 고쳐도 차례가 안 바뀌는 자리라, 거기에 기대면 차례가 산문에 숨는다.
#
# **범위 표기는 못 읽는다.** `46`~`48` 로 줄여 적으면 47 이 빠진 것으로 잡힌다. 그것이 의도다 —
# 사람이 읽는 표와 기계가 읽는 표가 갈리면 기계 쪽이 헛것을 센다(`Q87` 이 낱개로 폈다).
plan_unplaced=$(comm -23 \
  <(plan_open_rows | awk -F'|' '{id=$2; gsub(/^ +| +$/,"",id); print id}' | sort -u) \
  <(awk '/^## 구간/{on=1; next} on && /^## /{on=0} on && /^\| /{
      n=split($0,c,"|"); if (n<4) next; s=c[3];
      while (match(s, /`[^`]+`/)) { print substr(s, RSTART+1, RLENGTH-2); s=substr(s, RSTART+RLENGTH) }
    }' PLAN.md | sort -u) | tr '\n' ' ')
if [ -n "${plan_unplaced// /}" ]; then
  echo "[구간 누락] PLAN.md — 안 닫힌 청크 중 구간 표에 없는 것: ${plan_unplaced}"
  echo "    다섯 구간 중 하나의 청크 칸에 낱개로 적는다(PLAN.md 「구간」)"
  fail=1
fi

# **곧 칠 행은 칸 넷이 다 차 있나.** 위 「분할표 칸 누락」은 **래칫**이라 기준선(위 `plan_open_incomplete_baseline`) 안에 이미
# 든 행은 더 망가져도 안 걸린다 — `64` 가 `Q39` 몫을 흡수하면서 범위가 늘었는데 **닫힘 칸은
# 빈 채로 지나갔고**, 마무리 25차 독립 리뷰가 손으로 찾아야 했다.
#
# **구간 ①·② 로 좁힌다.** 미착수 행 전부에 0 기준을 걸면 그 기준선을 0으로 내리는 큰 일이 되고,
# 큰 일은 안 지켜진다 — **안 지켜지는 규칙은 없는 규칙이다**(`/warmup`). 차례가 잡힌 것부터
# 채우면 구간이 앞으로 갈수록 자연히 다 찬다. **행이 ③으로 물러나면 이 검사에서도 빠진다** —
# 곧 안 칠 것에 닫힘을 미리 쓰면 **무엇을 닫는지 모르는 채로 쓰게 된다**(`CLAUDE.md`
# 「문서가 코드보다 먼저다」의 둘째 조건과 같은 이유다).
near_ids=$(awk '/^## 구간/{on=1; next} on && /^## /{on=0} on && (/\*\*① 얼굴\*\*/ || /\*\*② 배포 앞\*\*/){
    n=split($0,c,"|"); if (n<4) next; s=c[3];
    while (match(s, /`[^`]+`/)) { print substr(s, RSTART+1, RLENGTH-2); s=substr(s, RSTART+RLENGTH) }
  }' PLAN.md | sort -u)
near_incomplete=""
for id in $near_ids; do
  row=$(plan_open_rows | awk -F'|' -v want="$id" '{gsub(/^ +| +$/,"",$2); if ($2==want) print}')
  [ -z "$row" ] && continue   # 닫힌 행이면 볼 것이 없다
  miss=""
  case "$row" in *'**축**'*) ;; *) miss="$miss 축" ;; esac
  case "$row" in *'**강제 지점**'*) ;; *) miss="$miss 강제지점" ;; esac
  case "$row" in *'**닫힘**'*) ;; *) miss="$miss 닫힘" ;; esac
  [ -n "$miss" ] && near_incomplete="${near_incomplete}  ${id} —${miss}"$'\n'
done
if [ -n "${near_incomplete//[$'\n' ]/}" ]; then
  echo "[곧 칠 행 칸 누락] PLAN.md — 구간 ①·② 의 행은 칸 넷이 다 있어야 한다. 빠진 것:"
  printf '%s' "$near_incomplete"
  echo "    닫힘은 「무엇이 초록이면 끝인가」를 검사할 수 있는 이름으로 적는다(PLAN.md 「청크 분할표」)"
  fail=1
fi

# 차례를 주장하는 문장이 구간 표 밖에 있나. **차례의 주인은 `PLAN.md` 「구간」 표 하나다** —
# `PROGRESS.md` 「현재 상태」가 순서를 그 표에 넘겼고, 그러면 다른 자리의 같은 말은
# **사본**이라 원본이 바뀌어도 안 따라온다.
#
# **막으려는 사고**: 2026-09-17 에 `Q39` 를 맨 끝에서 구간 ②로 옮겼는데, 그 말을 베껴 둔
# 여섯 자리가 그대로 남았다. 그중 `README.md` 는 포폴 독자가 읽는 유일한 입구다.
# **마무리 25차 독립 리뷰가 일곱을 손으로 셌고, 이 검사는 리뷰가 못 본 `coding-rules.md` 를 더 냈다.**
#
# **예외를 안 판다.** 구멍을 파면 쓰인다 — 끝난 일은 과거형으로 고쳐 적으면 순서 단어가 안 남는다.
# 스캔 밖인 두 자리는 성격이 달라서다: 구간 표는 **주인**이고 `PROGRESS.md` 「이력」은
# **그때는 맞았던 기록**이다(고치면 서사가 거짓이 된다).
#
# `「」` 안은 걷어낸다 — 남의 문장을 옮겨 적는 자리라 순서 단어가 그대로 들어온다(존댓말 검사와 같다).
order_word='(맨 마지막|마지막 청크|맨 뒤)'
chunk_ref='(`(Q|D)?[0-9]+[a-z0-9-]*`|Q[0-9]+)'
order_claims=""
for f in README.md backend/README.md CLAUDE.md doc/reference/*.md; do
  [ -f "$f" ] || continue
  h=$(perl -CSD -pe 's/\x{300C}.*?\x{300D}//g' "$f" | grep -nE "$order_word" | grep -E "$chunk_ref")
  [ -n "$h" ] && order_claims="${order_claims}$(echo "$h" | sed "s|^|  $f:|")"$'\n'
done
# `PLAN.md` 는 구간 절(`## 구간` ~ 다음 `## `)을 뺀 나머지, `PROGRESS.md` 는 이력을 뺀 나머지.
for pair in "PLAN.md:^## 구간" "PROGRESS.md:^## 이력"; do
  f=${pair%%:*}; skip=${pair#*:}
  h=$(awk -v skip="$skip" '$0 ~ skip {off=1; next} off && /^## /{off=0} {print (off ? "" : $0)}' "$f" \
    | perl -CSD -pe 's/\x{300C}.*?\x{300D}//g' | grep -nE "$order_word" | grep -E "$chunk_ref")
  [ -n "$h" ] && order_claims="${order_claims}$(echo "$h" | sed "s|^|  $f:|")"$'\n'
done
if [ -n "${order_claims//[$'\n' ]/}" ]; then
  echo "[차례 사본] 청크의 차례는 PLAN.md 「구간」 표만 든다. 다른 자리는 그 표를 가리킨다:"
  printf '%s' "$order_claims" | cut -c1-160
  fail=1
fi

# 이력이 날짜순인가(`W3`). 앞줄보다 이른 날짜가 오면 센다 — 그 수가 기준선을 넘으면 빨갛다.
# **기준선은 내리기만 한다.** 2026-09-11 에 이미 일곱이었고(299~393줄이 통째로 역순이다)
# 그것을 되돌리면 diff 가 95줄 이동이라 아무도 못 읽는다. **새 줄이 그 수를 늘리는 것만 막는다.**
#
# **막으려는 사고가 무엇인지**: 이력 줄을 줄 번호로 끼워 넣다가 엉뚱한 날짜 사이에 넣는 것이다.
# 1차 마무리가 **하루에 세 번** 했고 둘은 커밋까지 갔다. 그러면 **같은 날짜가 표의 두 자리로
# 흩어지고** 「그날 뭐 했나」를 찾는 사람이 한쪽만 보고 만다. 지금 `08-12`·`08-18` 이 그 상태다.
#
# **주제로 묶는 것은 이력이 아니라 분할표가 한다**(「분할표와 이력의 분업」) —
# 이력은 시간순이고 분할표가 청크순이다. 섞으면 「같은 말을 두 번 안 쓴다」가 깨진다.
history_unsorted_baseline=7
history_unsorted=$(awk '/^## 이력/{on=1; next} on && /^## /{on=0} on && /^\| [0-9]{4}-[0-9]{2}-[0-9]{2} \|/{
    d=substr($0,3,10); if (prev != "" && d < prev) k++; prev=d
  } END{print k+0}' PROGRESS.md)
if [ "$history_unsorted" -gt "$history_unsorted_baseline" ]; then
  echo "[이력 순서] PROGRESS.md — 앞줄보다 이른 날짜가 ${history_unsorted}곳 (기준선 ${history_unsorted_baseline}). 새 줄은 표 맨 아래에 붙인다(PROGRESS.md 「기록 규칙」)"
  fail=1
elif [ "$history_unsorted" -lt "$history_unsorted_baseline" ]; then
  echo "[기준선 내릴 것] PROGRESS.md — 이력 어긋남이 ${history_unsorted}곳으로 줄었다. scripts/doc-lint.sh 의 history_unsorted_baseline 을 그 수로 내린다"
fi

# 「현재 상태」는 표다(`2u`). 서사가 붙기 시작하면 세션마다 hook 이 그것을 통째로 주입한다(`2q`) —
# 2026-09-06 에 119줄이었다. 상한을 넘으면 빨갛다.
state_lines=$(awk '/^## 현재 상태$/{on=1; next} /^## /{on=0} on' PROGRESS.md | wc -l)
if [ "$state_lines" -gt 25 ]; then
  echo "[현재 상태 비대] PROGRESS.md — 「현재 상태」가 ${state_lines}줄이다(상한 25). 표만 남기고 서사는 이력으로(PROGRESS.md 「기록 규칙」)"
  fail=1
fi

# 게이트를 세웠으면 게이트 표에 행이 있나(`Q147`). 위 `gate_rows_missing` 이 잣대를 든다.
gate_missing_baseline=0
gate_missing=$(gate_rows_missing backend/src/test doc/reference/quality-gates.md)
gate_missing_count=$(printf '%s' "$gate_missing" | grep -c . || true)
if [ "$gate_missing_count" -gt "$gate_missing_baseline" ]; then
  echo "[게이트 표 누락] 저장소 원문을 읽는 시험인데 quality-gates.md 에 이름이 없다 (${gate_missing_count}개, 기준선 ${gate_missing_baseline}):"
  printf '%s\n' "$gate_missing" | sed 's/^/    /'
  echo "    「지금 무엇이 어디에 걸려 있나」 표에 행을 세운다 — 층·어디서 도나·무엇을 막나·부순 날(quality-gates.md)"
  fail=1
elif [ "$gate_missing_count" -lt "$gate_missing_baseline" ]; then
  echo "[기준선 내릴 것] 게이트 표 누락이 ${gate_missing_count}개로 줄었다. scripts/doc-lint.sh 의 gate_missing_baseline 을 그 수로 내린다"
fi

# 화면 쪽도 **0 기준이다**(`Q156`) — `Q156` 이 셀 때 둘이 빠져 있었고 같은 청크가 그 둘을 표에 올렸다.
screen_gate_missing=$(gate_rows_missing_screen frontend/src doc/reference/quality-gates.md)
if [ -n "${screen_gate_missing//[$'\n' ]/}" ]; then
  echo "[게이트 표 누락] 저장소 원문을 읽는 화면 시험인데 quality-gates.md 에 이름이 없다:"
  printf '%s\n' "$screen_gate_missing" | sed 's/^/    /'
  echo "    「지금 무엇이 어디에 걸려 있나」 표에 행을 세운다 — 층·어디서 도나·무엇을 막나·부순 날(quality-gates.md)"
  fail=1
fi

# CI 가 돌리는 스크립트도 게이트다. **이쪽은 0 기준이다** — 실측이 0이라 과거 부채가 없다.
script_missing=$(grep -oE 'scripts/[a-z-]+\.sh' .github/workflows/ci.yml | sort -u | while read -r s; do
  n=$(basename "$s" .sh)
  grep -q "$n" doc/reference/quality-gates.md || echo "$n"
done)
if [ -n "${script_missing//[$'\n' ]/}" ]; then
  echo "[게이트 표 누락] CI 가 돌리는데 quality-gates.md 에 이름이 없는 스크립트:"
  printf '%s\n' "$script_missing" | sed 's/^/    /'
  fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "이상 없음 — 검사한 파일 전부 통과"
fi
exit "$fail"
