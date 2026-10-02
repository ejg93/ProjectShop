-- 정산서 줄 목록의 정렬 인덱스(`Q244`). 줄 목록은 `where settlement_id = ? order by kind, settlement_item_id` 에 `limit` 이다.
--
-- 옛 `(settlement_id)` 는 그 정산의 줄을 다 읽어 다섯 표와 잇고 나서야 앞 50 줄을 골랐다 — 로컬 10만 건에서
-- 줄 3.5만 정산의 첫 쪽이 210ms. 정렬 키까지 넣으면 앞 50 줄만 읽고 그 줄만 잇는다(1.3ms, 2026-10-03 롤백 실험).
-- 같은 이름으로 다시 만든다 — 외래 키 인덱스 대조(`ForeignKeyIndexTest`)가 첫 칸 `settlement_id` 를 그대로 받는다.
--
-- **깊은 쪽은 여전히 비싸다**(마지막 쪽 240ms) — 쪽 번호 방식이라 앞의 줄을 다 지나야 한다. 키셋은 목록 규약(`D5`)이
-- 바뀌는 일이라 안 했다.
drop index settlement_item_settlement_idx;
create index settlement_item_settlement_idx on settlement_item (settlement_id, kind, settlement_item_id);
