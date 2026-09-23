-- V108 을 되돌린다. 칸 둘과 그 검사를 지운다 — 원장에서 다시 셀 수 있는 값이라 잃는 것이 없다.

alter table seller_daily_sales drop constraint seller_daily_sales_mall_discount_check;
alter table seller_daily_sales
    drop column mall_discount_amount,
    drop column refunded_mall_discount_amount;
