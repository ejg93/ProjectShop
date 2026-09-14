-- 미인도·지연인도 손해배상(`43a-4b`, `D2` R38·R39).
--
-- **금액을 우리가 계산하지 않는다.** 소비자분쟁해결기준(공정위 고시) 별표2 「인터넷쇼핑몰업」이
-- 2)·3)·6) 에 「계약해제 및 손해배상」을 정하면서 **액수를 안 정하고**, 소비자기본법 시행령
-- 별표1(일반적 소비자분쟁해결기준)도 환급금액만 정한다(2026-09-14 원문 확인). 게다가
-- 지연인도는 「구매목적을 달성하지 못했나」로 갈리는데 그것은 **소비자의 사정**이다.
--
-- 그래서 이 표가 담는 것은 계산 결과가 아니라 **사람이 내린 판정**이다 —
-- 얼마를, 왜, 누가, 언제 정했나.
--
-- **전자상거래법의 지연배상금과 다른 자리다.** `refund.delay_interest`(`V47`)는 **환급이 늦었을 때**
-- 연 15% 로 붙는 값이라 법이 요율을 줬다(`D2` R21). 여기는 **인도가 늦거나 없었을 때**고
-- 그 요율이 없다. 한 칸에 담으면 **법이 준 값과 사람이 정한 값이 섞인다.**
create table compensation (
    compensation_id bigint not null generated always as identity primary key,

    -- 배상의 단위는 셀러 묶음이다. 미인도·지연인도가 그 묶음에서 일어난다(`D7`).
    seller_order_id bigint not null
        references seller_order (seller_order_id) on delete restrict,

    -- 무엇에 대한 배상인가.
    --
    --   non_delivery   물건이 아예 안 갔다. 고시 2)·6)
    --   late_delivery  늦게 갔다. 고시 3)
    kind text not null,

    -- 누가 무나. **사유로 갈린다**(사용자 결정 2026-09-14) — `R36` 이 반품 배송비에서 같은 모양이다.
    --
    -- 셀러로 고정하면 **우리 결제나 시스템이 멈춰서 발송이 늦은 건까지 셀러가 무는데**,
    -- 그것은 거짓이다. `seller` 인 것만 정산에서 빠진다.
    bearer text not null,

    -- 물어 준 금액. **양수로 담는다** — 정산에 실릴 때 부호를 뒤집는다(`sale_reversal` 과 같다).
    amount bigint not null,

    -- 왜 그 금액인가. **비울 수 없다.** 액수를 법이 안 정해서 근거가 이 글뿐이고,
    -- 분쟁이 오면 이 칸이 우리가 자의로 정하지 않았다는 유일한 증거다.
    reason text not null,

    -- 누가 정했나. **계정이 파기돼도 판정은 남아야 해서** restrict 다(`D13`).
    decided_by_user_id bigint not null references app_user (user_id) on delete restrict,

    -- **이 행 자체가 판정이라** 만들어진 시각이 곧 정해진 시각이다.
    -- 접수와 판정을 가르는 단계가 생기면 그때 칸을 늘린다 — 지금 비워 두면 뜻 없는 칸이 된다.
    decided_at timestamptz not null default now(),

    -- 어느 문의에서 왔나. **없을 수 있다** — 우리가 먼저 알고 무는 경우가 있다.
    -- 문의가 파기돼도 배상 기록은 남는다(`D13`).
    inquiry_id bigint references inquiry (inquiry_id) on delete set null,

    constraint compensation_kind_check
        check (kind in ('non_delivery', 'late_delivery')),

    constraint compensation_bearer_check
        check (bearer in ('seller', 'platform')),

    -- 0 원 배상은 배상이 아니라 「안 물어 준 것」이다. 그 사실은 행이 없는 것으로 나타난다.
    constraint compensation_amount_check check (amount > 0),

    constraint compensation_reason_length_check
        check (length(btrim(reason)) between 1 and 2000)
);

create index compensation_seller_order_idx on compensation (seller_order_id);

comment on table compensation is
    '미인도·지연인도 손해배상 판정. 액수를 법이 안 정해서 사람이 정한 값이다(D2 R38·R39, 43a-4b)';
comment on column compensation.reason is
    '왜 그 금액인가. 법이 요율을 안 줘서 이 글이 유일한 근거다';
comment on column compensation.bearer is
    '누가 무나. seller 인 것만 정산에서 빠진다 — 우리 귀책까지 셀러에게 물리면 거짓이 된다';

-- 정산에 싣는 자리.
--
-- **셀러가 무는 것만 실린다.** `platform` 인 배상은 우리가 낸 돈이라 셀러 정산과 무관하다.
alter table settlement_item
    add column compensation_id bigint references compensation (compensation_id) on delete restrict;

comment on column settlement_item.compensation_id is
    '손해배상 판정의 근거. kind = compensation 인 줄에만 찬다(43a-4b)';

alter table settlement_item drop constraint settlement_item_kind_check;
alter table settlement_item add constraint settlement_item_kind_check
    check (kind in ('sale', 'shipping_fee', 'commission',
                    'sale_reversal', 'commission_reversal', 'carryover', 'compensation'));

-- 배상은 셀러 몫에서 빠지는 것이라 음수다.
alter table settlement_item drop constraint settlement_item_amount_sign_check;
alter table settlement_item add constraint settlement_item_amount_sign_check
    check ((kind in ('sale', 'shipping_fee', 'commission_reversal') and amount > 0)
           or (kind in ('commission', 'sale_reversal', 'carryover', 'compensation')
               and amount < 0));

-- 종류가 근거를 정한다. **기존 갈래에도 `compensation_id is null` 을 더한다** —
-- 안 더하면 판매 줄에 배상 근거가 붙어도 아무것도 안 막는다.
alter table settlement_item drop constraint settlement_item_source_check;
alter table settlement_item add constraint settlement_item_source_check
    check (
        (kind in ('sale', 'commission')
             and order_item_id is not null and seller_order_id is null
             and refund_item_id is null and carried_from_settlement_id is null
             and compensation_id is null)
        or (kind = 'shipping_fee'
             and seller_order_id is not null and order_item_id is null
             and refund_item_id is null and carried_from_settlement_id is null
             and compensation_id is null)
        or (kind in ('sale_reversal', 'commission_reversal')
             and refund_item_id is not null and order_item_id is null
             and seller_order_id is null and carried_from_settlement_id is null
             and compensation_id is null)
        or (kind = 'carryover'
             and carried_from_settlement_id is not null and order_item_id is null
             and seller_order_id is null and refund_item_id is null
             and compensation_id is null)
        or (kind = 'compensation'
             and compensation_id is not null and order_item_id is null
             and seller_order_id is null and refund_item_id is null
             and carried_from_settlement_id is null)
    );

-- **배상 하나는 평생 한 번만 실린다.** 두 번 실리면 셀러가 같은 배상을 두 번 문다 —
-- (셀러, 주기) 유니크는 다른 주기로 새는 것을 안 막는다(`V52` 가 같은 판단을 했다).
create unique index settlement_item_compensation_unique
    on settlement_item (compensation_id) where compensation_id is not null;

-- **공급자 칸은 그대로 둔다.** `supplier` 는 종류에서 뽑는 생성 컬럼인데(`V52`), 배상은
-- 어느 갈래에도 안 걸려서 비어 있다 — **손해배상금은 재화나 용역의 공급이 아니라
-- 부가가치세 과세 대상이 아니고**(`D2` R17 이 부르는 세금계산서의 대상이 아니다),
-- 이월 줄이 같은 이유로 이미 비어 있다. 안 고치는 것이 답이라 이 주석이 그 근거다.
