#!/usr/bin/env bash
# 건드린 것이 무엇을 돌릴지 정한다(`/verify`, `2z`). `origin/main` 대비 backend 지문이 다르면 backend 레인,
# frontend 지문이 다르면 frontend 레인, **대조 지문이 다르면 backend 대조**(`Q111`), **도구 지문이 다르면 도구 레인**(`Q216`).
# 지문은 `verify-fingerprint.sh` 가 낸다(코드·빌드 파일 + 대조 입력 + 검증 도구, `2z-1`).
#
# **단계가 둘이다**(`2z-2`). 기본은 **빠른 도장** — backend `gradlew test`(컨테이너 없이 10초),
# frontend `tsc --noEmit`·lint·test. `--full` 은 backend `gradlew build`(느린 레인 930개 포함),
# frontend `next build`·lint·test. 청크를 닫을 땐 빠른 도장이면 되고(Stop hook), **push 앞엔 full 이어야 한다**(push hook).
# DB 를 타는 결함은 그래서 청크 여럿 뒤에 드러날 수 있다 — 청크가 커밋 하나라 `git bisect` 가 답한다.
#
# **빠른 단계라도 origin/main 에 없는 새 `V*` 가 있으면 backend 는 `integrationTest` 까지 돈다**(`Q216`) —
# 열거형·길이·이름 대조가 컨테이너 레인이라, 빠른 레인만 돌면 번들 끝 `--full` 에서 처음 빨개진다(번들 A, 2026-09-23 밤).
# Docker 가 없으면 그 레인은 `gradlew test` 만 돌고 도장이 `fast-nodb` 다 — 번들이 서지 않게. push hook 은 그 도장을 안 받는다.
#
# **같은 지문은 두 번 안 돈다**(`Q216`). 레인의 지금 지문이 도장에 요청 단계 이상으로 있으면 건너뛴다 —
# 문서만 고친 청크에서 frontend 레인(lint + vitest 7분)이 두 번 돌던 것이 그 값이다(2026-09-25 실측).
#
# 통과하면 `.git/verify-stamp` 에 「레인 지문 단계」를 적는다. 표의 손 줄(마이그레이션 기동·e2e·프록시·시드·로그)은
# 여기 없다 — `/verify` 스킬이 든다.
set -uo pipefail
cd "$(dirname "$0")/.."

level=fast
[ "${1:-}" = "--full" ] && level=full

# 이 환경의 JAVA_HOME 은 JDK 11 이라 Gradle 이 안 뜬다(CLAUDE.md). 리눅스 러너는 그대로.
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) export JAVA_HOME="C:/Program Files/Java/jdk-25" ;; esac

git rev-parse -q --verify origin/main >/dev/null || { echo "origin/main 이 없다 — 기준이 없어서 못 잰다"; exit 1; }

# 작업 트리(커밋 안 된 것 포함)의 트리 해시. 임시 인덱스라 진짜 인덱스를 안 건드린다.
tmp=$(mktemp); build_log=$(mktemp); trap 'rm -f "$tmp" "$build_log"' EXIT
GIT_INDEX_FILE="$tmp" git read-tree HEAD
GIT_INDEX_FILE="$tmp" git add -A . 2>/dev/null
tree=$(GIT_INDEX_FILE="$tmp" git write-tree)

fp_work=$(bash scripts/verify-fingerprint.sh "$tree"); fp_main=$(bash scripts/verify-fingerprint.sh origin/main)
changed() { [ "$(echo "$fp_work" | grep "^$1 ")" != "$(echo "$fp_main" | grep "^$1 ")" ]; }
fp_of() { echo "$fp_work" | grep "^$1 " | cut -d' ' -f2; }
path_differs() { [ "$(git rev-parse -q --verify "$tree:$1" 2>/dev/null)" != "$(git rev-parse -q --verify "origin/main:$1" 2>/dev/null)" ]; }

st="$(git rev-parse --git-dir)/verify-stamp"
# 도장이 이 레인의 지금 지문을 요청한 단계 이상으로 찍어 뒀나. 찍어 뒀으면 그 단계를 낸다.
# `fast-nodb` 는 어느 단계에도 안 맞는다 — Docker 가 돌아오면 컨테이너 레인을 마저 돈다.
stamped() {
  local lvl; lvl=$(grep "^$1 $(fp_of "$1") " "$st" 2>/dev/null | cut -d' ' -f3) || return 1
  [ -n "$lvl" ] || return 1
  if [ "$lvl" = full ] || { [ "$level" = fast ] && [ "$lvl" = fast ]; }; then echo "$lvl"; return 0; fi
  return 1
}
docker_up() { docker info >/dev/null 2>&1; }

