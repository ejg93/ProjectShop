#!/usr/bin/env bash
# Docker 데몬이 안 떠 있으면 Docker Desktop 을 켜고 뜰 때까지 기다린다(`Q232`). Windows(Git Bash) 전용이다 —
# 리눅스 러너는 데몬이 늘 떠 있어 부를 일이 없다. `verify.sh` 의 `docker_up()` 이 `docker info` 실패 시 한 번 부른다.
#
# **왜 있나**: 밤에 무인으로 번들을 돌 때 Docker 가 내려가 있으면 `--full`·`doc/erd`·새 `V*` 자리에서
# 「Docker Desktop 을 켜고 다시 돌린다」로 선다. 2026-09-25 마무리 49차에서 사람이 켰다.
#
# 켜는 순서: `docker desktop start`(CLI 플러그인, v0.4.3 에서 확인) → 없으면 PowerShell `Start-Process` 로 exe.
# exe 가 기본 경로가 아니면 `DOCKER_DESKTOP_EXE` 로 준다. **3분이 지나도 안 뜨면 1** — 무한 대기는 안 한다.
set -u

if docker info >/dev/null 2>&1; then
  echo "docker 이미 떠 있다"
  exit 0
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ;;
  *) echo "Docker 데몬이 없다 — 이 스크립트는 Windows(Docker Desktop)만 켠다" >&2; exit 1 ;;
esac

# `--detach` — 기본은 켜질 때까지 시간 제한 없이 기다려서 아래 3분 상한이 안 걸린다(마무리 51차 독립 리뷰).
if docker desktop start --detach >/dev/null 2>&1; then
  echo "docker desktop start 로 켰다 — 데몬을 기다린다"
else
  exe=${DOCKER_DESKTOP_EXE:-C:/Program Files/Docker/Docker/Docker Desktop.exe}
  # `Start-Process` 의 경로 인자는 Git Bash 가 바꿔 넘기지 않게 따옴표 안에 둔다(`Q231` 의 경로 변환).
  MSYS_NO_PATHCONV=1 powershell -NoProfile -Command "Start-Process -FilePath '$exe'" \
    || { echo "Docker Desktop 을 못 켰다: $exe" >&2; exit 1; }
  echo "Docker Desktop 을 켰다($exe) — 데몬을 기다린다"
fi

for i in $(seq 1 36); do
  if docker info >/dev/null 2>&1; then
    echo "데몬이 떴다(약 $((i * 5))초)"
    exit 0
  fi
  sleep 5
done
echo "3분이 지나도 데몬이 안 떴다 — Docker Desktop 창을 본다" >&2
exit 1
