-- 발송 기한이 비는 것을 막고, 뷰가 표를 다시 따라가게 한다(Q2).
--
-- 셋 다 2026-08-20 재점검이 찾은 것이고 같은 표에 붙어서 한 파일에 담는다.


-- 1. 결제된 주문에 발송 기한이 없는 묶음이 남지 않게 한다(B1).
--
-- ship_due_at 은 결제 승인 때 박제한다(V26). 지금 그것을 부르는 자리가
-- OrderStatusService.movePayment 하나뿐이라 안 빠뜨리는데, 앱 검증은
-- 새 입구가 생기면 빠뜨린다(D23 축 2). 빠뜨리면 조용하다 —
-- seller_order_ship_overdue_idx 가 `ship_due_at is not null` 로 걸러서
-- 기한을 넘긴 묶음이 「지금 늦고 있는 것」 조회에서 그냥 사라진다.
--
-- check 로는 못 막는다. 조건이 다른 표(shop_order)의 상태라서다.
-- V23 이 환불 합계에 쓴 지연 제약 트리거가 같은 물음의 선례고 여기도 그것을 쓴다.
--
-- 지연(deferrable initially deferred)이라야 하는 이유는 순서다. 한 트랜잭션 안에서
-- 상태를 옮기고 기한을 박제하는데, 즉시 검사하면 그 사이에 걸린다.
create or replace function assert_ship_deadline_frozen() returns trigger
language plpgsql as $$
declare
    v_missing int;
begin
    select count(*) into v_missing
      from seller_order so
     where so.order_id = new.order_id
       -- 취소된 묶음은 나갈 일이 없어서 기한이 뜻을 안 갖는다.
       and so.status <> 'cancelled'
       and so.ship_due_at is null;

    if v_missing > 0 then
        raise exception '결제된 주문에 발송 기한이 없는 묶음이 있다: order_id=%, 묶음 %개',
                        new.order_id, v_missing
              using errcode = 'check_violation';
    end if;

    return null;
end;
$$;

comment on function assert_ship_deadline_frozen() is
    '결제 승인 시점에 발송 기한이 다 박제됐는지 본다(D2 R21, 전자상거래법 제15조제1항)';

create constraint trigger shop_order_ship_deadline_frozen
    after update of status on shop_order
    deferrable initially deferred
    for each row
    when (new.status = 'paid')
    execute function assert_ship_deadline_frozen();


-- 2. 환불의 사람 외래키에 인덱스를 단다(B5).
--
-- on delete restrict 라 app_user 를 지울 때 이 표를 훑는다. 지금은 탈퇴가 update 라
-- 그 경로가 안 타지만(D13), 파기 배치가 행을 지우는 날 그때 훑는다.
-- 승인자로 거른 조회(「내가 승인한 환불」)도 이 인덱스를 쓴다.
create index refund_requested_by_idx on refund (requested_by_user_id)
 where requested_by_user_id is not null;

create index refund_approved_by_idx on refund (approved_by_user_id)
 where approved_by_user_id is not null;


-- 3. 뷰를 다시 만든다(B2).
--
-- V26 이 같은 이유로 이미 한 번 다시 만들었다 — 뷰가 컬럼 목록을 굳혀서 표에 컬럼이 늘어도
-- 안 따라온다. 그런데 다음 날 V29 가 return_reason 을 더하면서 또 안 고쳤다.
--
-- 셀러 주문 화면(13g)이 반품 사유를 그리려면 이 뷰로 와야 한다. 셀러 조회는 표를 직접 안 읽는다.
--
-- 재발을 막는 것은 SellerOrderVisibilityTest 의 컬럼 대조다. 뷰는 check 를 못 걸어서
-- 테스트가 천장이고(D23 축 2), 그 테스트가 없어서 같은 함정을 두 번 밟았다.
drop view seller_order_visible;

create view seller_order_visible as
select so.seller_order_id,
       so.seller_order_number,
       so.order_id,
       so.seller_id,
       so.status,
       so.shipping_fee,
       so.supply_lead_days,
       so.ship_due_at,
       so.shipped_at,
       so.delivered_at,
       so.withdrawal_expire_at,
       so.auto_confirm_at,
       so.return_reason,
       so.closed_at,
       so.created_at,
       so.updated_at
  from seller_order so
  join shop_order o on o.order_id = so.order_id
 where o.status = 'paid';

comment on view seller_order_visible is
    '셀러에게 보이는 셀러 주문. 결제가 끝난 것만 든다(11c-2). 표에 컬럼이 늘면 여기도 늘린다(Q2)';
