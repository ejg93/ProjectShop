# 권한 판정 매트릭스

`PermissionMatrixTest` 가 생성한다. 손으로 고치지 않는다.
갱신은 `gradlew test -Dsnapshot.update=true` 로만 한다.

판정을 받는 사람은 `user=100` 이고 셀러 `1`(알파)에만 속한다.
조직 부여는 전부 알파에서 받은 것으로 놓는다.

## 규칙 하나

| 부여 | 스코프 | 효과 | 내 것 | 남의 것 | 알파(속함) | 베타(안 속함) | 내 것+알파 | 남 것+베타 |
|---|---|---|---|---|---|---|---|---|
| 전역 | own | allow | 허용 | 거부 | 거부 | 거부 | 허용 | 거부 |
| 전역 | seller | allow | 거부 | 거부 | 허용 | 거부 | 허용 | 거부 |
| 전역 | all | allow | 허용 | 허용 | 허용 | 허용 | 허용 | 허용 |
| 전역 | own | deny | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 |
| 전역 | seller | deny | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 |
| 전역 | all | deny | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 |
| 조직 | own | allow | 허용 | 거부 | 거부 | 거부 | 허용 | 거부 |
| 조직 | seller | allow | 거부 | 거부 | 허용 | 거부 | 허용 | 거부 |
| 조직 | all | allow | 허용 | 허용 | 허용 | 허용 | 허용 | 허용 |
| 조직 | own | deny | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 |
| 조직 | seller | deny | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 |
| 조직 | all | deny | 거부 | 거부 | 거부 | 거부 | 거부 | 거부 |

## 규칙 여럿

| 규칙 1 | 규칙 2 | 내 것 | 남의 것 | 알파(속함) | 베타(안 속함) | 내 것+알파 | 남 것+베타 |
|---|---|---|---|---|---|---|---|
| 전역 allow/all | 전역 deny/own | 거부 | 허용 | 허용 | 허용 | 거부 | 허용 |
| 전역 deny/own | 전역 allow/all | 거부 | 허용 | 허용 | 허용 | 거부 | 허용 |
| 전역 allow/seller | 전역 deny/own | 거부 | 거부 | 허용 | 거부 | 거부 | 거부 |
| 조직 allow/seller | 전역 deny/own | 거부 | 거부 | 허용 | 거부 | 거부 | 거부 |
| 전역 allow/own | 조직 allow/seller | 허용 | 거부 | 허용 | 거부 | 허용 | 거부 |
| 조직 allow/seller | 전역 allow/own | 허용 | 거부 | 허용 | 거부 | 허용 | 거부 |
| 전역 allow/all | 조직 deny/seller | 허용 | 허용 | 거부 | 허용 | 거부 | 허용 |
| 전역 allow/own | 전역 allow/all | 허용 | 허용 | 허용 | 허용 | 허용 | 허용 |

## 판정 근거

