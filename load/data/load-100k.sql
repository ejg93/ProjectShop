-- 부하 데이터 — 로컬 10만 건(`70-0`). 측정(`42`)과 부하(`70`)의 입력이다.
--
-- **`shop_load` DB 에만 붓는다.** `scripts/load-data.sh` 가 `shop` 을 틀로 복사한 DB 에 이 파일을 한 트랜잭션으로 돌린다.
-- 주문은 지울 길이 없어서(거래기록 5년 — `restrict`, `deleted_at` 없음) 지우기는 `drop database` 다.
--
-- **트리거·제약을 끄지 않는다.** 금액 합·계약서면 네 조항·재고 행·환불 합은 커밋 때 DB 가 검사한다 —
-- 생성기가 규칙을 어기면 붓기가 실패한다. 재고 행이 낳은 아웃박스 사건은 스크립트가 붓고 나서 비운다.
--
-- **`random()` 을 안 쓴다.** 건수와 분포가 번호의 나머지로 정해져서 다시 부어도 같다.
--
-- 수: 구매자 1,000(합성) · 상품 1만 · SKU 3만 · 주문 10만 · 묶음 12만 · 항목 20만 · 환불 8,000(결제된 취소·반품 묶음마다).
--
-- **앱의 주기 작업이 고쳐 쓸 것을 안 남긴다**(`70`). 실제로는 있을 수 없는 행(30분 넘은 결제 대기, 환불 없는 닫힌
-- 취소 묶음, 통지가 안 나간 주문)을 넣으면 부하를 재는 도중에 앱이 그것을 메우느라 쓰기가 돌고, 회차마다 데이터가 달라진다.
-- 구매자는 합성 계정이 주문의 99% 를, `customer@example.com` 이 1% 를 가진다 — 한 사람에게 몰면 「내 주문」의
-- 실행 계획이 순차 스캔을 정답으로 골라 인덱스 누락과 구분이 안 된다(2026-10-02 사용자 결정).

-- 노출 번호의 끝 여섯 자. 32자 알파벳(`[2-9A-HJ-NP-Z]`)으로 n 을 적는다 — 번호 형식 검사와 같은 글자 집합이다.
create function pg_temp.b32(n bigint) returns text language sql immutable as $$
    select string_agg(substr('23456789ABCDEFGHJKLMNPQRSTUVWXYZ', ((n >> (5 * p)) & 31)::int + 1, 1), '' order by p)
      from generate_series(0, 5) as p
$$;

-- ① 합성 구매자. 로그인할 일이 없어서 비밀번호 해시는 하나를 같이 쓴다.
insert into app_user (email, password_hash, display_name)
select 'load-' || lpad(u::text, 4, '0') || '@load.invalid',
       h.hash,
       '부하구매자' || lpad(u::text, 4, '0')
  from generate_series(1, 1000) as u
 cross join (select '{bcrypt}' || crypt('load-password-1234', gen_salt('bf', 4)) as hash) as h;

insert into user_role (user_id, role_id)
select u.user_id, r.role_id
  from app_user u
  join role r on r.code = 'customer'
 where u.email like 'load-%@load.invalid';

-- 구매자 번호(1~1000) → user_id. 0 번은 데모 고객이다.
create temp table t_buyer as
select row_number() over (order by u.email) as b, u.user_id
  from app_user u
 where u.email like 'load-%@load.invalid'
 union all
select 0, user_id from app_user where email = 'customer@example.com';

-- 데모 셀러 둘. 0 = demo-fashion, 1 = demo-craft
create temp table t_seller as
select case s.code when 'demo-fashion' then 0 else 1 end as k,
       s.seller_id, s.commission_bp, s.default_shipping_fee,
       (select u.user_id from app_user u
         where u.email = case s.code when 'demo-fashion' then 'fashion-owner@example.com'
                                     else 'craft-owner@example.com' end) as owner_id
  from seller s
 where s.code in ('demo-fashion', 'demo-craft');

-- ② 상품 1만 — 셀러마다 5,000. 이름에 낱말 여덟을 돌려 넣어 검색(`60`)이 잴 거리를 만든다.
-- 열 중 하나는 초안이고, 다섯 중 하나는 설명이 없다.
create temp table t_word as
select w.i, w.word
  from unnest(array['운동화', '깔창', '가방', '머그컵', '노트', '양말', '모자', '우산']) with ordinality as w (word, i);

insert into product (seller_id, created_by_user_id, name, description, status, created_at, updated_at)
select s.seller_id, s.owner_id,
       '부하 상품 ' || n || ' ' || w.word,
       case when n % 5 <> 0 then w.word || '를 위한 부하 상품 설명 ' || n end,
       case when n % 10 = 0 then 'draft' else 'on_sale' end,
       now() - make_interval(days => (n % 180)::int, mins => (n % 1440)::int),
       now() - make_interval(days => (n % 180)::int, mins => (n % 1440)::int)
  from generate_series(1, 10000) as n
  join t_seller s on s.k = n % 2
  join t_word w on w.i = n % 8 + 1;

