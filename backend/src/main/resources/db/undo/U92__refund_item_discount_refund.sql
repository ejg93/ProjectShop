-- V92 를 되돌린다. **정산 되돌림이 다시 칸 없이 계산된다** — `SettlementService` 의 되돌림 쿼리를
-- `V92` 앞 판으로 같이 돌려야 한다. 이 칸을 읽는 코드가 남은 채로 두면 정산 마감이 죽는다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

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

alter table refund_item drop constraint refund_item_discount_refund_check;
alter table refund_item drop column discount_refund;
