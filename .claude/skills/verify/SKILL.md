---
name: verify
description: 청크를 닫기 전의 검증. `/verify`. `bash scripts/verify.sh` 가 건드린 레인(backend build · frontend build/lint/test)을 돌리고 도장을 찍는다. 표의 손 줄(마이그레이션 기동·e2e·프록시·시드·로그)과 CI 읽는 법은 여기서 고른다.
---

# 검증

**무엇을 건드렸는지가 무엇을 돌릴지 정한다.** 아래 표의 「언제」 칸이 그 답이고,
청크를 닫기 전에 걸리는 줄을 **전부** 돌린다.

**먼저 `bash scripts/verify.sh`**(`2z`). `origin/main` 대비 레인 지문(코드·빌드 파일만 — `scripts/verify-fingerprint.sh`, `2z-1`)이 다르면 그 레인을 돌리고,
초록이면 `.git/verify-stamp` 에 지문을 찍는다. **도장이 두 단계다**(`2z-2`):

| 단계 | 명령 | 무엇이 도나 | 누가 요구하나 |
|---|---|---|---|
| **빠른 도장** | `bash scripts/verify.sh` | backend `gradlew test`(10초) · frontend `tsc --noEmit`·lint·test | **Stop hook** — 청크를 닫을 때 |
| **full 도장** | `bash scripts/verify.sh --full` | backend `gradlew build`(느린 레인 930개) · frontend `next build`·lint·test | **push hook** — 미는 것은 마무리 앞 한 번 |

**full 은 Docker 를 먼저 본다**(`2z-3`). 안 떠 있으면 한 줄로 끝낸다 — 그전에는 930개가 전부 FAILED 로 뜨고
진짜 원인은 XML 리포트를 파야 나왔다.

DB 를 타는 결함은 그래서 청크 여럿 뒤에 드러날 수 있다 — 청크가 커밋 하나라 `git bisect` 가 답한다.
아래 표의 첫 네 줄이 그 두 레인이다. **나머지 줄은 손이고 도장이 안 본다** — 걸리면 돌리고 이력에 적는다.

## 실제로 돌려본 것만 됐다고 한다

**돌리지 못했으면 못 돌렸다고 밝힌다.** 안 돌려보고 "동작한다"·"빌드 통과"라고 쓰지 않는다.

**backend 명령은 앞에 이것을 붙인다** — 이 환경의 `JAVA_HOME` 이 JDK 11 을 가리켜서 Gradle 이 안 뜬다. **안 붙이면 훅이 막는다**(`2x`).

```
JAVA_HOME="C:/Program Files/Java/jdk-25"
```