-- ③ SKU 3만 — 상품마다 셋, 재고 행을 같은 트랜잭션에 둔다(`sku_requires_stock`).
insert into sku (product_id, price_incl_vat)
select p.product_id, 1000 + ((p.product_id * 3 + v) % 50) * 1000
  from product p
 cross join generate_series(0, 2) as v
 where p.name like '부하 상품 %';

insert into sku_stock (sku_id, on_hand)
select sk.sku_id, 100
  from sku sk
  join product p on p.product_id = sk.product_id
 where p.name like '부하 상품 %';

-- 셀러별 SKU 번호(1~15000) → sku. 항목이 그 셀러의 SKU 를 고른다.
create temp table t_sku as
select s.k, row_number() over (partition by s.k order by sk.sku_id) as rn,
       sk.sku_id, sk.price_incl_vat, p.name as product_name
  from sku sk
  join product p on p.product_id = sk.product_id
  join t_seller s on s.seller_id = p.seller_id
 where p.name like '부하 상품 %';
create index on t_sku (k, rn);
analyze t_sku;

-- ④ 주문 10만의 뼈대. 최근 90일에 편다. 상태는 i 의 나머지로 — 90% 결제, 4% 대기, 3% 만료, 3% 실패.
create temp table t_order as
select i,
       case when i % 100 = 0 then 0 else 1 + i % 1000 end as b,
       case when i % 100 < 90 then 'paid'
            when i % 100 < 94 then 'payment_pending'
            when i % 100 < 97 then 'payment_expired'
            else 'payment_failed' end as status,
       -- **결제 대기는 최근 10분 안이다.** 앱이 5분마다 30분 넘은 대기를 만료시켜서(`OrderStatusBatch`) 오래된 대기는
       -- 실제로 있을 수 없고, 넣어 두면 부하를 재는 도중에 앱이 4천 건을 고쳐 쓴다(2026-10-02 `70` 실측).
       case when i % 100 between 90 and 93 then now() - make_interval(mins => (i % 10)::int)
            else now() - make_interval(days => (i % 90)::int, mins => (i % 1440)::int) end as created_at
  from generate_series(1, 100000) as i;
alter table t_order add column order_number text;
update t_order set order_number = to_char(created_at at time zone 'Asia/Seoul', 'YYYYMMDD') || '-' || pg_temp.b32(i);
create index on t_order (i);
-- **임시 표는 autovacuum 이 통계를 안 모은다.** 안 모으면 플래너가 행 수를 모르고 중첩 루프를 골라
-- 묶음 12만을 잇는 데 10분을 넘겼다(2026-10-02 실측). 임시 표를 만들 때마다 센다.
analyze t_order;

-- ⑤ 묶음 12만 — 주문마다 셀러 하나(i 의 홀짝), 다섯 중 하나는 다른 셀러가 더 붙는다.
create temp table t_so as
select row_number() over (order by o.i, x.extra) as j, o.i, x.k, x.extra
  from t_order o
 cross join lateral (select (o.i % 2)::int as k, 0 as extra
                     union all
                     select (1 - o.i % 2)::int, 1 where o.i % 5 = 0) as x;
create index on t_so (j);
analyze t_so;
alter table t_so add column status text;
update t_so so
   set status = case
           when o.status = 'paid' then
               case when (so.i + so.extra) % 20 < 2 then 'preparing'
                    when (so.i + so.extra) % 20 < 4 then 'shipping'
                    when (so.i + so.extra) % 20 < 7 then 'delivered'
                    when (so.i + so.extra) % 20 < 18 then 'confirmed'
                    when (so.i + so.extra) % 20 = 18 then 'cancelled'
                    else 'returned' end
           when o.status = 'payment_pending' then 'preparing'
           else 'cancelled' end
  from t_order o
 where o.i = so.i;

-- ⑥ 항목 20만 — 묶음마다 하나, 셋 중 둘은 하나 더. SKU 는 그 묶음 셀러의 것이다.
create temp table t_item as
select so.j, m,
       sk.sku_id, sk.product_name, sk.price_incl_vat as unit_price,
       (1 + (so.j + m) % 3)::int as quantity,
       s.commission_bp
  from t_so so
 cross join generate_series(0, 1) as m
  join t_seller s on s.k = so.k
  join t_sku sk on sk.k = so.k and sk.rn = 1 + (so.j * 7 + m) % 15000
 where m = 0 or so.j % 3 <> 0;
