-- 정산 스키마(청크 17). 표 셋이다 — 주기·명세·항목.
--
-- `17-1` 이 `money-invariants.md` 에 등식 다섯을 적어 두고 강제는 이 청크로 미뤘다.
-- 여기가 그 자리다. **적어 두는 것과 거는 것을 가른다** 는 그 문서의 말이 이 파일에서 끝난다.
--
-- 알갱이는 **주문 항목 건별**이다(사용자 선택). 종류별 합계 네 줄로 두면 정산서 안에 근거가
-- 없어서 「이 수수료 합이 어느 주문에서 나왔나」에 정산서가 답을 못 한다 — 그때 주문 표를
-- 다시 더하면 그 계산이 마감 때와 같다는 보장이 없다(박제가 안 된다).


-- 1. 주기. 월 하나에 한 행이고 셀러와 무관하다.
--
-- **셀러별로 안 판다.** 주기는 「전달 1일~말일을 이번 달 10일에 준다」는 달력이라
-- 셀러마다 다를 것이 없다. 셀러가 붙는 것은 아래 명세다.
create table settlement_cycle (
    settlement_cycle_id bigint not null generated always as identity primary key,

    -- 대상 기간. KST 기준 전달 1일~말일이다(D10).
    --
    -- **date 다.** 경계가 자정이라 시각을 담을 것이 없고, timestamptz 로 두면 「말일 24시」를
    -- 어느 쪽 끝으로 볼지가 읽는 자리마다 갈린다.
    period_start date not null,
    period_end   date not null,

    -- 지급 예정일. 매월 10일이되 쉬는 날이면 다음 영업일이다(D10).
    --
    -- **박제한다.** 휴일표(V19)가 나중에 임시공휴일을 받으면 지나간 주기의 지급일까지
    -- 흔들린다 — seller_order.ship_due_at·refund.due_at 과 같은 판단이다.
    payout_date date not null,

    -- 마감한 시각. 마감 배치가 채운다(청크 19). 비어 있으면 아직 안 닫힌 주기다.
    closed_at timestamptz,

    created_at timestamptz not null default now(),

    -- 같은 달을 두 번 만들면 그 달이 두 번 정산된다.
    constraint settlement_cycle_period_unique unique (period_start),

    constraint settlement_cycle_period_check check (period_end >= period_start),

    -- 지급일이 대상 기간 안에 있으면 아직 안 끝난 거래를 지급하게 된다.
    constraint settlement_cycle_payout_date_check check (payout_date > period_end)
);

comment on table settlement_cycle is
    '정산 주기. 전달 1일~말일 KST 를 대상으로 하고 지급일을 박제한다(D3, 청크 17)';


-- 2. 명세. 셀러 하나의 그 주기 정산서다.
create table settlement (
    settlement_id bigint not null generated always as identity primary key,

    settlement_cycle_id bigint not null
        references settlement_cycle (settlement_cycle_id) on delete restrict,
    seller_id bigint not null references seller (seller_id) on delete restrict,

    -- 이번에 지급할 금액. **음수일 수 있다**(money-rules.md).
    --
    -- 정산 후 환불이 나면 이미 준 돈을 회수하는데, 회수액이 이번 달 판매를 넘으면 음수가 된다.
    -- 아래 항목 합과 같아야 하고 그것은 지연 트리거가 본다.
    payout_amount bigint not null,

    -- 다음 주기로 넘기는 음수 잔액. 안 넘기면 0 이다.
    --
    -- **한 행 안에서 결정된다** — 지급액이 음수면 그 값이 그대로 이월이다.
    -- money-invariants.md 는 이것을 테스트로 적어 뒀는데 check 로 내려간다(D23 축 2).
    carried_over bigint not null default 0,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    -- **두 번 돌면 지급이 두 배가 되는 사고는 합계 불일치로 안 잡힌다** — 두 번째 정산서는
    -- 그것대로 합이 맞는다. 그래서 유일성이 등식과 별개로 필요하다(money-invariants.md).
    constraint settlement_cycle_seller_unique unique (settlement_cycle_id, seller_id),

    constraint settlement_carried_over_check
        check (carried_over = least(0, payout_amount))
);

create index settlement_seller_idx on settlement (seller_id);

comment on table settlement is
    '셀러 하나의 그 주기 정산서. 지급액과 다음으로 넘길 음수 잔액을 담는다(청크 17)';

