-- 「발송이 늦었나」를 뷰가 답하게 한다(`11c-2c`).
--
-- 이 판정식이 지금 앱 SQL 두 곳에 **같은 글자로** 있다 —
-- OrderQuery(사는 사람의 주문 상세)와 SellerOrderQuery.findByNumber(셀러의 묶음 상세).
-- 여기에 셀러 목록이 더해지면 셋이 되고, 그때부터 **한 곳을 고치고 두 곳을 안 고치는 자리**가 생긴다.
--
-- 기한을 넘겼는지는 도메인 규칙이지 화면마다 정할 것이 아니다. 넘기면 지연배상금이
-- 연 15% 로 붙는다(전자상거래법 시행령 제21조의3) — 화면마다 답이 다르면 안 되는 종류의 물음이다.
--
-- 그래서 막을 수 있는 가장 낮은 자리로 내린다(`D23` 축 2). 셀러 조회는 표를 직접 안 읽고
-- 이 뷰로만 오므로(`11c-2`) 뷰에 두면 셀러 쪽 사본이 0 이 된다.
--
-- **OrderQuery 는 그대로 둔다.** 그쪽은 사는 사람의 화면이라 `seller_order` 표를 직접 읽는다
-- (뷰는 결제된 것만 들어서 결제 전 주문을 못 그린다). 사본이 둘에서 둘로 갈 뿐이지만,
-- 늘어나는 쪽을 막는 것이 이 파일이 하는 일이다.
--
--
-- 이름이 `is_` 로 시작하는 것은 `D22` 다. **JSON 은 안 바뀐다** — Java 필드가
-- 그대로 `shipOverdue` 라서 응답 이름도 `ship_overdue` 그대로고, 이미 나가고 있는 계약을 안 건드린다.
--
--
-- 뷰를 통째로 다시 만든다. `V35` 가 `agreed_lead_days` 를 더하면서 만든 판이 바탕이고,
-- 컬럼 목록을 안 옮기면 그 컬럼이 사라진다 — `V26`·`V29` 가 실제로 밟은 함정이고
-- SellerOrderVisibilityTest 의 컬럼 대조가 그것을 잡는다(`Q2`).
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
       -- 기한이 없으면 늦은 것이 아니다. 취소된 묶음이 그렇고(`V30`), 결제 전에도 안 박힌다.
       -- 아직 안 보냈으면 지금 시각으로 잰다 — 보냈으면 보낸 시각으로 잰다.
       (so.ship_due_at is not null
        and coalesce(so.shipped_at, now()) > so.ship_due_at) as is_ship_overdue,
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
    '셀러에게 보이는 셀러 주문. 결제가 끝난 것만 든다(11c-2). 표에 컬럼이 늘면 여기도 늘린다(Q2). '
    'is_ship_overdue 는 표에 없는 파생 컬럼이다 — 판정식을 앱마다 베끼지 않으려고 여기 둔다(11c-2c)';
