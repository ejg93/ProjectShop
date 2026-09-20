#!/usr/bin/env bash
# 요건표(D2)의 **산문 절**이 이미 닫힌 청크를 아직 안 한 것처럼 드는 자리를 잡는다.
#
# **매핑표는 이미 지켜지고 있다**(`RequirementEnforcementTest`, `Q33`) — 백틱 안의 제약명·
# 클래스명·마이그레이션 번호가 실물인지 그 시험이 묻는다. 그런데 그 시험이 보는 것은
# **네 모양**뿐이고 요건표 자신이 「경로·청크 번호·산문은 안 잰다」고 적어 뒀다.
# 그래서 청크가 완료돼도 산문이 안 따라가고, 표와 산문이 서로 다른 말을 한다.
#
# `점검 가로 · 법`(2026-09-20)이 그 자리를 여덟 찾았다. 셋은 알림 축(`54a`~`56`)을
# 「발송 인프라 자체가 미착수」로 들고 있었는데 넷 다 완료였고, 하나는 `R19` 가
# 「`15-4` 가 받는다」고 적는데 실제로 받은 것은 `15-5` 였다 — **매핑표는 그 자리가 이미
# 갱신돼 있었다.** 법 요건을 읽는 사람이 채워진 것을 구멍으로 보고, 구멍을 채워진 것으로 본다.
#
# **범위를 좁게 잡는다.** 미래형 표현은 자연어라 계획 서술로 정당한 자리가 있다 —
# 「`RefundSweeper`(`12a-3`)가 ... 요청을 만든다」는 현재 도는 것을 적은 것이라 안 걸려야 한다.
# 그래서 **청크 번호가 주어 자리에 선 것**(백틱 바로 뒤에 조사 `가`/`이`)만 본다.
#
# **정규식이 무는 것과 안 무는 것을 따로 잰다**(`Q152`). `Q150` 이 세울 때는 「실물이 통과한다」와
# 「고쳐야 할 모양이 빨갛다」 둘만 쟀고 **형태가 달라서 빠지는 것**을 안 쟀다 — `Q` 번호 청크가
# 양쪽에서 빠져 있었다. 분할표의 `Q` 행은 **상태 칸 없이 마지막 칸이 커밋 해시**라
# 상태를 읽는 추출이 하나도 안 잡았고, 패턴도 숫자로 시작하는 것만 봤다.
# **빠진 것은 게이트가 스스로 못 알려 준다** — 그래서 네 모양을 `--selftest` 가 따로 잰다.
set -uo pipefail
cd "$(dirname "$0")/.."

table=doc/reference/commerce-compliance.md
plan=PLAN.md

# **내리기만 한다.** 정정이 끝나면 이 수는 줄고, 늘었다는 것은 산문이 또 뒤처졌다는 뜻이다.
stale_baseline=0

# 분할표에서 닫힌 청크 번호. **행 모양이 둘이다** — 숫자 청크는 마지막에서 두 번째 칸이
# 상태고, `Q` 청크는 상태 칸 없이 마지막 칸이 커밋 해시라 **제목의 취소선**이 닫힘을 말한다.
collect_done() {
  awk -F'|' '
    /^\| *[0-9]/ {
      id=$2; gsub(/[ `~*]/, "", id);
      st=$(NF-1); gsub(/[ *]/, "", st);
      if (id != "" && st == "완료") print id;
      next;
    }
    /^\| *Q[0-9]/ {
      id=$2; gsub(/[ `~*]/, "", id);
      title=$3; desc=$4;
      if (id != "" && (title ~ /~~/ || desc ~ /완료/)) print id;
    }
  ' "$1" | sort -u
}

