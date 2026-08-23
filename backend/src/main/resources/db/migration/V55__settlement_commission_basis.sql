-- 수수료 줄이 근거를 들고 있게 한다(청크 18).
--
-- 17 이 세운 정산 항목은 **금액만** 들고 있었다. 셀러가 정산서를 받아서
-- 「이 수수료가 무엇에 몇 퍼센트냐」를 물으면 정산서가 답을 못 하고, 주문 표를 다시 뒤져야 한다.
-- 그때 하는 계산이 마감 때와 같다는 보장이 없다 — 그것이 곧 박제가 안 된 상태다.
--
-- **요율을 조인해서 정산 때 계산하면 요율을 바꾼 순간 과거 주문의 정산액이 같이 바뀐다**
-- (business-model.md). 주문 항목이 이미 그 값을 굳혀 뒀고(V16 commission_bp·commission_amount)
-- 정산은 그것을 읽기만 한다. 여기서 하는 것은 **읽은 값을 정산 행에도 굳히는 것**이다.


alter table settlement_item add column commission_bp int;
alter table settlement_item add column commission_base_amount bigint;

-- 이미 들어간 수수료 줄에 근거를 채운다.
--
-- 값이 전부 주문 항목에 있다. 정산이 그 항목을 가리키고 있어서 조인 하나로 닿는다 —
-- 못 채우는 행이 있으면 아래 not null 짝 검사가 그 자리에서 막는다.
update settlement_item i
   set commission_bp = oi.commission_bp,
       commission_base_amount = oi.line_amount
  from order_item oi
 where oi.order_item_id = i.order_item_id
   and i.kind = 'commission';

comment on column settlement_item.commission_bp is
    '뗀 요율. 주문 시점에 굳힌 order_item.commission_bp 를 그대로 옮긴다(청크 18)';

comment on column settlement_item.commission_base_amount is
    '요율을 곱한 기준 금액. 그 주문 항목의 line_amount 다(청크 18)';


-- 수수료 줄에만 있다.
--
-- **환급 줄에는 안 박는다.** refund_item.commission_refund 는 원 수수료를 수량으로 나눈 값인데
-- 마지막 수량을 환불할 때 절사 잔액을 몰아 준다(money-invariants) — 그래서
-- 「기준액 × 요율」과 안 맞고, 등식은 물론 상한으로도 못 쓴다.
--
--   단가 10,005 × 3개, 10% → 항목 수수료 3,001 (30,015 × 0.1 = 3,001.5 버림)
--   2개 환불 2,000 / 마지막 1개 1,001  ←  10,005 × 0.1 = 1,000 을 넘는다
--
-- 환급의 근거는 요율 재계산이 아니라 **원 수수료 금액**이다. 그 등식(통째로 환불하면
-- 합이 commission_amount 와 같다)은 이미 refund 축이 들고 있다.
alter table settlement_item add constraint settlement_item_commission_basis_check
    check ((kind = 'commission')
           = (commission_bp is not null and commission_base_amount is not null));

alter table settlement_item add constraint settlement_item_commission_bp_range_check
    check (commission_bp is null or commission_bp between 0 and 10000);

alter table settlement_item add constraint settlement_item_commission_base_amount_check
    check (commission_base_amount is null or commission_base_amount >= 0);

-- 한 행 안에서 끝나는 등식이다. **앱이 정산 시점에 다시 계산하는 경로를 안 만든다**
-- (청크 18의 강제 지점) — 다시 계산한 값이 이 등식과 어긋나면 그 자리에서 막힌다.
--
-- 정수 나눗셈이 곧 버림이다(D8). order_item_commission_amount_check 와 같은 모양이고,
-- 절사 규칙을 반올림으로 바꾸면 두 제약을 같이 갈아 끼워야 한다.
--
-- 부호를 뒤집어 비교한다. 정산에서 우리가 떼는 것은 음수다(V52).
alter table settlement_item add constraint settlement_item_commission_amount_check
    check (kind <> 'commission'
           or -amount = commission_base_amount * commission_bp / 10000);
