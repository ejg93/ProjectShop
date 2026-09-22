-- V87 을 되돌린다. **박제된 할인액이 사라진다** — 그 주문이 얼마를 깎았는지를
-- 다시 계산할 방법이 없다(쿠폰 정의가 그 사이에 바뀌었을 수 있다).
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop index if exists coupon_issue_one_per_order;

-- V16 의 판으로 되돌린다. 할인 합계와 결제액 등식이 빠진다.
create or replace function assert_order_amounts(p_order_id bigint) returns void
language plpgsql as $$
declare
    o record;
    v_item_sum       bigint;
    v_commission_sum bigint;
    v_shipping_sum   bigint;
    v_seller_orders  bigint;
begin
    select * into o from shop_order where order_id = p_order_id;
    if not found then
        return;
    end if;

    select coalesce(sum(oi.line_amount), 0), coalesce(sum(oi.commission_amount), 0)
      into v_item_sum, v_commission_sum
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
end;
$$;

alter table shop_order drop constraint if exists shop_order_payable_amount_check;
alter table shop_order add constraint shop_order_payable_amount_check
    check (payable_amount = total_amount + shipping_fee_total);

alter table shop_order drop constraint if exists shop_order_discount_total_check;
alter table order_item drop constraint if exists order_item_discount_amount_check;
alter table shop_order drop column if exists discount_total;
alter table order_item drop column if exists discount_amount;
