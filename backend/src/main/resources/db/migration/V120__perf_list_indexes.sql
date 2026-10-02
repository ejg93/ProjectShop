-- 목록 정렬 인덱스 둘(`42`). 로컬 10만 건(`70-0`)의 실행 계획에서 순차 스캔 + 정렬이 나온 목록에만 건다.
--
-- **둘 다 「정렬 키 그대로」다.** 목록은 `order by <시각>, <id>` 에 `limit` 를 붙이는데(`ListQuery`),
-- 그 순서의 인덱스가 없으면 표 전체를 읽고 정렬한 뒤 20개를 남긴다. 인덱스 순서가 같으면 앞 20개만 읽는다.
-- 잰 값과 남긴 것(셀러 목록·상품 목록 등 질의 모양이 막는 자리)은 `doc/notes/perf-local-100k.md` 에 있다.

-- 관리자 주문 목록(`OrderQuery.findAll`). 주문 10만에서 15ms → 0.1ms. 상태·기간 거르기도 이 순서로 걸러 읽는다.
create index shop_order_created_at_idx on shop_order (created_at desc, order_id desc);

-- 환불 대기열(`RefundQuery`). 기한이 가까운 것부터다. 환불 4,000 에서 16ms → 0.2ms —
-- 인덱스가 없으면 묶음 12만을 해시로 이은 뒤 정렬한다.
create index refund_due_at_idx on refund (due_at, refund_id desc);
