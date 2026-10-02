-- 셀러 주문 목록의 정렬 인덱스(`Q243a`). 목록은 `order by created_at desc, seller_order_id desc` 에 `limit` 이다.
--
-- **셀러 인덱스에 동점 키를 넣는다.** 옛 `(seller_id, created_at desc)` 는 같은 시각의 묶음이 많으면
-- `Incremental Sort` 가 그 묶음들을 다 읽어 정렬했다 — 로컬 10만 건에서 셀러 한 곳 115ms, 동점 키까지 넣으면 3.0ms
-- (`doc/notes/perf-local-100k.md`, 2026-10-03 롤백 실험). 조회기도 셀러가 하나면 `= any(배열)` 대신 `=` 로 건다.
drop index seller_order_seller_idx;
create index seller_order_seller_idx on seller_order (seller_id, created_at desc, seller_order_id desc);

-- 전부 보는 사람(관리자)과 여러 셀러에 속한 사람의 목록. 셀러 조건이 없거나 배열이라 위 인덱스를 못 쓰고
-- 묶음 12만을 순차로 읽어 정렬했다 — 68ms → 4.5ms, 여럿 갈래 42ms → 2.6ms.
create index seller_order_created_at_idx on seller_order (created_at desc, seller_order_id desc);