# Docker 를 먼저 본다(`2z-3`). 안 떠 있으면 느린 레인 930개가 **전부 FAILED** 로 뜨고,
# 진짜 원인(`Could not find a valid Docker environment`)은 XML 리포트를 파야 나온다 —
# 로그만 보면 코드가 깨진 것처럼 보인다. 2026-09-10 에 `2i-2` 를 치다 실제로 그랬다.
# **막는 것은 full 과 `doc/erd` 뿐이다**(`Q216`). 새 `V*` 는 `fast-nodb` 로 물러난다.
need_docker() {
  docker_up && return 0
  echo "Docker 가 안 떴다. $1 — Docker Desktop 을 켜고 다시 돌린다."
  exit 1
}

# **마이그레이션 불변을 먼저 본다**(`Q51`). 1초고, 빨가면 뒤의 빌드가 뭘 하든 못 밀 것이라
# 먼저 알려 주는 편이 싸다. 첫 배포 전에는 스스로 물러난다.
#
# **레인 판정 밖이다.** 지문이 `origin/main` 과 같아도 돌린다 — 이 검사가 견주는 것은
# `origin/main` 이 아니라 **배포 기준점**이라 축이 다르다.
bash scripts/migration-immutable.sh || exit 1

ran=0; ok=1; lv_backend=; lv_frontend=; lv_compare=; lv_tools=

# 대조 레인이 이미 찍혀 있나를 먼저 본다 — backend 레인이 대조를 포함하므로 backend 를 건너뛸지도 이것에 달렸다.
compare_stamped=0
if changed compare && lv_compare=$(stamped compare); then compare_stamped=1; fi

# **`doc/erd` 는 어느 레인이 돌든 빠른 단계에서 느린 시험 하나를 문다**(`Q110` 선택지 ①). 그 폴더는 `SchemaErdTest` 가
# 스스로 만드는 생성물이라 **손으로 고친 것이 곧 잡을 사건**이고, 값(컨테이너 30초)은 그 폴더를 건드린 청크에만 붙는다.
# 빠른 backend 레인(`gradlew test`)은 `@Tag("db")` 를 안 돌므로 **backend 를 같이 고쳤어도** 이 시험은 따로 돈다 —
# 마무리 31차 독립 리뷰가 그 구멍을 짚었다. full 은 `gradlew build` 가 포함한다.
# Docker 가 없으면 **아무것도 돌리기 전에 빨갛다** — 건너뛴 초록은 이 문이 막으려는 바로 그것이고,
# 테스트가 빨간 것과 Docker 가 없는 것이 한 줄에 섞이지 않게 먼저 본다. 대조 도장이 있으면 그때 이미 돌았다.
erd_differs=0; path_differs doc/erd && erd_differs=1
erd_run=0; [ "$level" = fast ] && [ "$erd_differs" -eq 1 ] && [ "$compare_stamped" -eq 0 ] && erd_run=1
[ "$erd_run" -eq 1 ] && need_docker "doc/erd 가 다르다 — SchemaErdTest 가 컨테이너를 띄운다(Q110)"

if changed backend; then
  if lv_backend=$(stamped backend) && { ! changed compare || [ "$compare_stamped" -eq 1 ]; }; then
    echo "== backend: 같은 지문을 $lv_backend 로 찍어 뒀다 → 건너뜀"
  else
    ran=1; lv_backend=$level
    if [ "$level" = full ]; then
      need_docker "느린 레인은 컨테이너를 띄운다"
      echo "== backend 지문이 origin/main 과 다르다 → ./gradlew build (두 레인)"
      (cd backend && ./gradlew build -q) || ok=0
    elif [ -n "$(git diff-tree -r --name-only --diff-filter=A origin/main "$tree" -- backend/src/main/resources/db/migration)" ]; then
      if docker_up; then
        echo "== backend 지문이 다르고 새 V* 가 있다 → ./gradlew test integrationTest (빠른 레인 + 컨테이너 레인)"
        (cd backend && ./gradlew test integrationTest -q) || ok=0
      else
        # 막지 않는다 — 번들이 서는 값이 누수보다 크다(ProjectTicket `B0-1` 사용자 결정, `Q216` 이 옮겼다).
        echo "== 새 V* 가 있는데 Docker 가 안 떴다 → ./gradlew test 만. 컨테이너 레인은 번들 끝 --full 이 돈다(도장 fast-nodb)"
        (cd backend && ./gradlew test -q) || ok=0
        lv_backend=fast-nodb
      fi
    else
      echo "== backend 지문이 origin/main 과 다르다 → ./gradlew test (빠른 레인)"
      (cd backend && ./gradlew test -q) || ok=0
    fi
  fi
fi
if changed frontend; then
  if lv_frontend=$(stamped frontend); then
    echo "== frontend: 같은 지문을 $lv_frontend 로 찍어 뒀다 → 건너뜀"
  else
    ran=1; lv_frontend=$level
    if [ "$level" = full ]; then
      echo "== frontend 지문이 origin/main 과 다르다 → next build · lint · test"
      # 빌드 출력은 초록이면 안 보이고 **빨가면 통째로 보인다.** `>/dev/null` 이던 때는 종료 코드만 남아
      # 무엇이 깨졌는지 다시 돌려야 알았다.
      (cd frontend && { npm run build >"$build_log" 2>&1 || { cat "$build_log"; exit 1; }; } \
        && npm run lint && npm test 2>&1 | tail -4) || ok=0
    else
      echo "== frontend 지문이 origin/main 과 다르다 → tsc --noEmit · lint · test"
      (cd frontend && npx tsc --noEmit && npm run lint && npm test 2>&1 | tail -4) || ok=0
    fi
  fi
