-- 공급 기한. 전자상거래법 제15조제1항(D2 R21).
--
-- 「선지급식 통신판매의 경우에는 소비자가 그 대금을 전부 또는 일부 지급한 날부터
--  3영업일 이내에 재화등의 공급을 위하여 필요한 조치를 하여야 한다」
--
-- 우리 결제는 선지급식이다 — 물건을 받기 전에 낸다. 그리고 우리 상태머신에서
-- 「공급을 위하여 필요한 조치」는 preparing → shipping 전이다.
--
-- seller_order 는 기한을 셋 박제해 왔다(withdrawal_expire_at·auto_confirm_at·closed_at).
-- 이것만 없어서 셀러가 preparing 에 무한정 두어도 걸리는 것이 없었다.
--
-- 중개자 고지로 안 빠져나간다. 제20조의2제3항이 못 면하는 범위에 제15조를 넣는다(D2 R5).


-- 1. 상품마다 공급에 걸리는 날수.
--
-- 제15조제1항 단서가 「공급시기에 관하여 따로 약정한 것이 있는 경우에는 그러하지 아니하다」
-- 라고 한다. 주문제작·예약판매가 그것이다.
--
-- null 이 기본값(3영업일)을 뜻하는 것이 아니다 — 「이 상품은 약정이 없다」는 사실이고,
-- 약정이 없으면 법정 기한이 그대로 걸린다. 빈 값에 계산을 싣지 않는다(D23).
--
-- 약정은 고지가 성립 요건이다. 값만 두고 상품 상세가 안 그리면 그 약정은 서지 않고
-- 3영업일이 그대로 걸린다 — R4 가 청약철회 제한에서 겪은 것과 같은 구조고,
-- 그리는 것은 청크 14c 다.
alter table product add column supply_lead_days int;

-- 0 은 당일 발송이다. 위 상한은 법이 정한 것이 아니라 우리가 정한 것으로,
-- 오타로 3000 이 들어가 기한이 8년 뒤가 되는 것을 막는다.
alter table product add constraint product_supply_lead_days_check
    check (supply_lead_days is null or supply_lead_days between 0 and 60);

comment on column product.supply_lead_days is
    '공급시기 약정 날수(영업일). null 이면 약정이 없어 법정 3영업일이 걸린다(D2 R21)';


-- 2. 묶음에 박제하는 값 셋.
--
-- 리드타임을 주문 시점에 박제하는 이유는 가격·수수료율과 같다 —
-- 셀러가 나중에 값을 바꿔도 지나간 주문의 기한이 안 흔들려야 한다(D10).
--
-- 묶음 안에서 가장 긴 것을 쓴다. 한 셀러 묶음은 한 번에 나가므로 가장 늦게 준비되는
-- 항목이 그 묶음의 발송 시점을 정한다.
-- 기본값을 남긴다. product 쪽과 반대인데 뜻이 달라서다 —
-- 그쪽 null 은 「약정이 없다」는 사실이고, 이쪽은 「계산된 결과」라 비어 있을 수 없다.
--
-- 값을 안 넣고 넣는 경로가 생기면 법정 3영업일로 떨어진다. 그것이 안전한 방향이다 —
-- 기본값이 길면 빠뜨린 순간 기한이 늘어나서 위반이 되고, 짧으면 우리가 더 서두를 뿐이다.
alter table seller_order add column supply_lead_days int not null default 3;

alter table seller_order add constraint seller_order_supply_lead_days_check
    check (supply_lead_days between 0 and 60);

-- 발송 기한. 결제가 승인될 때 박제한다.
--
-- 이름이 ship_ 인 이유는 이 기한이 재는 것이 발송이어서다. 법은 「공급을 위하여
-- 필요한 조치」라고 하는데 우리 도메인에서 그것은 preparing → shipping 하나다 —
-- 조문 용어를 그대로 쓰면 이 컬럼이 배송완료까지 포함하는 것처럼 읽힌다.
alter table seller_order add column ship_due_at timestamptz;

-- 실제로 보낸 시각.
--
-- 이것이 없으면 지연이 「지금 preparing 이면서 기한을 넘긴 것」으로만 표현되고,
-- 늦게라도 보내는 순간 흔적이 사라진다. 상습적으로 늦는 셀러와 한 번 늦은 셀러가
-- 데이터에서 같아 보인다.
--
-- delivered_at 이 이미 같은 모양이라 선례가 맞고, 배송 소요일 같은 지표도 그 둘의 차로 나온다.
alter table seller_order add column shipped_at timestamptz;

comment on column seller_order.supply_lead_days is '주문 시점에 박제한 약정 날수. 없으면 3';
comment on column seller_order.ship_due_at is '발송 기한. 결제 승인 때 박제한다(D2 R21)';
comment on column seller_order.shipped_at is '실제로 보낸 시각. 지연을 사후에 판단하는 근거다';

-- 「기한 없이 보낸 것」을 막는 제약을 안 건다.
--
-- V16 이 withdrawal_expire_at 에 건 것과 모양이 비슷해 보이지만 막는 것이 다르다.
-- 그쪽은 「기산점 없는 기한」이라 값 자체가 뜻을 잃는데, 이쪽은 기한이 없어도 되는 경우가 있다 —
-- 결제를 안 지난 묶음을 옮기는 경로가 전이 서비스에 남아 있고(뷰로 거르는 것은
-- OrderActionService 다) 그 사실을 이 컬럼이 판단할 일이 아니다.
--
-- 걸어도 얻는 것이 없다. ship_due_at 이 비면 지연 판정이 false 로 떨어져서
-- 틀린 신호가 안 나간다 — 없는 기한을 넘겼다고 말하지 않는다.


-- 3. 셀러에게 보이는 뷰를 다시 만든다.
--
-- 뷰는 만들 때의 컬럼 목록을 굳혀서 표에 컬럼이 늘어도 안 따라온다.
-- 셀러 조회가 이 뷰만 읽으므로(11c-2b) 여기서 안 열면 셀러는 자기 발송 기한을 못 본다 —
-- 기한을 지켜야 하는 사람이 그 기한을 못 보는 것이다.
--
-- 컬럼을 더할 때 이 자리를 같이 고치는 것을 잊으면 조회가 「그런 컬럼이 없다」로 깨진다.
-- 조용히 틀리지 않고 바로 깨지는 쪽이라 방벽이 따로 필요 없다.
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
       so.closed_at,
       so.created_at,
       so.updated_at
  from seller_order so
  join shop_order o on o.order_id = so.order_id
 where o.status = 'paid';

comment on view seller_order_visible is
    '셀러에게 보이는 셀러 주문. 결제가 끝난 것만 든다(11c-2)';


-- 기한을 넘긴 미발송 묶음을 찾는 자리다(D2 R21).
--
-- 조건이 곧 「지금 늦고 있는 것」의 정의고, 이 인덱스가 없으면 그 조회가
-- seller_order 전체를 훑는다. 그 표는 주문 수만큼 자란다.
create index seller_order_ship_overdue_idx on seller_order (ship_due_at)
 where status = 'preparing' and ship_due_at is not null;
