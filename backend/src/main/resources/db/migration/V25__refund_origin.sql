-- 환불이 어디서 왔나를 데이터로 가른다.
--
-- 두 가지를 고친다. 둘 다 12a-1 이 하나로 뭉쳐 둔 것이고, 법이 그것을 가른다.
--
--   1. 사유    cancelled 하나가 서로 다른 조문 둘을 덮고 있었다.
--   2. 요청자  배치가 요청을 만들 자리가 없었다. requested_by_user_id 가 not null 이다.


-- 1. 셀러 공급 불능을 별도 사유로 뗀다.
--
-- 법의 기산점이 다르다. 같은 「취소」인데 조문이 갈린다.
--
--   고객이 취소  제18조제2항 3호  재화등을 공급하지 아니한 경우 → 청약철회를 한 날
--   셀러가 취소  제15조제2항      공급하기 곤란             → 대금을 지급한 날
--
-- 셀러 취소를 청약철회로 세면 기한이 결제일보다 뒤로 밀린다 — 법보다 늦게 잡는 것이다.
-- 사유를 안 가르고 계산만 가르면 같은 사유에 다른 기한이 나오는 행이 생기고,
-- 그때 어느 쪽이 맞는지를 판단할 근거가 행에 없다.
--
-- admin_cancelled 를 따로 두는 이유는 판단이 안 서서다. 관리자 취소는 사유가 자유 텍스트라
-- 코드가 조문을 못 고른다. 그래서 이른 쪽(대금 지급일)을 쓴다 —
-- 늦게 잡으면 위반이고 일찍 잡으면 우리가 손해를 볼 뿐이다.

alter table refund drop constraint refund_reason_code_check;

alter table refund add constraint refund_reason_code_check
    check (reason_code in ('cancelled', 'supply_failed', 'admin_cancelled',
                           'withdrawal', 'payment_error'));

comment on column refund.reason_code is
    '환불 사유. 기산점이 여기서 갈린다 — 고객 취소·반품은 closed_at, 그 밖은 결제일(D2 R5)';

-- 지금 있는 행은 전부 12a-1·12a-2 의 테스트가 만든 것이거나 개발 중에 만든 것이라
-- 재분류할 것이 없다. 운영 데이터가 있었으면 이 자리에 update 가 와야 한다 —
-- 없다는 것을 적어 두지 않으면 다음 사람이 "빠뜨렸나" 를 묻게 된다.


-- 2. 요청자를 사람 아닌 것도 담을 수 있게 연다.
--
-- 스위퍼(12a-3)가 요청을 만든다. 법이 청약철회만으로 환급 의무를 발생시키므로
-- (D2 R5) 사람이 요청을 안 내도 환불이 시작돼야 한다.
--
-- order_status_history 가 같은 문제를 이미 풀었다(V18) — actor_type 에 system 을 두고
-- 사람 컬럼을 nullable 로 뒀다. 같은 모양을 쓴다. 둘이 다른 방식이면
-- 「이 행을 누가 만들었나」를 묻는 코드가 표마다 달라진다.

alter table refund add column requested_by_type text not null default 'customer';

-- 기본값을 지우는 이유는 다음 행이 조용히 customer 가 되지 않게 하려는 것이다.
-- 기존 행을 채우는 데만 쓰고 걷는다.
alter table refund alter column requested_by_type drop default;

alter table refund alter column requested_by_user_id drop not null;

alter table refund add constraint refund_requested_by_type_check
    check (requested_by_type in ('customer', 'seller', 'admin', 'system'));

-- 사람이 낸 요청은 누구인지 남는다. 시스템은 지목할 사람이 없다.
-- 한쪽만 걸면 「관리자가 냈는데 누구인지 모르는」 행이 생긴다(V18 과 같은 문장).
alter table refund add constraint refund_requested_by_user_check
    check ((requested_by_type = 'system') = (requested_by_user_id is null));

comment on column refund.requested_by_type is
    '요청 출처. system 은 스위퍼가 만든 것이다(12a-3)';


-- 자기승인 차단이 시스템 요청에서 뚫리지 않게 한다.
--
-- 원래 제약은 두 사람을 비교했다. requested_by_user_id 가 null 이 될 수 있게 되면서
-- 그 비교가 참도 거짓도 아닌 null 이 되고, check 는 null 을 통과시킨다.
--
-- 시스템이 만든 요청은 낸 사람이 없으므로 누가 승인해도 자기승인이 아니다.
-- 그것이 맞는 동작이라 조건을 명시적으로 적는다 — 지금 모양은 우연히 그렇게 되는 것이고,
-- 우연히 맞는 것과 그렇게 정한 것은 다음에 이 줄을 읽는 사람에게 다르다.
alter table refund drop constraint refund_self_approval_check;

alter table refund add constraint refund_self_approval_check
    check (approved_by_user_id is null
           or requested_by_user_id is null
           or approved_by_user_id <> requested_by_user_id);


-- 스위퍼가 매 회차에 훑는 조건이다.
--
-- 닫힌 묶음 중 환불 요청이 없는 것을 찾는다. 인덱스가 없으면 seller_order 전체를 훑고,
-- 그 표는 주문 수만큼 자란다.
--
-- confirmed 는 안 담는다. closed_at 은 확정에서도 차는데(OrderStatusService.closeTransaction)
-- 구매확정은 환불 대상이 아니다 — 부분 인덱스의 조건이 곧 스위퍼의 대상 조건이다.
create index seller_order_closed_refundable_idx on seller_order (closed_at)
 where closed_at is not null and status in ('cancelled', 'returned');