comment on column settlement.payout_amount is
    '이번에 지급할 금액. 항목 합과 같다(지연 트리거). 회수가 판매를 넘으면 음수다';


-- 3. 항목. 정산서 한 줄이고 근거를 가리킨다.
create table settlement_item (
    settlement_item_id bigint not null generated always as identity primary key,

    settlement_id bigint not null references settlement (settlement_id) on delete cascade,

    -- 무엇으로 붙은 줄인가. **이 값이 공급자와 부호를 둘 다 결정한다.**
    --
    -- 되돌림을 별도 종류로 둔다. 원래 줄의 부호만 뒤집으면 「이번 달 판매가 얼마였나」와
    -- 「지난 달 것을 얼마나 물렸나」가 한 종류 안에서 안 갈린다.
    kind text not null,

    -- 부호가 있는 금액. 합이 곧 지급액이다.
    amount bigint not null,

    -- 근거. 종류마다 채우는 칸이 다르고 아래 check 가 그 짝을 강제한다.
    --
    -- **다형 참조를 한 칸에 안 담는다** — 대상 표 이름을 문자열로 들고 있으면 외래키를 못 걸고,
    -- 못 걸면 지워진 행을 가리키는 줄이 정산서에 남는다.
    order_item_id bigint references order_item (order_item_id) on delete restrict,
    seller_order_id bigint references seller_order (seller_order_id) on delete restrict,
    refund_item_id bigint references refund_item (refund_item_id) on delete restrict,
    carried_from_settlement_id bigint references settlement (settlement_id) on delete restrict,

    -- 부가가치세법이 요구하는 공급자다(D2 R17).
    --
    -- 상품 대금과 배송비는 **셀러가 고객에게** 공급한 것이고, 중개수수료는
    -- **플랫폼이 셀러에게** 공급한 것이다. 한 줄로 뭉치면 세금계산서를 못 가른다.
    --
    -- **받지 않고 만든다.** money-invariants.md 가 「공급자 컬럼을 따로 받아서 채우면
    -- 종류와 어긋난 행이 생긴다」고 적어 뒀는데, 생성 열이면 어긋날 방법 자체가 없다 —
    -- check 로 짝을 검사하는 것보다 한 층 위다(D23 축 2 의 1위, 구조).
    --
    -- 이월은 공급이 아니라 정산끼리의 조정이라 공급자가 없다.
    supplier text generated always as (
        case
            when kind in ('sale', 'shipping_fee', 'sale_reversal') then 'seller'
            when kind in ('commission', 'commission_reversal')     then 'platform'
        end
    ) stored,

    created_at timestamptz not null default now(),

    constraint settlement_item_kind_check
        check (kind in ('sale', 'shipping_fee', 'commission',
                        'sale_reversal', 'commission_reversal', 'carryover')),

    -- 부호를 종류가 정한다.
    --
    -- 셀러에게 주는 것이 양수, 우리가 떼거나 물리는 것이 음수다. 수수료를 양수로 담고
    -- 뺄셈을 앱이 하면 「합이 곧 지급액」이 성립을 안 하고, 그 순간 지연 트리거가 못 막는다.
    --
    -- 0 을 안 받는다. 금액이 0 인 줄은 정산서를 늘리기만 하고 아무것도 안 말한다.
    constraint settlement_item_amount_sign_check
        check ((kind in ('sale', 'shipping_fee', 'commission_reversal') and amount > 0)
               or (kind in ('commission', 'sale_reversal', 'carryover') and amount < 0)),

    -- 종류가 근거를 정한다.
    --
    -- 배송비만 seller_order 를 가리키는 이유는 그 값이 묶음 단위여서다 — 항목별로 가를
    -- 근거가 없다(business-model.md). 판매·수수료는 항목에서 나오고 그 항목이 묶음을 안다.
    constraint settlement_item_source_check
        check (
            (kind in ('sale', 'commission')
                 and order_item_id is not null and seller_order_id is null
                 and refund_item_id is null and carried_from_settlement_id is null)
            or (kind = 'shipping_fee'
                 and seller_order_id is not null and order_item_id is null
                 and refund_item_id is null and carried_from_settlement_id is null)
            or (kind in ('sale_reversal', 'commission_reversal')
                 and refund_item_id is not null and order_item_id is null
                 and seller_order_id is null and carried_from_settlement_id is null)
            or (kind = 'carryover'
                 and carried_from_settlement_id is not null and order_item_id is null
                 and seller_order_id is null and refund_item_id is null)
        )
);

