-- V124 를 되돌린다. 시각 인덱스를 지우고 셀러 인덱스를 옛 두 칸으로 되살린다.

drop index seller_order_created_at_idx;
drop index seller_order_seller_idx;
create index seller_order_seller_idx on seller_order (seller_id, created_at desc);
