-- V88 을 되돌린다. **쿠폰 부담 줄이 남아 있으면 되돌아가지 않는다** —
-- `kind` 검사가 그 값을 모르게 되므로 먼저 그 줄을 지워야 하고, 지우면
-- 그 달 정산액이 달라진다. 마감 전에만 되돌린다.

-- **줄을 지우면 지급액도 같이 고쳐야 한다.** `settlement_item_amounts_check` 는
-- delete 에도 걸린 지연 제약이라, 안 고치면 커밋 때 「지급액이 항목 합과 다르다」로 터진다 —
-- **되돌릴 수 있다고 적어 두고 실제로는 못 되돌리는** 자리였다(마무리 43차 독립 리뷰).
update settlement s
   set payout_amount = s.payout_amount + coalesce(
           (select sum(-i.amount) from settlement_item i
             where i.settlement_id = s.settlement_id and i.kind = 'coupon_discount'), 0)
 where exists (select 1 from settlement_item i
                where i.settlement_id = s.settlement_id and i.kind = 'coupon_discount');

delete from settlement_item where kind = 'coupon_discount';

alter table settlement_item drop column supplier;
alter table settlement_item add column supplier text generated always as (
    case
        when kind in ('sale', 'shipping_fee', 'sale_reversal') then 'seller'
        when kind in ('commission', 'commission_reversal')     then 'platform'
    end
) stored;

alter table settlement_item drop constraint settlement_item_source_check;
alter table settlement_item add constraint settlement_item_source_check
    check (
        (kind in ('sale', 'commission')
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
           or (kind in ('commission', 'sale_reversal', 'carryover', 'compensation')
               and amount < 0));

alter table settlement_item drop constraint settlement_item_kind_check;
alter table settlement_item add constraint settlement_item_kind_check
    check (kind in ('sale', 'shipping_fee', 'commission',
                    'sale_reversal', 'commission_reversal', 'carryover', 'compensation'));