create index settlement_item_settlement_idx on settlement_item (settlement_id);

-- **근거 하나는 평생 한 번만 실린다.**
--
-- 유일성을 정산서 안이 아니라 **전역**으로 건다. 같은 주문 항목이 두 정산서에 실리면
-- 각 정산서는 그것대로 합이 맞고 셀러는 돈을 두 번 받는다 — (셀러, 주기) 유니크는
-- 그 사고를 못 잡는다. 같은 주기를 두 번 마감하는 것만 막지 다른 주기로 새는 것은 안 막는다.
--
-- **종류를 키에 넣는다.** 한 주문 항목이 판매 한 줄과 수수료 한 줄을 낸다.
create unique index settlement_item_order_item_unique
    on settlement_item (kind, order_item_id) where order_item_id is not null;

create unique index settlement_item_seller_order_unique
    on settlement_item (kind, seller_order_id) where seller_order_id is not null;

create unique index settlement_item_refund_item_unique
    on settlement_item (kind, refund_item_id) where refund_item_id is not null;

-- 한 정산서의 음수 잔액은 한 번만 넘어간다. 두 번 물리면 셀러가 같은 빚을 두 번 갚는다.
create unique index settlement_item_carryover_unique
    on settlement_item (carried_from_settlement_id) where carried_from_settlement_id is not null;

comment on table settlement_item is
    '정산서 한 줄. 주문 항목 건별이고 종류가 공급자와 부호를 정한다(D2 R17, 청크 17)';

comment on column settlement_item.supplier is
    '부가가치세법상 공급자. kind 에서 생성되므로 어긋날 수 없다. 이월은 공급이 아니라 비어 있다';


-- 지급액이 항목 합과 같다.
--
-- **지연 트리거인 이유는 순서다.** settlement 가 먼저 들어가고 항목이 뒤에 붙는데
-- 참조 방향이 그 반대라 순서를 못 바꾼다 — assert_order_amounts 와 같은 자리다.
create or replace function assert_settlement_amounts(p_settlement_id bigint)
    returns void language plpgsql as $$
declare
    v_payout bigint;
    v_item_sum bigint;
    v_items int;
begin
    select payout_amount into v_payout
      from settlement where settlement_id = p_settlement_id;

    -- 정산서가 이미 지워졌다. 검사할 것이 없다.
    if not found then
        return;
    end if;

    select coalesce(sum(amount), 0), count(*)
      into v_item_sum, v_items
      from settlement_item where settlement_id = p_settlement_id;

    -- 줄이 없는 정산서는 지급액이 0 이어도 안 된다. 대상이 없으면 정산서를 안 만든다 —
    -- 빈 정산서가 서면 「이 달에 거래가 없었다」와 「마감이 덜 돌았다」가 안 갈린다.
    if v_items = 0 then
        raise exception '줄이 없는 정산서다 (settlement_id=%)', p_settlement_id;
    end if;

    if v_payout <> v_item_sum then
        raise exception '지급액이 항목 합과 다르다 (settlement_id=%, 저장=%, 항목합=%)',
            p_settlement_id, v_payout, v_item_sum;
    end if;
end;
$$;

create or replace function check_amounts_on_settlement() returns trigger
language plpgsql as $$
begin
    perform assert_settlement_amounts(new.settlement_id);
    return null;
end;
$$;

create or replace function check_amounts_on_settlement_item() returns trigger
language plpgsql as $$
begin
    if tg_op <> 'INSERT' then
        perform assert_settlement_amounts(old.settlement_id);
    end if;
    if tg_op <> 'DELETE' then
        perform assert_settlement_amounts(new.settlement_id);
    end if;
    return null;
end;
$$;

-- 둘 다에 건다. 항목만 보면 항목을 안 건드리고 지급액만 고치는 경로가 빠져나간다.
create constraint trigger settlement_amounts_check
    after insert or update on settlement
    deferrable initially deferred
    for each row execute function check_amounts_on_settlement();

create constraint trigger settlement_item_amounts_check
    after insert or update or delete on settlement_item
    deferrable initially deferred
    for each row execute function check_amounts_on_settlement_item();
