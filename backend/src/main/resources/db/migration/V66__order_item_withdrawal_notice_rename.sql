-- 32자짜리 컬럼 이름을 상한 안으로 줄인다(Q40, naming-rules.md 「SQL › 공통」 — 30자 이하).
--
-- `SchemaNamingTest`(Q31)가 찾았다. 마이그레이션을 글자로 재던 측정은 「위반 0」이라고 했는데,
-- 도는 스키마에 물으니 `order_item.withdrawal_restriction_agreed_at` 이 32자였다.
--
-- **새 이름을 「고지」로 잡은 이유**: 시행령 제21조가 요구하는 것은 제한 사실의 **고지와 동의**고,
-- 저장소가 이미 그 개념을 고지라고 부른다(`WithdrawalNoticeScreenTest`). 줄이면서 뜻이 안 빠진다.
-- `withdrawal_agreed_at` 은 「무엇에 동의했나」가 사라지고, `wd_` 같은 축약은 D22 가 금지한다.
--
-- **제약은 손대지 않는다.** Postgres 가 rename 을 따라가며 check 식을 같이 고친다 —
-- 세 제약(`order_item_withdrawal_reason_check`·`order_item_made_to_order_agreement_check`·
-- `order_item_agreement_without_reason_check`)의 뜻이 그대로 남는다.
alter table order_item rename column withdrawal_restriction_agreed_at to withdrawal_notice_agreed_at;

comment on column order_item.withdrawal_notice_agreed_at is
    '주문제작 상품의 청약철회 제한을 고지하고 동의받은 시각(전자상거래법 시행령 제21조)';