| 언제 | 명령 | 통과 기준 |
|---|---|---|
| **backend 를 건드렸으면, push 앞에** | `cd backend && ./gradlew build`(= `verify.sh --full`) | `BUILD SUCCESSFUL`. **테스트 두 레인이 여기서 다 돈다** — `test`(빠른 것)와 `integrationTest`(컨테이너) |
| **화면을 건드렸으면, push 앞에** | `cd frontend && npm run build`(= `verify.sh --full`) | `Compiled successfully` + `Finished TypeScript`. 청크를 닫을 땐 `tsc --noEmit` 으로 타입만 본다(`2z-2`) |
| 〃 | `npm run lint` | 출력 없음. **접근성 규칙이 포함돼 있다**(`D20`) |
| 〃 | `npm test` | 실패 0 |
| **로그인·상품·장바구니 화면을 건드렸으면** | 백엔드를 `local` 로 띄운 뒤 `cd frontend && npm run build && npm run e2e` | 통과. CI 는 PR 에서 자동으로 돈다(`Q18-1`). 손으로 걸려면 `gh workflow run e2e.yml --ref <가지>` — **`e2e.yml` 이 `main` 에 있어야 뜬다** |
| **푸시했으면** | 아래 「CI」 | 초록. **빨가면 다음 청크보다 먼저 친다** |
| **고치는 중·청크를 닫을 때** | `./gradlew test`(= `verify.sh`) | 실패 0. **컨테이너를 안 띄우는 레인이라 10초에 답한다**(87개). 대신 **DB 를 타는 930개는 여기서 안 돈다** — 닫기 전에는 `build` 를 돌린다 |
| 스키마·서비스만 볼 때 | `./gradlew integrationTest` | 실패 0. 컨테이너를 띄우는 레인이다(930개, **72~78초**. 재사용을 켠 값이다 — `stack.md`). `HttpFlowTest` 가 관통 흐름을 진짜 HTTP 로 검증한다 |
| **마이그레이션을 더했으면** | **빈 DB 를 만들어** `POSTGRES_DB=shop_check ./gradlew bootRun --args='--spring.profiles.active=local'` 후 `curl localhost:8080/api/health` | `applied_migrations` 가 **마이그레이션 파일 수 + 시드 3**. **테스트만으로는 기동 경로를 안 지난다**. 쓰던 DB 에 그냥 올리면 시드가 `V900+` 라 Flyway 가 순서를 어긴 것으로 보고 멈춘다(`stack.md`) |
| 컨테이너 설정을 건드렸으면 | `docker compose config --quiet` 후 `docker compose up -d` | 종료 코드 0, `shop-db`·`shop-redis` 가 `healthy` |
| 프록시·라우팅을 건드렸으면 | 백엔드를 띄운 뒤 `npm run dev` 하고 `curl localhost:3000/api/health` | 8080 을 직접 부른 것과 **같은 JSON**. 다르면 rewrite 가 안 걸린 것이다 |
| 시드·데모 데이터를 건드렸으면 | `./gradlew bootRun --args='--spring.profiles.active=local'` | `db/seed/` 가 같이 적용된다. 계정 6·셀러 2, 비밀번호는 전부 `demo-password-1234`. **`local` 없이 뜨면 시드가 안 들어간다** |
| 로그·추적을 건드렸으면 | 기동 후 `curl localhost:8080/api/health` 하고 `backend/logs/shop.log` | 요청마다 `[추적ID,스팬ID] c.p.s.o.RequestLogFilter : GET /api/health 200 5ms` 한 줄. **대괄호 값이 요청마다 달라야 한다** — 같으면 추적이 안 붙은 것이다(`D16`) |
| **`CLAUDE.md`·`doc/reference/*` 를 고쳤으면** | **안 돌려도 된다** — `.claude/settings.json` 의 훅이 편집 직후에 돌린다(`2j`). 손으로 돌리려면 `bash scripts/doc-lint.sh` | 통과하면 아무 말이 없고, 깨지면 **편집한 그 자리에서 막힌다.** 잡는 것이 셋이다 — 제목 파편(`batch-catalog.md`·`state-machines.md`·`PLAN.md` 가 실제로 이렇게 부서졌었다), 완전 중복 문장(`frontend-rules.md` 사례), **존댓말**(`2k-1`), **기준 문서 제목의 날짜**(`2c-2`. `external-references.md` 는 날짜가 내용이라 뺀다) |
| **요건표(`D2`)에 R 을 더했으면** | `bash scripts/req-coverage.sh` | 리포트라 안 빨개진다(`2v`). 새 R 이 「언급하지 않는 요건」에 뜨면 테스트를 세우거나 요건표 「강제 지점」 칸이 왜 없는지를 답한다 |

프론트 명령은 전부 `frontend/` 안에서 돌린다.

**새 구역이 생기면 그 명령을 이 표에 더한다.** 지금 도는 것은 위가 전부다.

## CI

**커밋마다 CI 가 같은 명령을 돌린다**(`.github/workflows/ci.yml`, 청크 `2c`) —
backend 는 `./gradlew build`, frontend 는 `npm ci` 뒤 `build`·`lint`·`test` 다.
**푸시해야 돈다. 가지를 가리지 않는다**(`2g-4`) — 작업 가지에 밀어도 그대로 돈다.
**그래서 PR 이 없어도 청크마다 리눅스 검증을 받는다.**

