-- 청약철회 제한을 주문 항목에 박제한다(Q5, D2 R4).
--
-- 지금은 반품 판정이 product 의 **현재** 값을 읽는다(OrderStatusService.requireNoRestrictedItem).
-- 두 가지가 걸린다.
--
-- 1. 셀러가 나중에 제한을 켜면 **지나간 주문까지 막힌다.** 가격·수수료율·리드타임을 전부
--    주문 시점에 박제해 온 이유가 그것인데(D10) 이 값만 안 했다.
--
-- 2. **상품 속성 하나로는 제한이 성립하지 않는다.** 제17조제2항의 사유마다 성립 조건이 다르다.
--
--    복제 가능 매체(4호)  포장을 **훼손한** 경우
--    용역·디지털콘텐츠(5호) 제공이 **개시된** 경우
--    주문제작(시행령 제21조) 거래마다 **별도 고지 + 소비자의 서면 동의**
--
--    「이 상품은 그 사유에 해당할 수 있다」와 「이 거래에서 그 조건이 찼다」는 다른 사실이다.
--
--
-- 그래서 여기 박제하는 것은 상품 속성이 아니라 **이 거래에서 성립한 제한**이다.
--
--   digital_content  배송완료에서만 반품 접수가 열려서(OrderStatusPolicy) 공급이 전제다.
--                    주문 시점에 성립한 것으로 본다
--   made_to_order    시행령 제21조가 요구하는 동의를 받았을 때만 성립한다.
--                    안 받았으면 제한이 없는 주문이다
--   copyable_media   **주문 시점에는 성립할 수 없다.** 포장 훼손은 물건이 돌아와야 아는 사실이고
--                    제17조제5항이 그 입증을 우리에게 지웠다. 접수를 막는 근거가 못 되고,
--                    실제 판단은 반품 검수 축(43·44)이 한다. 그래서 값에서 뺀다


alter table order_item add column withdrawal_restriction_reason text;

comment on column order_item.withdrawal_restriction_reason is
    '이 거래에서 성립한 청약철회 제한 사유. null 이면 제한이 없다(D2 R4, 전자상거래법 제17조제2항)';

-- 동의를 받은 시각. 값이 아니라 시각인 이유는 **언제 받았는지가 입증 자료**라서다 —
-- 동의 이력(user_consent)이 acted_at 을 남기는 것과 같다(D2 R11).
alter table order_item add column withdrawal_restriction_agreed_at timestamptz;

comment on column order_item.withdrawal_restriction_agreed_at is
    '주문제작 상품의 청약철회 제한에 동의받은 시각(전자상거래법 시행령 제21조)';

-- 값을 닫는다(D23 「법이 인정한 목록은 닫는다」).
alter table order_item add constraint order_item_withdrawal_reason_check
    check (withdrawal_restriction_reason is null
           or withdrawal_restriction_reason in ('digital_content', 'made_to_order'));

-- 동의 없는 주문제작 제한을 막는다.
--
-- **이것이 시행령 제21조를 구조로 옮긴 자리다.** 앱이 동의를 안 받고 제한만 박으려 하면
-- 여기서 걸린다 — 강제 지점을 앱 검증(3위)에서 DB 제약(2위)으로 내렸다(D23 축 2).
alter table order_item add constraint order_item_made_to_order_agreement_check
    check (withdrawal_restriction_reason <> 'made_to_order'
           or withdrawal_restriction_agreed_at is not null);

-- 제한이 없는데 동의 시각만 남는 것도 막는다. 그런 행은 「동의를 받았는데 안 걸었다」는 뜻이라
-- 무엇이 사실인지 읽는 사람마다 달라진다.
alter table order_item add constraint order_item_agreement_without_reason_check
    check (withdrawal_restriction_agreed_at is null
           or withdrawal_restriction_reason is not null);
