-- V113 을 되돌린다. 인덱스만 걷는다 — 데이터는 안 건드린다.
drop index cart_item_sku_idx;
drop index compensation_inquiry_idx;
drop index copyright_report_product_image_idx;
drop index notification_order_idx;
drop index notification_refund_idx;
drop index notification_seller_order_idx;
drop index notification_user_consent_idx;
drop index return_request_seller_order_idx;
drop index return_request_item_order_item_idx;
drop index review_order_item_idx;
drop index settlement_item_order_item_idx;
drop index settlement_item_refund_item_idx;
drop index settlement_item_seller_order_idx;
drop index sku_stock_movement_order_idx;