analyze t_item;

-- 주문 금액은 항목에서 센다 — 넣는 순간 맞아야 커밋 때 `assert_order_amounts` 를 지난다.
insert into shop_order (order_number, user_id, status, total_amount, commission_total,
                        shipping_fee_total, payable_amount, created_at, updated_at)
select o.order_number, bu.user_id, o.status,
       a.total, a.commission, f.shipping, a.total + f.shipping,
       o.created_at, o.created_at
  from t_order o
  join t_buyer bu on bu.b = o.b
  join (select so.i,
               sum(it.unit_price * it.quantity) as total,
               sum(it.unit_price * it.quantity * it.commission_bp / 10000) as commission
          from t_item it
          join t_so so on so.j = it.j
         group by so.i) as a on a.i = o.i
  join (select so.i, sum(s.default_shipping_fee) as shipping
          from t_so so
          join t_seller s on s.k = so.k
         group by so.i) as f on f.i = o.i;

alter table t_order add column order_id bigint;
update t_order o set order_id = so.order_id from shop_order so where so.order_number = o.order_number;
analyze t_order;

insert into order_shipping (order_id, receiver_name, receiver_phone, postal_code, address1)
select order_id, '부하구매자', '010-0000-0000', '06236', '서울특별시 강남구 테헤란로 1'
  from t_order;

-- 계약내용 서면 네 조항(`V31`) — 셋은 정책 문서, 약관은 동의 항목을 가리킨다(`OrderFixture` 와 같은 모양).
insert into order_contract_document (order_id, policy_document_id, clause)
select o.order_id,
       (select policy_document_id from policy_document where effective_at <= now() order by policy_document_id limit 1),
       c.clause
  from t_order o
 cross join unnest(array['withdrawal', 'exchange', 'dispute']) as c (clause);

insert into order_contract_document (order_id, consent_item_id, clause)
select o.order_id,
       (select consent_item_id from consent_item where effective_at <= now() order by consent_item_id limit 1),
       'terms'
  from t_order o;

-- 묶음. 배송 이후 상태는 발송·도착 시각을, 끝난 거래는 `closed_at` 을 채운다.
-- 번호를 먼저 정해 두고 넣은 뒤 그 번호(고유 인덱스)로 id 를 되찾는다.
alter table t_so add column number text;
update t_so so
   set number = 'S-' || to_char(o.created_at at time zone 'Asia/Seoul', 'YYYYMMDD') || '-' || pg_temp.b32(so.j)
  from t_order o
 where o.i = so.i;

insert into seller_order (seller_order_number, order_id, seller_id, status, shipping_fee, supply_lead_days,
                          ship_due_at, shipped_at, carrier_code, tracking_no,
                          delivered_at, withdrawal_expire_at, auto_confirm_at, return_reason, closed_at,
                          created_at, updated_at)
select so.number,
       o.order_id, s.seller_id, so.status, s.default_shipping_fee, 3,
       o.created_at + interval '3 days',
       case when so.status in ('shipping', 'delivered', 'confirmed', 'returned') then o.created_at + interval '1 day' end,
       case when so.status in ('shipping', 'delivered', 'confirmed', 'returned') then 'cj' end,
       case when so.status in ('shipping', 'delivered', 'confirmed', 'returned') then lpad(so.j::text, 12, '0') end,
       case when so.status in ('delivered', 'confirmed', 'returned') then o.created_at + interval '3 days' end,
       case when so.status in ('delivered', 'confirmed', 'returned') then o.created_at + interval '10 days' end,
       case when so.status in ('delivered', 'confirmed', 'returned') then o.created_at + interval '10 days' end,
       case when so.status = 'returned' then 'change_of_mind' end,
       case when so.status = 'confirmed' then o.created_at + interval '10 days'
            when so.status = 'cancelled' then o.created_at + interval '1 hour'
            when so.status = 'returned' then o.created_at + interval '12 days' end,
       o.created_at, o.created_at
  from t_so so
  join t_order o on o.i = so.i
  join t_seller s on s.k = so.k;

alter table t_so add column seller_order_id bigint;
update t_so so set seller_order_id = x.seller_order_id from seller_order x where x.seller_order_number = so.number;
analyze t_so;

insert into order_item (seller_order_id, sku_id, product_name, unit_price_incl_vat, quantity,
                        line_amount, commission_bp, commission_amount, created_at)
select so.seller_order_id, it.sku_id, it.product_name, it.unit_price, it.quantity,
       it.unit_price * it.quantity, it.commission_bp,
       it.unit_price * it.quantity * it.commission_bp / 10000,
       o.created_at
  from t_item it
  join t_so so on so.j = it.j
  join t_order o on o.i = so.i;

