-- 환불과 정산 되돌림이 할인을 안다(`Q164`).
--
-- **`50` 이 만든 자리다.** 그 청크가 `payable_amount` 를 「항목합 + 배송비합 − 할인합」으로
-- 줄였는데 **환불 쪽은 할인 전 값을 그대로 쓰고 있었다**(마무리 43차 독립 리뷰).
--
--   * **전액 환불이 막힌다** — 환불합(할인 전) > 결제액(할인 후)이라 상한 검사가 터진다.
--     쿠폰을 쓴 주문은 되돌릴 수가 없다.
--   * **부분 환불은 더 많이 돌려준다** — 항목 상한이 `line_amount` 라 할인분을 안 뺀다.
--     소비자가 8,000 을 내고 10,000 을 받는 자리다.
--
-- **고치는 자리는 상한 둘이고 기준을 「실제로 받은 값」으로 옮긴다.** 환불액 자체를 앱이
-- 어떻게 계산하느냐는 그다음이고, **제약이 먼저 참을 말해야** 앱이 틀렸을 때 걸린다.

-- ---------------------------------------------------------------------------
-- 1. 항목 상한 — 할인을 뺀 값이 천장이다
-- ---------------------------------------------------------------------------

create or replace function assert_refund_item_within_order_item(p_order_item_id bigint)
returns void
language plpgsql as $$
declare
    oi record;
    v_quantity int;
    v_amount bigint;
    v_commission bigint;
    v_refundable bigint;
begin
    select quantity, line_amount, commission_amount, discount_amount
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

    -- **할인을 뺀 값이 천장이다.** 소비자가 그 항목에 실제로 낸 것이 이것이고,
    -- 더 돌려주면 우리가 그 차액을 문다.
    v_refundable := oi.line_amount - oi.discount_amount;

    if v_amount > v_refundable then
        raise exception '환불 대금이 실제 낸 금액을 넘는다 (order_item_id=%, 항목=%, 할인=%, 환불누계=%)',
            p_order_item_id, oi.line_amount, oi.discount_amount, v_amount;
    end if;

    if v_commission > oi.commission_amount then
        raise exception '수수료 환불이 항목 수수료를 넘는다 (order_item_id=%, 항목=%, 환불누계=%)',
            p_order_item_id, oi.commission_amount, v_commission;
    end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- 2. 정산 되돌림에 쿠폰을 더한다
-- ---------------------------------------------------------------------------

-- **정산 뒤 환불하면 셀러가 쿠폰 부담을 영구히 문다**(마무리 43차 독립 리뷰).
-- 1월에 `+판매 −수수료 −쿠폰`, 2월에 `−판매 +수수료` 를 넣으면 **순 `−쿠폰` 이 남는다** —
-- 거래가 없어졌는데 부담만 남는 것이다.
--
-- `sale_reversal`·`commission_reversal` 과 같은 자리에 짝을 세운다.
-- **부호가 양수다** — 셀러에게 돌려주는 것이라 `coupon_discount`(음수)의 반대다.
alter table settlement_item drop constraint settlement_item_kind_check;
alter table settlement_item add constraint settlement_item_kind_check
    check (kind in ('sale', 'shipping_fee', 'commission',
                    'sale_reversal', 'commission_reversal', 'carryover', 'compensation',
                    'coupon_discount', 'coupon_discount_reversal'));

alter table settlement_item drop constraint settlement_item_amount_sign_check;
alter table settlement_item add constraint settlement_item_amount_sign_check
    check ((kind in ('sale', 'shipping_fee', 'commission_reversal',
                     'coupon_discount_reversal') and amount > 0)
           or (kind in ('commission', 'sale_reversal', 'carryover', 'compensation',
                        'coupon_discount')
               and amount < 0));

-- 되돌림 셋이 같은 근거를 든다 — 무엇을 되돌리는지가 환불 항목에 있다.
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
        or (kind in ('sale_reversal', 'commission_reversal', 'coupon_discount_reversal')
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

-- 공급자를 다시 짓는다. 쿠폰 되돌림도 셀러 쪽이다 — 되돌리는 것은 원래 줄의 반대고
-- **공급자가 바뀌는 것이 아니다**.
alter table settlement_item drop column supplier;
alter table settlement_item add column supplier text generated always as (
    case
        when kind in ('sale', 'shipping_fee', 'sale_reversal',
                      'coupon_discount', 'coupon_discount_reversal') then 'seller'
        when kind in ('commission', 'commission_reversal')            then 'platform'
    end
) stored;

comment on column settlement_item.supplier is '부가가치세법이 요구하는 공급자(D2 R17). 종류가 정한다 — 받아서 채우지 않는다';
