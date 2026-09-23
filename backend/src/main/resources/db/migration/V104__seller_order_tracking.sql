-- 발송에 택배사와 송장 번호를 붙인다(`57`).
--
-- **위치를 안 다룬다**(사용자 선택 2026-09-23). 기사·화물의 실시간 위치는 개인위치정보라 위치정보법이 걸리고
-- (`D2`), 사업 신고와 동의 절차가 따라온다. 그래서 **위치 칸을 아예 안 만든다** — 칸이 없으면 누가 채울 수도 없다.
-- 송장 번호는 위치가 아니다: 택배사에 조회를 맡기는 열쇠고, 사는 사람은 그 번호로 택배사 화면에서 따라간다.
--
-- **표를 따로 안 판다.** 묶음 하나에 발송은 한 번이고(`D7` — 부분 발송이 없다) 발송 시각(`shipped_at`)이 이미 이
-- 표에 있다. 1:1 확장 표를 세우면 「발송했는데 송장 행이 없다」를 또 막아야 한다.
alter table seller_order
    add column carrier_code text,
    add column tracking_no text;

-- 택배사 목록은 앱의 `Carrier` 와 짝이다(`EnumConstraintTest`).
alter table seller_order add constraint seller_order_carrier_code_check
    check (carrier_code in ('cj', 'hanjin', 'lotte', 'epost', 'logen'));

-- 숫자 10~14자리. 택배사마다 10(CJ·한진)·11(로젠)·12(롯데)·13(우체국)자리라 그 범위를 덮는다.
-- 하이픈은 앱이 떼고 넣는다 — 저장값이 한 모양이어야 번호로 찾을 수 있다.
alter table seller_order add constraint seller_order_tracking_no_check
    check (tracking_no ~ '^[0-9]{10,14}$');

-- 둘은 같이 있거나 같이 없다. 택배사 없는 번호는 어디에 물어볼지 모르고, 번호 없는 택배사는 따라갈 수 없다.
alter table seller_order add constraint seller_order_tracking_pair_check
    check ((carrier_code is null) = (tracking_no is null));

-- 보내기 전에는 송장이 없다. 발송 입구가 전이와 같은 트랜잭션에서 `shipped_at` 을 박은 뒤에 적는다.
--
-- **「발송했으면 송장이 있다」는 안 건다.** 이 칸이 생기기 전에 보낸 묶음과 관리자 강제 전이(`16c`)가 송장 없이
-- 발송 상태로 간다. 발송 입구가 송장을 필수로 받는 것이 그 자리를 지킨다(`ShipmentController`).
alter table seller_order add constraint seller_order_tracking_after_ship_check
    check (tracking_no is null or shipped_at is not null);

comment on column seller_order.carrier_code is '택배사(57). 발송 때 셀러가 고른다. 위치 칸은 일부러 없다(위치정보법)';
comment on column seller_order.tracking_no is '송장 번호(57). 숫자만. 택배사 조회의 열쇠다';

-- 셀러 화면이 읽는 뷰에 두 칸을 더한다. **뷰는 만들 때 컬럼을 굳힌다**(`11-6`, `Q2` 가 대조로 막는다).
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
       so.carrier_code,
       so.tracking_no,
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