-- ⑦ 결제 — 결제된 주문은 승인(셋 중 둘은 카드), 실패한 주문은 거절.
insert into payment (order_id, status, method, amount, approval_number, decline_reason, created_at)
select o.order_id,
       case o.status when 'paid' then 'approved' else 'failed' end,
       case when o.i % 3 = 0 then 'transfer' else 'card' end,
       so.payable_amount,
       case when o.status = 'paid' then 'AP-' || o.order_number end,
       case when o.status = 'payment_failed' then '한도 초과' end,
       o.created_at
  from t_order o
  join shop_order so on so.order_id = o.order_id
 where o.status in ('paid', 'payment_failed');

insert into payment_card (payment_id, card_issuer, card_last4)
select p.payment_id, '부하카드', lpad((p.payment_id % 10000)::text, 4, '0')
  from payment p
  join t_order o on o.order_id = p.order_id
 where p.method = 'card';

-- ⑧ 환불 — 결제된 주문의 취소·반품 묶음마다 하나, 그 묶음의 항목 전부를 손님이 요청한 채로 둔다(대기열이 읽는 모양).
--
-- **하나라도 빠뜨리면 앱이 메운다.** 환불 스위퍼(`RefundSweeper`)가 5분마다 「결제됐는데 환불이 없는 닫힌 취소·반품
-- 묶음」을 찾아 환불을 만들고, 상태 이력이 없는 묶음에서는 실패한다 — 반품 천 건에만 환불을 두었더니 부하를 재는
-- 도중에 3천 건을 쓰고 4천 건에서 실패했다(2026-10-02 `70` 실측).
create temp table t_refund as
select so.j, so.seller_order_id, so.status, o.created_at, bu.user_id
  from t_so so
  join t_order o on o.i = so.i
  join t_buyer bu on bu.b = o.b
 where o.status = 'paid' and so.status in ('cancelled', 'returned');
analyze t_refund;

insert into refund (refund_number, seller_order_id, reason_code, amount, requested_by_type, requested_by_user_id,
                    due_at, created_at, updated_at)
select 'R-' || to_char(r.created_at at time zone 'Asia/Seoul', 'YYYYMMDD') || '-' || pg_temp.b32(r.j),
       r.seller_order_id,
       case r.status when 'cancelled' then 'cancelled' else 'withdrawal' end,
       a.amount, 'customer', r.user_id,
       r.created_at + case r.status when 'cancelled' then interval '4 days' else interval '15 days' end,
       r.created_at + case r.status when 'cancelled' then interval '1 hour' else interval '12 days' end,
       r.created_at + case r.status when 'cancelled' then interval '1 hour' else interval '12 days' end
  from t_refund r
  join (select oi.seller_order_id, sum(oi.line_amount) as amount
          from order_item oi
          join t_refund x on x.seller_order_id = oi.seller_order_id
         group by oi.seller_order_id) as a on a.seller_order_id = r.seller_order_id;

insert into refund_item (refund_id, order_item_id, quantity, amount, commission_refund, discount_refund)
select rf.refund_id, oi.order_item_id, oi.quantity, oi.line_amount, oi.commission_amount, 0
  from t_refund r
  join refund rf on rf.seller_order_id = r.seller_order_id
  join order_item oi on oi.seller_order_id = r.seller_order_id;

-- ⑨ 부은 주문은 통지가 시작되기 전 것으로 둔다. 거래 통지 스위퍼(`NotificationSweeper`, 기동 3분 뒤)는
-- `notification_template` 의 가장 이른 `created_at` 뒤에 생긴 주문·결제·환불 중 통지가 없는 것에 통지를 쓴다.
-- 틀(`shop`)의 판은 붓기보다 앞이라 그대로 두면 최근 주문 4천여 건에 통지를 쓰면서 재는 창에 쓰기가 든다
-- (2026-10-02 마무리 56차 독립 리뷰가 짚었다 — 통지 4,260 건이 쌓여 있었다). 이 DB 에만 하는 일이다.
update notification_template set created_at = now() + interval '1 second';

-- **커밋 전에 실제 표의 통계를 모은다.** 금액 합·계약서면·재고 행 검사는 지연 트리거라 커밋 순간에 한꺼번에 돌고,
-- 그 안의 질의는 그때의 통계로 계획을 세운다. 틀(`shop`)의 통계는 행이 몇 개뿐이라 순차 스캔을 골라
-- 금액 합 한 번에 18ms, 55만 번이면 몇 시간이 된다(2026-10-02 실측 — 20분에 6만 번). 같은 트랜잭션의 `analyze` 는
-- 아직 커밋 안 된 자기 행을 센다.
analyze shop_order, seller_order, order_item, order_contract_document, sku, sku_stock, payment, refund, refund_item;
