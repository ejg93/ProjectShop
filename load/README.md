# 부하 시험

목록 입구 여섯의 p95 를 k6 로 재고 `baseline.json` 과 견주는 자리다(`70`, `D21` — `doc/reference/performance-goals.md`).

## 돌리는 법

```bash
bash scripts/load-test.sh                    # 견준다. 기준선의 1.5배를 넘는 입구가 있으면 1
bash scripts/load-test.sh --update-baseline  # 기준선을 다시 쓴다 — performance-goals.md 표를 같은 커밋에서 고친다
```

**앞서 둘이 있어야 한다.** 컴포즈가 떠 있고(`docker compose up -d`), 개발 DB `shop` 이 최신 판이다
(`bootRun` 을 한 번 돌리고 내린다 — 틀 복사는 연결이 있으면 실패한다).

| 단계 | 무엇 |
|---|---|
| 붓기 | `scripts/load-data.sh --clean` 뒤 다시 붓는다 — **매번 갓 부은 `shop_load` 에서 잰다**. 배치를 돌린 DB 는 행 수가 달라 견줄 수 없다 |
| 대상 | 컴포즈 `load` 프로필의 `backend`(이미지 빌드, 호스트 8081, 요청 제한 끔) |
| 예열 | 15초 한 번 — 결과를 안 쓴다 |
| 측정 | k6 `lists.js` 세 번, VU 10·1분. 응답이 하나라도 200 이 아니면 그 회차가 실패한다 |
| 견주기 | `compare.mjs` — 입구별 세 회차 중앙값을 기준선과 견준다 |

## 파일

| 파일 | 무엇 |
|---|---|
| `data/load-100k.sql` | 10만 건 생성기(`70-0`) |
| `lists.js` | k6 시나리오 — 입구 여섯과 세 역할의 로그인 |
| `compare.mjs` | 중앙값과 기준선 견주기, `--update-baseline` |
| `baseline.json` | 기준선의 원본. `performance-goals.md` 표가 사본이고 `PerformanceBaselineConsistencyTest` 가 둘을 견준다 |
| `out/` | 회차 결과. 저장소에 안 넣는다 |

## 기준선을 잰 기계

Intel Core Ultra 7 155H · 32GB · Windows 11 · Docker Desktop(22 CPU, 15.4GB). **다른 기계에서는 기준선을 다시 잰다** —
같은 기계의 앞 값과 견주는 것이 이 시험의 전제다.
