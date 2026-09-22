-- V89 를 되돌린다. **환불 상한이 다시 할인 전 값이 되고** 쿠폰 되돌림 줄이 사라진다 —
-- 되돌린 채로 두면 쿠폰 쓴 주문이 다시 전액 환불에 막힌다. `V87`·`V88` 을 같이 되돌릴 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

-- 지급액을 같이 고친다. `U88` 과 같은 이유다 — 줄만 지우면 지연 제약이 터진다.
update settlement s
   set payout_amount = s.payout_amount - coalesce(
           (select sum(i.amount) from settlement_item i
             where i.settlement_id = s.settlement_id
               and i.kind = 'coupon_discount_reversal'), 0)
 where exists (select 1 from settlement_item i
                where i.settlement_id = s.settlement_id
                  and i.kind = 'coupon_discount_reversal');

delete from settlement_item where kind = 'coupon_discount_reversal';

alter table settlement_item drop column supplier;
alter table settlement_item add column supplier text generated always as (
    case
        when kind in ('sale', 'shipping_fee', 'sale_reversal', 'coupon_discount') then 'seller'
        when kind in ('commission', 'commission_reversal')                        then 'platform'
    end
) stored;

alter table settlement_item drop constraint settlement_item_source_check;
alter table settlement_item add constraint settlement_item_source_check
    check (
        (kind in ('sale', 'commission', 'coupon_discount')
             and order_item_id is not null and seller_order_id is null
             and refund_item_id is null and carried_from_settlement_id is null
             and compensation_id is null)
        or (kind = 'shipping_fee'
             and seller_order_id is not null and order_item_id is null
             and refund_item_id is null and carried_from_settlement_id is null
             and compensation_id is null)
        or (kind in ('sale_reversal', 'commission_reversal')
             and refund_item_id is not null and order_item_id is null
             and seller_order_id is null and carried_from_settlement_id is null
             and compensation_id is null)
        or (kind = 'carryover'
             and carried_from_settlement_id is not null and order_item_id is null
             and seller_order_id is null and refund_item_id is null
             and compensation_id is null)
        or (kind = 'compensation'
             and compensation_id is not null and order_item_id is null
             and seller_order_id is null and refund_item_id is null
             and carried_from_settlement_id is null)
    );

alter table settlement_item drop constraint settlement_item_amount_sign_check;
alter table settlement_item add constraint settlement_item_amount_sign_check
    check ((kind in ('sale', 'shipping_fee', 'commission_reversal') and amount > 0)
           or (kind in ('commission', 'sale_reversal', 'carryover', 'compensation',
                        'coupon_discount')
               and amount < 0));

alter table settlement_item drop constraint settlement_item_kind_check;
alter table settlement_item add constraint settlement_item_kind_check
    check (kind in ('sale', 'shipping_fee', 'commission',
                    'sale_reversal', 'commission_reversal', 'carryover', 'compensation',
                    'coupon_discount'));

-- 항목 상한을 V23 의 판으로 되돌린다. 할인을 안 뺀다.
create or replace function assert_refund_item_within_order_item(p_order_item_id bigint)
returns void
language plpgsql as $$
declare
    oi record;
    v_quantity int;
    v_amount bigint;
    v_commission bigint;
begin
    select quantity, line_amount, commission_amount
      into oi
      from order_item
     where order_item_id = p_order_item_id;

    if not found then
        return;
    end if;

    select coalesce(sum(ri.quantity), 0), coalesce(sum(ri.amount), 0),
           coalesce(sum(ri.commission_refund), 0)
      into v_quantity, v_amount, v_commission
      from refund_item ri
      join refund r on r.refund_id = ri.refund_id
     where ri.order_item_id = p_order_item_id
       and r.status <> 'rejected';

    if v_quantity > oi.quantity then
        raise exception '환불 수량이 주문 수량을 넘는다 (order_item_id=%, 주문=%, 환불누계=%)',
            p_order_item_id, oi.quantity, v_quantity;
    end if;

    if v_amount > oi.line_amount then
        raise exception '환불 대금이 항목 금액을 넘는다 (order_item_id=%, 항목=%, 환불누계=%)',
            p_order_item_id, oi.line_amount, v_amount;
    end if;

    if v_commission > oi.commission_amount then
        raise exception '수수료 환불이 항목 수수료를 넘는다 (order_item_id=%, 항목=%, 환불누계=%)',
            p_order_item_id, oi.commission_amount, v_commission;
    end if;
end;
$$;
