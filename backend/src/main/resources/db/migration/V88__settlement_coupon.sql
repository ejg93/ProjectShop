-- 쿠폰 부담을 정산에 반영한다(`51`).
--
-- **부담 주체가 정산 줄의 유무를 정한다.**
--
--   * **셀러 부담** — 그 셀러가 할인을 문다. 판매액에서 그만큼 빠지는 줄이 선다.
--   * **몰 부담** — 우리가 문다. **셀러는 정가대로 받으므로 줄이 안 선다.**
--
-- **분할표는 「부담 주체 컬럼 + check」였는데 컬럼을 안 만들었다.** 몰 부담이 줄을 안 만드는
-- 이상 그 칸은 모든 행에서 `seller` 라 아무것도 안 가른다 — 값이 하나뿐인 칸은 검사가 아니라
-- 장식이다. 대신 **종류가 그것을 든다**: `coupon_discount` 가 있다는 것 자체가 셀러 부담이고,
-- 아래 `supplier` 생성 열이 그 사실을 구조로 굳힌다(`D23` 축 2 의 1위).

alter table settlement_item drop constraint settlement_item_kind_check;
alter table settlement_item add constraint settlement_item_kind_check
    check (kind in ('sale', 'shipping_fee', 'commission',
                    'sale_reversal', 'commission_reversal', 'carryover', 'compensation',
                    'coupon_discount'));

-- 공급자를 다시 짓는다. **생성 열은 고칠 수가 없어서 지우고 새로 만든다** —
-- 그 사이에 값이 사라지지 않는 것은 저장 생성 열이 정의에서 다시 계산돼서다.
--
-- 쿠폰 할인은 **셀러가 고객에게 덜 받은 것**이라 공급자가 셀러다(`D2` R17).
-- 우리가 셀러에게 공급한 것이 아니므로 `platform` 이 아니다.
alter table settlement_item drop column supplier;
alter table settlement_item add column supplier text generated always as (
    case
        when kind in ('sale', 'shipping_fee', 'sale_reversal', 'coupon_discount') then 'seller'
        when kind in ('commission', 'commission_reversal')                        then 'platform'
    end
) stored;

comment on column settlement_item.supplier is '부가가치세법이 요구하는 공급자(D2 R17). 종류가 정한다 — 받아서 채우지 않는다';

-- **부호도 종류가 정한다.** 쿠폰 할인은 셀러 몫에서 빠지는 것이라 음수다 —
-- 양수로 담고 뺄셈을 앱이 하면 「합이 곧 지급액」이 성립을 안 한다.
alter table settlement_item drop constraint settlement_item_amount_sign_check;
alter table settlement_item add constraint settlement_item_amount_sign_check
    check ((kind in ('sale', 'shipping_fee', 'commission_reversal') and amount > 0)
           or (kind in ('commission', 'sale_reversal', 'carryover', 'compensation',
                        'coupon_discount')
               and amount < 0));

-- **근거도 종류가 정한다.** 쿠폰 할인은 주문 항목에 붙는다 — 배분이 항목 단위라(`50`)
-- 셀러 주문에 붙이면 정산서에서 어느 상품이 깎였는지를 못 답한다.
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
