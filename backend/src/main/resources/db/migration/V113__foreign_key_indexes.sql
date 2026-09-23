-- 외래키 칸에 인덱스를 단다(`Q206`).
--
-- **PostgreSQL 은 외래키에 인덱스를 안 만든다.** 부모 행을 지우면 자식 표에서 그 키를 가리키는 행을 찾아야 하는데
-- (`restrict` 는 막으려고, `cascade` 는 같이 지우려고) 인덱스가 없으면 자식 표를 통째로 훑는다.
--
-- **부모를 실제로 지우는 자리에 걸린 것만 단다.** 거래기록 파기(`TransactionPurgeService` — 주문·묶음·주문 줄·환불·
-- 배상·문의), 동의 이력 파기(`AccountPurgeService`), 사진 삭제(`ProductImageService`·`CopyrightReportService`),
-- SKU 삭제(`ProductService`)다. 나머지는 부모를 안 지우고 그 칸으로 찾지도 않아서 `ForeignKeyIndexTest` 의 면제 목록에
-- 근거와 함께 있다.
--
-- **부분 인덱스는 외래키 검사가 못 탄다** — 검사는 「그 키를 가리키는 행 전부」를 찾아서 `where status not in (…)` 같은
-- 조건이 붙은 인덱스로는 못 좁힌다(「첫 칸 is not null」만 예외다). 반품 요청과 후기가 그 꼴이라 온전한 인덱스를 따로 단다
-- (마무리 48차 독립 리뷰).

create index cart_item_sku_idx on cart_item (sku_id);
create index compensation_inquiry_idx on compensation (inquiry_id);
create index copyright_report_product_image_idx on copyright_report (product_image_id);
create index notification_order_idx on notification (order_id);
create index notification_refund_idx on notification (refund_id);
create index notification_seller_order_idx on notification (seller_order_id);
create index notification_user_consent_idx on notification (user_consent_id);
create index return_request_seller_order_idx on return_request (seller_order_id);
create index return_request_item_order_item_idx on return_request_item (order_item_id);
create index review_order_item_idx on review (order_item_id);
create index settlement_item_order_item_idx on settlement_item (order_item_id);
create index settlement_item_refund_item_idx on settlement_item (refund_item_id);
create index settlement_item_seller_order_idx on settlement_item (seller_order_id);
create index sku_stock_movement_order_idx on sku_stock_movement (order_id);