fi

# **대조 레인**(`Q111`). backend 테스트가 읽기만 하는 파일 — 화면 소스·컴포즈·`PLAN.md`·`PROGRESS.md`·
# `doc/reference`·`doc/erd` — 만 바뀐 청크는 두 레인이 다 「돌릴 것이 없다」로 빠지고, 대조가 어긋난 것은
# **push 뒤 CI 에서야** 빨개진다. 거짓 초록이라 빨간 것보다 나쁘다 — 잰 적이 없는데 잰 것으로 보인다.
# `Q88` 이 문서 둘에 문을 팠고, 같은 묶음에서 `66` 의 `doc/erd` 가 그 문 밖에 있었다(`Q110`). 이제 셋째 지문이
# 그 파일들을 통째로 든다 — 목록이 `build.gradle.kts` 의 신고와 갈리면 `BuildInputTest` 가 빨갛다.
#
# **backend 레인이 돌았으면 건너뛴다** — 그 레인이 이 대조를 포함한다(`doc/erd` 는 위·아래에서 따로 든다).
# **빠른 단계는 빠른 레인 전부**(10초)고, full 은 `gradlew build`(느린 레인의 대조 — `RequirementEnforcementTest`·
# `IdentifierReferenceTest`·`DataLifecycleCoverageTest` — 까지). 시험 이름을 여기 안 적는다 — 적으면 새 대조가 생길 때 빠진다.
if changed compare; then
  if [ "$compare_stamped" -eq 1 ]; then
    changed backend || echo "== 대조: 같은 지문을 $lv_compare 로 찍어 뒀다 → 건너뜀"
  elif changed backend; then
    lv_compare=$level
  else
    ran=1; lv_compare=$level
    if [ "$level" = full ]; then
      need_docker "대조 full 은 느린 레인의 대조까지 돈다"
      echo "== 대조 지문이 origin/main 과 다르다 → ./gradlew build (두 레인의 대조)"
      (cd backend && ./gradlew build -q) || ok=0
    else
      echo "== 대조 지문이 origin/main 과 다르다 → ./gradlew test (빠른 레인의 대조)"
      (cd backend && ./gradlew test -q) || ok=0
    fi
  fi
fi
if [ "$erd_run" -eq 1 ]; then
  ran=1
  echo "== doc/erd 가 origin/main 과 다르다 → ./gradlew integrationTest --tests '*SchemaErdTest'"
  (cd backend && ./gradlew integrationTest -q --tests '*SchemaErdTest') || ok=0
fi

# **도구 레인**(`Q216`). 검증 도구 자체 — `scripts/`·`.claude/settings.json`·`.claude/skills` — 를 고친 청크는
# 앞 세 레인이 다 「돌릴 것이 없다」로 빠졌다. 셸 문법 · settings.json 파싱 · doc-lint 전체. 빠름·full 이 같다.
if changed tools; then
  if lv_tools=$(stamped tools); then
    echo "== tools: 같은 지문을 $lv_tools 로 찍어 뒀다 → 건너뜀"
  else
    ran=1; lv_tools=$level
    echo "== tools 지문이 origin/main 과 다르다 → bash -n scripts · settings.json 파싱 · doc-lint 전체"
    { for f in scripts/*.sh scripts/hooks/*.sh; do bash -n "$f" || { echo "문법: $f"; false; }; done; } || ok=0
    node -e 'JSON.parse(require("fs").readFileSync(".claude/settings.json","utf8"))' \
      || { echo "settings.json 이 JSON 이 아니다"; ok=0; }
    bash scripts/doc-lint.sh >/dev/null || { bash scripts/doc-lint.sh | head -20; ok=0; }
  fi
fi

[ "$ran" -eq 0 ] && echo "돌릴 것이 없다 — 네 지문이 origin/main 과 같거나 같은 지문을 찍어 뒀다"
[ "$ok" -eq 1 ] || { echo "빨갛다 — 도장을 안 찍는다"; exit 1; }

# 안 돈 레인(origin/main 과 같은 것)은 full 로 적는다 — 돌릴 것이 없어서다. 건너뛴 레인은 도장의 단계를 그대로 둔다.
# 대조 레인은 backend 레인이 돌았을 때도 그 단계로 적는다 — backend 가 대조를 포함해서다.
for d in backend frontend compare tools; do
  h=$(fp_of "$d")
  if changed "$d"; then eval "echo \"$d $h \$lv_$d\""; else echo "$d $h full"; fi
done > "$st"
echo "초록($level). 도장: $st"