**`main` 은 가지 보호가 걸려 있다**(`2g-3`). PR 없이는 못 밀고, `backend`·`frontend`·
`secrets`·`docs` 가 초록이 아니면 머지가 안 된다. **`review` 는 필수가 아니다** —
워크플로 파일을 고치는 PR 에서 그 잡이 `skipping` 이라 필수로 걸면 그런 PR 이 영영 안 닫힌다.
**관리자는 아직 뚫을 수 있다**(`enforce_admins=false`) — CI 설정 자체가 깨졌을 때
저장소가 잠기지 않게 남겨 둔 구멍이다.

**그래도 위 표를 먼저 돌린다.** CI 는 푸시한 뒤에야 답하고, 그때는 이미 커밋이 남아 있다.

## 리뷰는 마무리 PR 에서 한 번 돈다

**`claude-review.yml` 이 `pull_request` 에 걸려 있다**(청크 `2g`). **PR 이 마무리 때만 열리므로
리뷰도 그때 한 번**이고, 그 묶음의 청크 전부를 한꺼번에 본다(`2g-4`).

보는 것은 넷이고
전부 **빌드·테스트가 초록이어도 안 걸리는 것**이다 — 말한 축과 편 축이 같나,
강제 지점을 더 아래로 내릴 수 있었나, 이 PR 안의 결정끼리 부딪치나,
문서가 부르는 것이 실물과 맞나.
**답은 청크별 예·아니오·모름 표다**(`2w`) — 「없다」 한 줄이 「안 봤다」와 안 갈리던 것을 가른다.

**리뷰는 게이트가 아니다.** 조언이라 아무것도 못 막는다 — 막는 것은 위 CI 고,
리뷰의 산출물은 **반복해서 잡히는 것**이다. 그것을 테스트·`doc-lint.sh` 로 내리는 기준은
`D25`(품질 게이트 기준)가 정한다. **그러니 리뷰 지적을 그 자리에서 다 고칠 필요는 없다** —
「드러난 것의 처분」 셋(정보·흡수·신규) 중 하나로 처분하면 된다.

**게이트가 아닌 것과 안 기다려도 되는 것은 다르다.** 리뷰가 끝나기 전에 머지하면
그 지적을 **그 PR 에서 처분할 자리가 없어진다**(`2g-1`). 6~7분으로 CI 중 제일 길다.

**그 6~7분을 청크마다 기다리던 것이 `2g-4` 전의 병목이었다** — 하루에 PR 열하나를 열었고
**리뷰 지적은 0개**였다. 마무리당 한 번으로 줄이니 대기가 주 1~2회가 된다.
**리뷰가 왜 코멘트를 안 남기는지는 아직 안 잡았다**(`2g-5`).

## CI 는 위 표가 못 잡는 것을 잡는다

**다른 환경이라서다.** 리눅스 러너, 깨끗한 체크아웃, `npm ci`.
로컬은 Windows 에 이미 받아 둔 의존성이라 **여기서만 드러나는 종류가 있다.**

`2026-08-23` 에 CI 가 처음 돌면서 그것을 바로 잡았다 — `./gradlew` 에 **실행 비트가 없어서**
`Permission denied`(exit 126)로 죽었다. **Windows 에는 그 개념이 없어서 몇 번을 돌려도 안 나온다.**

| 무엇 | 명령 |
|---|---|
| 최근 결과 | `gh run list --limit 3` |
| 끝날 때까지 기다린다 | `gh run watch <id> --exit-status` |
| **실패한 부분만 읽는다** | `gh run view <id> --log-failed` |
| 다시 돌린다 | `gh run rerun <id>` |

**`gh` 가 `PATH` 에 없으면** 전체 경로로 부른다 — `"/c/Program Files/GitHub CLI/gh.exe"`.
설치 후에 뜬 셸이라야 `PATH` 가 잡힌다.

**빨간 CI 는 「지금 깨져 있는 것」이라 우선순위 1번이다**(「무엇부터」).

