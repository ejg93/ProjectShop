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
set -uo pipefail
cd "$(dirname "$0")/.."

table=doc/reference/commerce-compliance.md
plan=PLAN.md

# **내리기만 한다.** 정정이 끝나면 이 수는 줄고, 늘었다는 것은 산문이 또 뒤처졌다는 뜻이다.
stale_baseline=0

# 분할표에서 상태가 `완료` 인 청크 번호. 마지막 칸이 상태다.
done_ids=$(awk -F'|' '/^\| *[0-9]/{
  id=$2; gsub(/[ `~*]/,"",id);
  st=$(NF-1); gsub(/[ *]/,"",st);
  if (id != "" && st == "완료") print id;
}' "$plan" | sort -u)

# 산문 절만 본다 — 표 행(`^|`)은 매핑표고 위 시험이 이미 지킨다.
findings=$(awk -v ids="$done_ids" '
  BEGIN { n=split(ids, a, "\n"); for (i=1;i<=n;i++) if (a[i] != "") done[a[i]]=1 }
  /^\|/ { next }
  {
    line=$0;
    # 주어 자리에 선 청크 번호: 백틱으로 닫힌 직후에 조사가 붙는다.
    #   걸린다  → 청크 `5j` 가 채운다
    #   안 걸린다 → `RefundSweeper`(`12a-3`)가 ... 만든다   (닫는 백틱 뒤가 괄호다)
    stale=0;
    rest=line;
    while (match(rest, /`[0-9]+[a-z]?(-[0-9]+[a-z]?)?` *(가|이) /)) {
      tok=substr(rest, RSTART, RLENGTH);
      gsub(/[` ]/, "", tok); sub(/(가|이)$/, "", tok);
      if (tok in done && line ~ /(받는다|채운다|만든다|세운다|정한다|받을 것이다|채울 것이다)/) stale=1;
      rest=substr(rest, RSTART+RLENGTH);
    }
    # 「미착수」·「아직 없다」 는 조사 모양을 안 탄다 — 단어 자체가 상태 선언이다.
    if (line ~ /(미착수|아직 없다)/) {
      rest=line;
      while (match(rest, /`[0-9]+[a-z]?(-[0-9]+[a-z]?)?`/)) {
        tok=substr(rest, RSTART, RLENGTH); gsub(/`/, "", tok);
        if (tok in done) stale=1;
        rest=substr(rest, RSTART+RLENGTH);
      }
    }
    if (stale) printf "%s:%d: %s\n", FILENAME, FNR, substr(line, 1, 110);
  }
' "$table")

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
