-- 공급시기 약정이 있었는지를 묶음에 박제한다(14c, Q2 가 넘긴 것).
--
-- V26 이 리드타임을 주문 시점에 박제한 이유는 「셀러가 나중에 값을 바꿔도 지나간 주문의
-- 기한이 안 흔들려야」였다. 그런데 **약정이 있었는지 자체는 안 박제됐다** —
-- seller_order.supply_lead_days 는 「계산된 결과」라 3 이 「약정 3영업일」인지
-- 「약정이 없어서 법정 3영업일」인지 안 갈린다.
--
-- 계산 결과가 같아서 기한은 안 틀린다. 틀리는 것은 **나중에 묻는 물음**이다 —
-- 「이 주문에 약정이 있었나」는 셀러 평가와 분쟁에서 갈리는데,
-- 지금은 product 의 **현재** 값을 봐야 하고 그건 그 사이 바뀌었을 수 있다.
--
--
-- 새 컬럼은 약정 그 자체다. **결과가 아니라 약속**이라 null 이 뜻을 갖는다.
--
--   agreed_lead_days = null   약정이 없었다. 법정 3영업일이 걸렸다
--   agreed_lead_days = 5      5영업일을 약정했다
--
-- supply_lead_days 는 그대로 둔다. 그쪽은 기한을 계산할 때 쓰는 결과값이고,
-- 둘을 하나로 합치면 「약정 없음」을 표현할 수 없어진다(V26 이 이미 고른 자리다).
alter table seller_order add column agreed_lead_days int;

comment on column seller_order.agreed_lead_days is
    '주문 시점의 공급시기 약정 날수(영업일). null 이면 약정이 없어 법정 3영업일이 걸렸다(D2 R21)';

alter table seller_order add constraint seller_order_agreed_lead_days_check
    check (agreed_lead_days is null or agreed_lead_days between 0 and 60);

-- 약정이 있으면 계산 결과가 그보다 짧을 수 없다.
--
-- 묶음은 한 번에 나가므로 가장 늦게 준비되는 항목이 발송 시점을 정한다(V26).
-- 약정 5영업일짜리가 들어 있는데 결과가 3이면 그 약정을 못 지키는 기한을 박아 둔 것이다.
alter table seller_order add constraint seller_order_lead_days_consistent_check
    check (agreed_lead_days is null or supply_lead_days >= agreed_lead_days);


-- 뷰도 같이 고친다.
--
-- **이 파일을 쓸 때 빠뜨렸고 SellerOrderVisibilityTest 가 잡았다.** V26 이 같은 함정을 적어 두고
-- 뷰를 다시 만들었는데 V29 가 또 밟았고, 그래서 Q2 가 컬럼 대조 테스트를 세웠다 —
-- 세운 다음 첫 마이그레이션에서 바로 걸렸다. 적어 두는 것과 막는 것의 차이가 이것이다.
drop view seller_order_visible;

create view seller_order_visible as
select so.seller_order_id,
       so.seller_order_number,
       so.order_id,
       so.seller_id,
       so.status,
       so.shipping_fee,
       so.supply_lead_days,
       so.agreed_lead_days,
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