| 규칙 | 대상 | 근거 |
|---|---|---|
| (없음) | 내 것 | 거부 — matrix:check 에 걸린 규칙이 하나도 없다 |
| (없음) | 남의 것 | 거부 — matrix:check 에 걸린 규칙이 하나도 없다 |
| (없음) | 알파(속함) | 거부 — matrix:check 에 걸린 규칙이 하나도 없다 |
| (없음) | 베타(안 속함) | 거부 — matrix:check 에 걸린 규칙이 하나도 없다 |
| (없음) | 내 것+알파 | 거부 — matrix:check 에 걸린 규칙이 하나도 없다 |
| (없음) | 남 것+베타 | 거부 — matrix:check 에 걸린 규칙이 하나도 없다 |
| role(전역) allow/own | 내 것 | 허용 — role(전역) allow/own |
| role(전역) allow/own | 남의 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/own | 알파(속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/own | 베타(안 속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/own | 내 것+알파 | 허용 — role(전역) allow/own |
| role(전역) allow/own | 남 것+베타 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller | 내 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller | 남의 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller | 알파(속함) | 허용 — role(전역) allow/seller |
| role(전역) allow/seller | 베타(안 속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller | 내 것+알파 | 허용 — role(전역) allow/seller |
| role(전역) allow/seller | 남 것+베타 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(셀러 1) allow/seller | 내 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(셀러 1) allow/seller | 남의 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(셀러 1) allow/seller | 알파(속함) | 허용 — role(셀러 1) allow/seller |
| role(셀러 1) allow/seller | 베타(안 속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(셀러 1) allow/seller | 내 것+알파 | 허용 — role(셀러 1) allow/seller |
| role(셀러 1) allow/seller | 남 것+베타 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/all + role(전역) deny/own | 내 것 | 거부 — role(전역) deny/own |
| role(전역) allow/all + role(전역) deny/own | 남의 것 | 허용 — role(전역) allow/all |
| role(전역) allow/all + role(전역) deny/own | 알파(속함) | 허용 — role(전역) allow/all |
| role(전역) allow/all + role(전역) deny/own | 베타(안 속함) | 허용 — role(전역) allow/all |
| role(전역) allow/all + role(전역) deny/own | 내 것+알파 | 거부 — role(전역) deny/own |
| role(전역) allow/all + role(전역) deny/own | 남 것+베타 | 허용 — role(전역) allow/all |
| role(전역) allow/seller + role(전역) deny/own | 내 것 | 거부 — role(전역) deny/own |
| role(전역) allow/seller + role(전역) deny/own | 남의 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller + role(전역) deny/own | 알파(속함) | 허용 — role(전역) allow/seller |
| role(전역) allow/seller + role(전역) deny/own | 베타(안 속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller + role(전역) deny/own | 내 것+알파 | 거부 — role(전역) deny/own |
| role(전역) allow/seller + role(전역) deny/own | 남 것+베타 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
## 상태 축

동작은 `PAID` 에서만 열린다. 대상은 `내 것`이다.

| 규칙 | 상태 | 근거 |
|---|---|---|
| role(전역) allow/all | (없음) | 거부 — 상태에 걸린 동작인데 대상의 상태가 안 실려 왔다 |
| role(전역) allow/all | PAID | 허용 — role(전역) allow/all |
| role(전역) allow/all | SHIPPED | 거부 — 상태가 SHIPPED 라 이 동작이 닫혀 있다 |
| role(전역) allow/own | (없음) | 거부 — 상태에 걸린 동작인데 대상의 상태가 안 실려 왔다 |
| role(전역) allow/own | PAID | 허용 — role(전역) allow/own |
| role(전역) allow/own | SHIPPED | 거부 — 상태가 SHIPPED 라 이 동작이 닫혀 있다 |
| role(전역) allow/all + role(전역) deny/own | (없음) | 거부 — role(전역) deny/own |
| role(전역) allow/all + role(전역) deny/own | PAID | 거부 — role(전역) deny/own |
| role(전역) allow/all + role(전역) deny/own | SHIPPED | 거부 — role(전역) deny/own |
| role(전역) allow/seller | (없음) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller | PAID | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
| role(전역) allow/seller | SHIPPED | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 |
## 같은 넓이·거부 둘·필드 그룹

| 규칙 | 대상 | 근거 | 필드 그룹 |
|---|---|---|---|
| role(셀러 1) allow/seller[contact] + role(전역) allow/seller[settlement] | 내 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(셀러 1) allow/seller[contact] + role(전역) allow/seller[settlement] | 남의 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(셀러 1) allow/seller[contact] + role(전역) allow/seller[settlement] | 알파(속함) | 허용 — role(셀러 1) allow/seller | contact, settlement |
| role(셀러 1) allow/seller[contact] + role(전역) allow/seller[settlement] | 베타(안 속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(셀러 1) allow/seller[contact] + role(전역) allow/seller[settlement] | 내 것+알파 | 허용 — role(셀러 1) allow/seller | contact, settlement |
| role(셀러 1) allow/seller[contact] + role(전역) allow/seller[settlement] | 남 것+베타 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(전역) allow/seller[settlement] + role(셀러 1) allow/seller[contact] | 내 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(전역) allow/seller[settlement] + role(셀러 1) allow/seller[contact] | 남의 것 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(전역) allow/seller[settlement] + role(셀러 1) allow/seller[contact] | 알파(속함) | 허용 — role(전역) allow/seller | contact, settlement |
| role(전역) allow/seller[settlement] + role(셀러 1) allow/seller[contact] | 베타(안 속함) | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(전역) allow/seller[settlement] + role(셀러 1) allow/seller[contact] | 내 것+알파 | 허용 — role(전역) allow/seller | contact, settlement |
| role(전역) allow/seller[settlement] + role(셀러 1) allow/seller[contact] | 남 것+베타 | 거부 — 허용 규칙은 있으나 대상이 그 범위 밖이다 | (없음) |
| role(전역) deny/all + role(전역) deny/own | 내 것 | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/all + role(전역) deny/own | 남의 것 | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/all + role(전역) deny/own | 알파(속함) | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/all + role(전역) deny/own | 베타(안 속함) | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/all + role(전역) deny/own | 내 것+알파 | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/all + role(전역) deny/own | 남 것+베타 | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/own + role(전역) deny/all | 내 것 | 거부 — role(전역) deny/own | (없음) |
| role(전역) deny/own + role(전역) deny/all | 남의 것 | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/own + role(전역) deny/all | 알파(속함) | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/own + role(전역) deny/all | 베타(안 속함) | 거부 — role(전역) deny/all | (없음) |
| role(전역) deny/own + role(전역) deny/all | 내 것+알파 | 거부 — role(전역) deny/own | (없음) |
| role(전역) deny/own + role(전역) deny/all | 남 것+베타 | 거부 — role(전역) deny/all | (없음) |
| role(전역) allow/own[contact] + role(전역) allow/all | 내 것 | 허용 — role(전역) allow/all | 전부 |
| role(전역) allow/own[contact] + role(전역) allow/all | 남의 것 | 허용 — role(전역) allow/all | 전부 |
| role(전역) allow/own[contact] + role(전역) allow/all | 알파(속함) | 허용 — role(전역) allow/all | 전부 |
| role(전역) allow/own[contact] + role(전역) allow/all | 베타(안 속함) | 허용 — role(전역) allow/all | 전부 |
| role(전역) allow/own[contact] + role(전역) allow/all | 내 것+알파 | 허용 — role(전역) allow/all | 전부 |
| role(전역) allow/own[contact] + role(전역) allow/all | 남 것+베타 | 허용 — role(전역) allow/all | 전부 |
