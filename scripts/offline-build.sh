#!/usr/bin/env bash
# 프론트 `next build` 가 네트워크 없이 되는지 본다(`Q214`, `D25`). `docker.yml` 이 부르고 손으로도 같은 명령이다.
#
# **왜 있나**: 글꼴을 `next/font/google` 로 받던 때는 빌드가 구글을 불러, 못 받으면 빌드 전체가 죽었다(PR #77·#79).
# 배포도 같은 빌드라 그날은 배포가 선다. 빌드 때 바깥을 부르는 것이 다시 들어오면 여기서 빨갛다.
#
# 절차: `frontend/` 를 임시 사본으로(node_modules·.next 없이) → `node:22` 컨테이너에서 `npm ci`(네트워크 있음) →
# 같은 이미지의 `--network none` 컨테이너에서 `npm run build`. 두 단계가 한 이미지라 받은 네이티브 바이너리의 libc 가 맞는다.
# **사본에서 도는 이유**: 윈도에서 본 트리를 물리면 컨테이너의 리눅스 `node_modules` 가 윈도 것을 덮는다.
# `scripts/gate-probe.sh offline-build` 가 부순 채로 이것을 부른다 — 사본이라 트리를 안 건드린다.
set -u
cd "$(dirname "$0")/.."
command -v docker >/dev/null || { echo "docker 가 없다" >&2; exit 2; }
docker info >/dev/null 2>&1 || { [ -f scripts/docker-up.sh ] && bash scripts/docker-up.sh >&2; } || { echo "Docker 데몬이 없다" >&2; exit 2; }

T=$(mktemp -d)
tar --exclude=frontend/node_modules --exclude=frontend/.next --exclude=frontend/test-results \
    --exclude=frontend/playwright-report -cf - frontend | (cd "$T" && tar xf -)
mount=$T/frontend
command -v cygpath >/dev/null && mount=$(cygpath -w "$mount")   # Docker Desktop 은 윈도 경로를 받는다
export MSYS_NO_PATHCONV=1                                          # `-w /src` 를 Git Bash 가 바꾸지 않게

echo "== npm ci(네트워크 있음)"
docker run --rm -v "$mount":/src -w /src node:22 npm ci --no-audit --no-fund > "$T/ci.log" 2>&1 \
  || { tail -20 "$T/ci.log" >&2; echo "빨강 — npm ci 가 실패했다(이 단계는 네트워크를 쓴다)" >&2; exit 1; }

echo "== next build(--network none)"
if docker run --rm --network none -v "$mount":/src -w /src \
     -e NEXT_TELEMETRY_DISABLED=1 -e BACKEND_ORIGIN=http://backend.ci.internal:8080 node:22 npm run build > "$T/build.log" 2>&1; then
  tail -3 "$T/build.log"
  echo "초록 — next build 가 네트워크 없이 됐다"
  rc=0
else
  grep -iE "error|fail|requesting|resolve" "$T/build.log" | head -12
  echo "빨강 — next build 가 네트워크 없이 안 된다(빌드 때 바깥을 부르는 것이 있다)" >&2
  rc=1
fi
# 컨테이너가 root 로 쓴 파일은 러너 사용자가 못 지울 수 있다 — 임시 디렉터리라 남아도 해가 없다
rm -rf "$T" 2>/dev/null || true
exit $rc
