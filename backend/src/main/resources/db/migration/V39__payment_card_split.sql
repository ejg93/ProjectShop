-- 카드 정보를 결제 기록에서 갈라낸다(`D2` R9, 개인정보보호법 제21조제3항).
--
-- 제21조제3항은 **파기하지 않고 보존하는 개인정보를 다른 개인정보와 분리해 저장·관리**하라고 한다.
-- 우리 표에서 5년을 사는 개인정보는 이 둘 하나뿐이었다 — 주문·주문항목은 금액·상품명이라
-- 거래 사실이고, 사람은 `user_id` 연결뿐이며 그 계정의 식별 정보는 탈퇴 5일 뒤에 비워진다(`5i`).
--
-- **분리를 표를 가르는 것으로 한다.** 배송지를 `order_shipping` 으로 뺀 것과 같은 수단이고
-- (청크 10-1), 그 결과가 파기 축에서 이미 값을 했다 — 주문은 5년 남고 주소는 6개월에 사라진다.
-- 아카이브 스키마로 옮기는 방법을 저울질했는데, 조회 경로가 둘이 되고 참조 무결성이 끊긴다.
--
-- **수명을 배송지와 맞춘다** — 거래 종료 + 6개월(`D13`). 카드 정보의 쓸모는 「어느 카드로 냈나」고
-- 그 물음은 분쟁과 함께 온다. 6개월이 지나도 대금결제 기록 자체는 남는다:
-- 금액·승인번호·수단(`card`)·시각이 `payment` 에 그대로 있어서 제6조제1항이 요구하는 것은 안 잃는다.
--
-- **「카드 결제면 카드 정보가 있다」를 제약으로 못 든다.** 파기가 그 불변식을 깨는 것이 정상이라
-- (6개월 지난 카드 결제는 이 표에 행이 없다) 트리거로 걸면 파기가 막힌다.
-- 만들 때 채우는 것은 `PaymentService` 가 지고 테스트가 고정한다.

create table payment_card (
    payment_id bigint not null primary key
        references payment (payment_id) on delete cascade,

    -- PG 가 준 발급사 이름. 마스킹된 뒷 4자리와 함께 「어느 카드로 냈나」에 답한다.
    card_issuer text not null,
    card_last4  text not null,

    created_at timestamptz not null default now(),

    constraint payment_card_last4_format_check check (card_last4 ~ '^[0-9]{4}$'),
    constraint payment_card_issuer_length_check check (length(card_issuer) between 1 and 50)
);

comment on table payment_card is '결제의 카드 정보. 보존분 분리라 결제에서 갈랐다(D2 R9, D13). 거래 종료 + 6개월에 10a 가 지운다';

insert into payment_card (payment_id, card_issuer, card_last4, created_at)
select payment_id, card_issuer, card_last4, created_at
  from payment
 where card_issuer is not null;

alter table payment
    drop constraint payment_card_issuer_check,
    drop constraint payment_card_last4_check,
    drop constraint payment_card_last4_format_check,
    drop column card_issuer,
    drop column card_last4;
