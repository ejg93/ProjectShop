-- V104 를 되돌린다. **송장 번호가 사라진다** — 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
--
-- 뷰가 두 칸을 물고 있어서 뷰를 먼저 내리고, 칸을 지운 뒤 `V37` 의 모양으로 다시 세운다.

drop view seller_order_visible;

alter table seller_order drop constraint seller_order_tracking_after_ship_check;
alter table seller_order drop constraint seller_order_tracking_pair_check;
alter table seller_order drop constraint seller_order_tracking_no_check;
alter table seller_order drop constraint seller_order_carrier_code_check;
alter table seller_order drop column tracking_no;
alter table seller_order drop column carrier_code;

create view seller_order_visible as
select so.seller_order_id,
       so.seller_order_number,
       so.order_id,
       so.seller_id,
       so.status,
       so.shipping_fee,
       so.supply_lead_days,
       so.agreed_lead_days,
       so.ship_due_at,
       so.shipped_at,
       (so.ship_due_at is not null
        and coalesce(so.shipped_at, now()) > so.ship_due_at) as is_ship_overdue,
       so.delivered_at,
       so.withdrawal_expire_at,
       so.auto_confirm_at,
       so.return_reason,
       so.closed_at,
       so.created_at,
       so.updated_at
  from seller_order so
  join shop_order o on o.order_id = so.order_id
 where o.status = 'paid';
