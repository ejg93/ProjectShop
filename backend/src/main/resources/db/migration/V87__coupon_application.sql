-- 쿠폰 적용 결과를 주문에 박제한다(`50`).
--
-- **화면이 다시 계산하지 않는다.** 할인액을 그때그때 계산하면 정의가 바뀌는 날
-- 과거 주문의 금액이 같이 움직인다 — `V26` 이 공급 기한을 박제한 것과 같은 이유고,
-- 수수료를 요율이 아니라 **금액으로** 박아 둔 것과도 같다(`D8`).
--
-- **배분해서 항목에 박는다.** 주문 하나에 한 장이지만 할인액은 항목마다 나뉜다 —
-- 정산이 셀러별로 갈라야 하고(`51`), 부분 취소·환불이 항목 단위라서다.
-- 주문 머리에만 두면 그 둘이 매번 배분을 다시 해야 하고, **하는 곳마다 절사가 갈린다.**

-- ---------------------------------------------------------------------------
-- 1. 박제할 칸
-- ---------------------------------------------------------------------------

alter table order_item add column discount_amount bigint not null default 0;
alter table shop_order add column discount_total  bigint not null default 0;

-- 항목값보다 큰 할인은 **음수 결제액**을 만든다. 거스름을 줄 방법이 없다.
alter table order_item add constraint order_item_discount_amount_check
    check (discount_amount >= 0 and discount_amount <= line_amount);

alter table shop_order add constraint shop_order_discount_total_check
    check (discount_total >= 0);

-- **결제액 등식이 한 행 안에서 끝나는 자리라 `check` 다**(`V16`). 할인이 들어오면서
-- 그 등식이 바뀐다 — 안 고치면 쿠폰을 쓴 주문이 아예 안 선다.
--
-- 아래 트리거가 같은 등식을 한 번 더 재는 것은 **항목 합까지 보기 위해서**다.
-- 이 `check` 는 머리 안의 값 넷만 보고, 그 넷이 항목과 맞는지는 다른 표를 봐야 한다.
alter table shop_order drop constraint shop_order_payable_amount_check;
alter table shop_order add constraint shop_order_payable_amount_check
    check (payable_amount = total_amount + shipping_fee_total - discount_total);

comment on column order_item.discount_amount is '배분된 할인액. 주문 시점에 박제한다 — 쿠폰 정의가 바뀌어도 안 바뀐다';
comment on column shop_order.discount_total is '할인 합계. 항목별 배분액의 합과 같아야 한다 — assert_order_amounts 가 잰다';

-- 쿠폰 발급과 주문을 잇는 자리는 `coupon_issue.used_order_id` 다(`V86`).
-- 여기에 쿠폰 번호를 또 두지 않는다 — **같은 사실이 두 곳에 있으면 한쪽이 낡는다.**

-- ---------------------------------------------------------------------------
-- 2. 등식에 할인을 넣는다
-- ---------------------------------------------------------------------------

-- `V16` 의 판에 둘을 더한다.
--
--   ① 할인 합계 = 항목별 배분액의 합
--   ② 결제액 = 항목합 + 배송비합 − 할인합
--
-- **②가 그전에는 없었다.** `payable_amount` 를 아무도 안 재고 있어서, 할인이 들어오는
-- 지금이 그 등식을 세울 자리다 — 안 세우면 배분이 틀려도 **결제액만 조용히 어긋난다.**
create or replace function assert_order_amounts(p_order_id bigint) returns void
language plpgsql as $$
declare
    o record;
    v_item_sum       bigint;
    v_commission_sum bigint;
    v_discount_sum   bigint;
    v_shipping_sum   bigint;
    v_seller_orders  bigint;
begin
    select * into o from shop_order where order_id = p_order_id;
    if not found then
        return;
    end if;

    select coalesce(sum(oi.line_amount), 0), coalesce(sum(oi.commission_amount), 0),
           coalesce(sum(oi.discount_amount), 0)
      into v_item_sum, v_commission_sum, v_discount_sum
      from order_item oi
      join seller_order so on so.seller_order_id = oi.seller_order_id
     where so.order_id = p_order_id;

    select coalesce(sum(shipping_fee), 0), count(*)
      into v_shipping_sum, v_seller_orders
      from seller_order
     where order_id = p_order_id;

    if v_seller_orders = 0 then
        raise exception '셀러 주문이 없는 주문이다 (order_id=%)', p_order_id;
    end if;

    if exists (select 1
                 from seller_order so
                where so.order_id = p_order_id
                  and not exists (select 1
                                    from order_item oi
                                   where oi.seller_order_id = so.seller_order_id)) then
        raise exception '항목이 없는 셀러 주문이 있다 (order_id=%)', p_order_id;
    end if;

    if o.total_amount <> v_item_sum then
        raise exception '주문 금액이 항목 합과 다르다 (order_id=%, 저장=%, 항목합=%)',
            p_order_id, o.total_amount, v_item_sum;
    end if;

    if o.commission_total <> v_commission_sum then
        raise exception '수수료 합이 항목별 수수료 합과 다르다 (order_id=%, 저장=%, 항목합=%)',
            p_order_id, o.commission_total, v_commission_sum;
    end if;

    if o.shipping_fee_total <> v_shipping_sum then
        raise exception '배송비 합이 셀러 주문의 배송비 합과 다르다 (order_id=%, 저장=%, 합=%)',
            p_order_id, o.shipping_fee_total, v_shipping_sum;
    end if;

    if o.discount_total <> v_discount_sum then
        raise exception '할인 합이 항목별 배분액의 합과 다르다 (order_id=%, 저장=%, 항목합=%)',
            p_order_id, o.discount_total, v_discount_sum;
    end if;

    if o.payable_amount <> v_item_sum + v_shipping_sum - v_discount_sum then
        raise exception '결제액이 항목합+배송비합−할인합과 다르다 (order_id=%, 저장=%, 계산=%)',
            p_order_id, o.payable_amount, v_item_sum + v_shipping_sum - v_discount_sum;
    end if;
end;
$$;

-- ---------------------------------------------------------------------------
-- 3. 한 주문에 한 장
-- ---------------------------------------------------------------------------

-- **주문 하나에 쿠폰 하나다.** 여럿을 허용하면 적용 순서가 결과를 바꾸고(정률끼리는
-- 순서마다 값이 다르다), 그 순서를 정하는 규칙이 화면·정산·환불에 각각 생긴다.
--
-- 지금은 그 규칙을 정할 근거가 없다 — **중복 사용을 여는 날 그때 정한다**(`D8` 「절사 단위」).
create unique index coupon_issue_one_per_order on coupon_issue (used_order_id)
    where used_order_id is not null;
