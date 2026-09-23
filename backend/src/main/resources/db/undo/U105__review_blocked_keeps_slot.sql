-- V105 를 되돌린다. 내려간 후기를 지우면 다시 자리가 빈다 — 제재 우회가 돌아온다(`Q194`).

drop index review_live_per_order_item;

create unique index review_live_per_order_item
    on review (order_item_id)
    where deleted_at is null;
