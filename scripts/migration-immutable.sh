#!/usr/bin/env bash
#
# 배포된 마이그레이션을 고치거나 지웠나(`Q51`).
#
# **첫 배포 전에는 안 잰다.** 기준점이 없으면 「고쳤다」를 판정할 대상이 없고,
# 지금 켜면 `Q36` 이 열여섯 파일을 접은 것이 바로 빨개진다. 배포 전의 마이그레이션은
# 가변이고 그것이 맞다 — 나간 적이 없으니 남의 DB 에 박힌 체크섬이 없다.
#
# **기준점을 태그가 아니라 파일에 둔다**(사용자 선택 ②). 태그로 두면 상태 셋 중 둘이
# 같은 모양이 된다 — 「배포 전이라 태그가 없다」와 「CI 가 얕게 받아서 태그가 안 왔다」가
# 둘 다 빈 목록이고, 뒤엣것은 **게이트가 꺼진 채 초록**이다. 파일이면 갈린다:
# 없으면 배포 전, 있는데 커밋을 못 찾으면 빨강.
#
# 그리고 파일은 **리뷰 diff 에 뜬다.** 누가 기준점을 뒤로 물리면 그 줄이 보인다.
set -euo pipefail

baseline_file=backend/src/main/resources/db/deployed-baseline
watched="backend/src/main/resources/db/migration backend/src/main/resources/db/seed"

if [ ! -f "$baseline_file" ]; then
    echo "배포 전이다 — 잴 기준점이 없다. 첫 배포 때 $baseline_file 을 만든다(Q39)."
    exit 0
fi

# 주석(`#`)과 빈 줄을 걷고 첫 줄을 쓴다. 사람이 왜 그 커밋인지 적어 둘 자리를 남긴다.
#
# `|| true` 가 있어야 한다. 주석뿐인 파일에서 `grep` 이 1 로 끝나고, `set -e` 와 `pipefail` 이
# **그 자리에서 스크립트를 끊어서** 아래 안내가 안 나온다 — 종료 코드만 1 이고 이유가 없다.
# 실측으로 밟았다(`Q51`).
baseline=$(grep -vE '^[[:space:]]*(#|$)' "$baseline_file" | head -1 | tr -d '[:space:]' || true)
if [ -z "$baseline" ]; then
    echo "기준점 파일에 커밋이 없다: $baseline_file" >&2
    exit 1
fi

# **못 찾으면 빨갛다. 통과시키지 않는다.** 이 갈래가 파일을 고른 이유다 —
# 기준점이 있는데 못 읽는 것은 「배포 전」이 아니라 **고장**이고, 둘을 같이 통과시키면
# 게이트가 꺼진 것을 알아챌 방법이 없어진다.
if ! git cat-file -e "${baseline}^{commit}" 2>/dev/null; then
    echo "기준점 커밋을 못 찾는다: $baseline" >&2
    echo "  이력을 얕게 받았으면 그 탓이다 — 이 스크립트를 부르는 CI 잡은 fetch-depth: 0 이어야 한다." >&2
    exit 1
fi

# `M`(고침)·`D`(지움)·`R`(이름 바꿈)만 본다. `A`(새 파일)는 허용이다 — 더하는 것이 규칙이다.
#
# **HEAD 가 아니라 작업 트리와 견준다.** CI 에서는 둘이 같지만 로컬은 다르다 —
# `verify.sh` 는 커밋 **앞**에 도는데(청크를 닫는 기준), HEAD 로 견주면 아직 커밋 안 한 수정을
# 못 보고 초록을 준다. 그러면 커밋한 뒤 CI 에서야 빨개져서 되먹임이 한 바퀴 늦는다.
changed=$(git diff --name-status --diff-filter=MDR "$baseline" -- $watched)
if [ -n "$changed" ]; then
    echo "배포된 마이그레이션을 고쳤다(Q51). 기준점 $baseline 뒤로는 새 V 만 더한다." >&2
    echo "$changed" >&2
    exit 1
fi

echo "마이그레이션 불변 — 기준점 ${baseline} 뒤로 고치거나 지운 것이 없다"