# 산문 절만 본다 — 표 행(`^|`)은 매핑표고 위 시험이 이미 지킨다.
scan() {
  awk -v ids="$2" '
    BEGIN { n=split(ids, a, "\n"); for (i=1;i<=n;i++) if (a[i] != "") done[a[i]]=1 }
    /^\|/ { next }
    {
      line=$0; stale=0;
      # 주어 자리에 선 청크 번호: 백틱으로 닫힌 직후에 조사가 붙는다.
      #   걸린다   → 청크 `5j` 가 채운다
      #   안 걸린다 → `RefundSweeper`(`12a-3`)가 ... 만든다   (닫는 백틱 뒤가 괄호다)
      rest=line;
      while (match(rest, /`Q?[0-9]+[a-z]?(-[0-9]+[a-z]?)?` *(가|이) /)) {
        tok=substr(rest, RSTART, RLENGTH);
        gsub(/[` ]/, "", tok); sub(/(가|이)$/, "", tok);
        if (tok in done && line ~ /(받는다|채운다|만든다|세운다|정한다|받을 것이다|채울 것이다)/) stale=1;
        rest=substr(rest, RSTART+RLENGTH);
      }
      # 「미착수」·「아직 없다」 는 조사 모양을 안 탄다 — 단어 자체가 상태 선언이다.
      if (line ~ /(미착수|아직 없다)/) {
        rest=line;
        while (match(rest, /`Q?[0-9]+[a-z]?(-[0-9]+[a-z]?)?`/)) {
          tok=substr(rest, RSTART, RLENGTH); gsub(/`/, "", tok);
          if (tok in done) stale=1;
          rest=substr(rest, RSTART+RLENGTH);
        }
      }
      if (stale) printf "%s:%d: %s\n", FILENAME, FNR, substr(line, 1, 110);
    }
  ' "$1"
}

# **네 모양을 따로 잰다**(`Q152`). 실물이 통과하는 것만으로는 정규식이 성립했다는 증거가 안 된다.
if [ "${1:-}" = "--selftest" ]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  cat > "$tmp/plan.md" <<'FIX'
| 5j | ~~동의 고지 항목~~ | **완료.** | 완료 |
| Q13 | ~~정보주체 권리~~ | **완료.** | `0e3559f` |
| 99z | 아직 안 한 것 | 설명 | |
FIX
  ids=$(collect_done "$tmp/plan.md")
  fail=0
  check() { # 이름, 본문, 걸려야 하나(1/0)
    printf '%s\n' "$2" > "$tmp/doc.md"
    got=$(scan "$tmp/doc.md" "$ids" | grep -c . || true)
    want=$3
    if { [ "$want" = 1 ] && [ "$got" -eq 0 ]; } || { [ "$want" = 0 ] && [ "$got" -ne 0 ]; }; then
      echo "  [실패] $1 — 걸려야 하나=$want 실제=$got"; fail=1
    else
      echo "  [통과] $1"
    fi
  }
  echo "네 모양을 잰다:"
  check "닫힌 숫자 청크를 미래형으로 — 걸려야 한다"      '청크 `5j` 가 채운다.' 1
  check "닫힌 Q 청크를 미래형으로 — 걸려야 한다"          '청크 `Q13` 이 채운다.' 1
  check "현재형 서술은 안 걸려야 한다"                    '`RefundSweeper`(`5j`)가 요청을 만든다.' 0
  check "미착수 청크를 미래형으로 — 안 걸려야 한다"        '청크 `99z` 가 채운다.' 0
  [ "$fail" -eq 0 ] && echo "네 모양 전부 통과" || echo "네 모양 중 실패가 있다"
  exit "$fail"
fi

findings=$(scan "$table" "$(collect_done "$plan")")
count=$(printf '%s' "$findings" | grep -c . || true)

if [ "$count" -gt 0 ]; then
  echo "$findings"
  echo
fi

if [ "$count" -gt "$stale_baseline" ]; then
  echo "[산문이 뒤처졌다] $table — 닫힌 청크를 아직 안 한 것처럼 드는 자리 ${count}곳(기준선 ${stale_baseline})."
  echo "  그 청크가 실제로 무엇을 세웠는지 PLAN.md 완료 행에서 읽고 산문을 그 사실로 고친다."
  exit 1
fi

if [ "$count" -lt "$stale_baseline" ]; then
  echo "[기준선 내릴 것] $table — ${count}곳으로 줄었다. scripts/req-prose-staleness.sh 의 stale_baseline 을 그 수로 내린다"
  exit 1
fi

echo "이상 없음 — 닫힌 청크를 미착수로 드는 산문 ${count}곳(기준선 ${stale_baseline})"
exit 0
