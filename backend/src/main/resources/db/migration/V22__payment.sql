-- 결제. 승인과 거절이 사건으로 쌓인다.
--
-- 담는 것이 결과뿐이다. 카드번호 전체와 유효기간·CVC 는 컬럼이 아예 없다 —
-- 여신전문금융업법 제19조가 가맹점의 카드정보 보관을 금지한다(D2 R18).
-- 모의 결제라서 면제되는 것이 아니라, 지금 담아 두면 진짜 PG 를 붙일 때 스키마를 다시 판다.
--
-- 안 담으면 샐 것도 마스킹을 빠뜨릴 것도 없다. 강제 지점 1위(계약에 칸이 없다)라
-- 앱이 실수해도 들어갈 자리가 없다(D23 축 2).
--
-- append-only 다. updated_at 이 없는 것이 그 뜻이다 — 결제는 상태가 아니라 사건이고,
-- 거절된 결제를 나중에 승인으로 고치는 것이 아니라 새 행이 쌓인다.
-- 환불도 이 표를 안 고친다. refund 테이블이 따로 서고(12a) 상한만 이 행을 본다(money-invariants).
--
-- deleted_at 이 없다. 거래기록 5년 보존(D2 R6)이라 shop_order 와 같은 구조다.

create table payment (
    payment_id bigint not null generated always as identity primary key,

    -- 파기 배치가 5년 지난 주문을 지울 때 같이 사라진다(D13). 결제만 남기면 가리킬 곳을 잃는다.
    -- cascade 를 파기 수단으로 쓰는 것이 아니라, 파기 배치가 지우는 자리에서만 쓰인다(D23).
    order_id bigint not null references shop_order (order_id) on delete cascade,

    -- 승인이냐 거절이냐. 전이가 없어서 상태머신(D7)에 안 들어간다 — 행이 생길 때 정해지고 안 바뀐다.
    status text not null,

    -- 수단 종류만 남긴다. 계좌번호는 카드번호와 같은 이유로 안 담는다.
    method text not null,

    -- PG 에 보낸 금액. shop_order.payable_amount 를 서버가 읽어서 넣는다.
    -- 요청이 금액을 안 받으므로 다른 값이 들어올 경로가 없다(money-invariants).
    amount bigint not null,

    -- PG 가 채번한 승인번호. 우리가 고른 값이 아니라 형식을 좁게 잡지 않는다(D23).
    approval_number text,

    -- 카드사 이름과 마스킹된 뒷 4자리. 이 둘이 R18 이 허용하는 범위의 끝이다.
    card_issuer text,
    card_last4 text,

    -- 거절 사유. PG 가 준 코드를 그대로 둔다.
    decline_reason text,

    created_at timestamptz not null default now(),

    constraint payment_status_check check (status in ('approved', 'failed')),
    constraint payment_method_check check (method in ('card', 'transfer')),
    constraint payment_amount_check check (amount > 0),

    -- 승인번호와 거절 사유는 결과에 딸린 값이다. 둘 다 있거나 둘 다 없는 행이 생기면
    -- "이 결제가 된 건가" 를 status 로 묻는 코드와 컬럼으로 묻는 코드가 다른 답을 낸다.
    constraint payment_approval_number_check
        check ((status = 'approved') = (approval_number is not null)),
    constraint payment_decline_reason_check
        check ((status = 'failed') = (decline_reason is not null)),

    -- 카드로 냈으면 카드사와 뒷 4자리가 있고, 계좌이체면 없다.
    -- 거절된 카드 결제도 카드 정보는 있다 — PG 의 판정을 받은 뒤라 카드가 무엇인지 안다.
    constraint payment_card_issuer_check check ((method = 'card') = (card_issuer is not null)),
    constraint payment_card_last4_check   check ((method = 'card') = (card_last4 is not null)),

    -- 넉 자리 숫자만. 이 컬럼에 카드번호 전체를 넣으려는 코드가 여기서 걸린다(D2 R18).
    constraint payment_card_last4_format_check check (card_last4 ~ '^[0-9]{4}$'),

    constraint payment_approval_number_length_check
        check (length(approval_number) between 1 and 64),
    constraint payment_decline_reason_length_check
        check (length(decline_reason) between 1 and 64)
);

comment on table payment is '결제 결과. 카드번호·유효기간·CVC 는 담지 않는다(D2 R18 여신전문금융업법 제19조)';
comment on column payment.amount is 'PG 에 보낸 금액. shop_order.payable_amount 와 같다';
comment on column payment.card_last4 is '마스킹된 뒷 4자리. R18 이 허용하는 범위의 끝이다';

-- 한 주문에 승인은 하나다. 거절은 여러 번 날 수 있어서 부분 인덱스로 승인만 막는다.
--
-- 앱도 결제 전에 주문 상태를 보지만 그건 강제 지점 3위라 새 입구가 생기면 빠뜨린다(D23 축 2).
-- 여기서 막으면 이중 청구가 구조에서 사라진다.
create unique index payment_approved_unique on payment (order_id) where status = 'approved';

-- 승인번호는 PG 가 유일하게 채번한다. 겹치면 우리가 같은 승인을 두 번 적은 것이다.
create unique index payment_approval_number_unique on payment (approval_number)
 where approval_number is not null;

create index payment_order_idx on payment (order_id, created_at desc);
