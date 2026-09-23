-- 환불 항목이 되돌린 할인을 박제한다(`Q168`).
--
-- **`Q164` 가 같은 커밋 안에서 자기 전제를 없앴다**(마무리 44차 독립 리뷰). `sale` 줄은
-- `oi.line_amount`(할인 **전**)로 서는데 `sale_reversal` 은 `-ri.amount` 고, `Q164` 가
-- `ri.amount` 를 할인 **후**로 바꾼 순간 되돌림만 할인 후가 됐다. 그 위에
-- `coupon_discount_reversal = +할인` 을 더하니 **정산 뒤 환불할 때마다 셀러가 할인액을 한 번 더 받는다.**
--
-- **되돌림을 할인 전 축으로 올린다**(사용자 선택). `sale_reversal = -(amount + discount_refund)`,
-- `coupon_discount_reversal = +discount_refund` — 판매와 판매되돌림이 같은 축에 서고,
-- 쿠폰 부담을 되돌린 것이 정산서에 줄로 남는다.
--
-- **왜 칸을 세우나.** 정산 쿼리가 `할인 × 환불수량 ÷ 주문수량` 을 그때마다 계산하면 나눠서 환불할 때
-- 버림이 쌓여 **통째로 환불해도 할인 몫이 몇 원 덜 되돌아간다.** 대금(`amount`)과 수수료
-- (`commission_refund`)는 이미 「잔액을 마지막 수량에 몰아 준다」로 끝을 맞추는데(`RefundMath`)
-- 할인만 그 규칙 밖에 있었다. 환불 순간에 같은 규칙으로 계산해 박제한다 — 정산은 읽기만 한다(청크 18).

alter table refund_item add column discount_refund bigint not null default 0;

-- 이미 있는 행을 채운다. **잔액 몰아주기를 여기서 다시 하지 않는다** — 그 행이 마지막 수량이었는지를
-- 되짚으려면 환불 순서를 재구성해야 하고, 이 칸이 서기 전의 행은 로컬에만 있다(`V89` 가 배포 전이다).
update refund_item ri
   set discount_refund = oi.discount_amount * ri.quantity / oi.quantity
  from order_item oi
 where oi.order_item_id = ri.order_item_id
   and oi.discount_amount > 0;

-- **기본값을 걷는다.** 남겨 두면 넣는 쪽이 이 칸을 잊어도 0 으로 들어가고, 그 순간 할인 쓴 주문의
-- 되돌림이 다시 할인 후 축으로 떨어진다 — 이 청크가 막으려는 바로 그것이다.
alter table refund_item alter column discount_refund drop default;

alter table refund_item add constraint refund_item_discount_refund_check
    check (discount_refund >= 0);

comment on column refund_item.discount_refund is
    '이 환불이 되돌린 배분 할인(Q168). 대금(amount)과 더하면 할인 전 값이다. 잔액은 마지막 수량에 몬다';

-- 항목 상한에 할인 누계를 더한다. **나머지는 `V89` 와 같다** — 함수를 통째로 다시 쓰는 것은
-- `create or replace` 가 부분 수정을 못 해서다.
create or replace function assert_refund_item_within_order_item(p_order_item_id bigint)
returns void
language plpgsql as $$
declare
    oi record;
    v_quantity int;
    v_amount bigint;
    v_commission bigint;
    v_discount bigint;
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
           coalesce(sum(ri.commission_refund), 0), coalesce(sum(ri.discount_refund), 0)
      into v_quantity, v_amount, v_commission, v_discount
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

    -- 되돌린 할인이 배분된 할인을 넘으면 `sale_reversal` 이 판매보다 커진다 —
    -- 셀러가 판 것보다 많이 토해 낸다.
    if v_discount > oi.discount_amount then
        raise exception '되돌린 할인이 배분된 할인을 넘는다 (order_item_id=%, 할인=%, 되돌림누계=%)',
            p_order_item_id, oi.discount_amount, v_discount;
    end if;
end;
$$;
