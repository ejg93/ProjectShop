-- 환불. 요청과 승인을 가른다.
--
-- 돈이 들어온 단위와 나갈 단위가 다르다. 결제는 shop_order 하나에 승인 하나인데
-- (V22 payment_approved_unique) 취소·반품은 seller_order 층에서 일어난다(D7).
-- 셀러가 둘인 주문은 한쪽만 취소될 수 있고 결제는 하나다 — 이 표가 그 둘을 잇는다.
-- 그래서 환불은 seller_order 를 가리키고, 상한만 결제를 본다(money-invariants).
--
-- payment_id 를 안 담는다. seller_order → shop_order → payment 로 갈 수 있고
-- 승인은 주문마다 하나뿐이라(payment_approved_unique) 그 경로가 언제나 한 행에 닿는다.
-- 담으면 같은 사실이 두 곳에 있고, 둘이 어긋난 행을 막을 자리가 또 필요해진다.
--
-- deleted_at 이 없다. 거래기록 5년 보존이라 shop_order·payment 와 같은 구조다(D2 R6).

create table refund (
    refund_id bigint not null generated always as identity primary key,

    -- 노출 번호를 만든 이유가 셋이다(D9, CLAUDE.md 「확장성을 재는 방법」).
    --   1. 승인 워크플로가 요청을 가리킨다 — 12a-2 의 경로가 이 값을 쓴다
    --   2. 정산이 차감한 환불을 가리킨다(청크 19·21)
    --   3. PG 에 넘길 멱등키가 된다 — 아래 gateway_refund_number 주석을 본다
    refund_number text not null,

    seller_order_id bigint not null
        references seller_order (seller_order_id) on delete cascade,

    -- requested → approved 또는 rejected. 둘 다 종점이다.
    --
    -- PG 환불이 실패한 상태가 없다. 실패하면 요청이 requested 로 남고 다시 승인하면 된다 —
    -- failed 를 두면 "재시도할 수 있는 종점" 이라는 모순된 상태가 생기고,
    -- 그 상태에서 기한(due_at)이 계속 흐른다는 사실이 상태 이름에 안 드러난다.
    status text not null default 'requested',

    -- 왜 돌려주나. 자유 텍스트로 두면 정산이 사유별로 집계를 못 한다(D23 「열거값」).
    --
    -- payment_error 는 12-1 이 주석으로 남긴 구멍이다 — 결제 조회와 기록 사이에
    -- 만료 배치가 끼면 PG 에는 승인이 남고 우리 주문은 취소된다. 되돌릴 자리가 여기다.
    reason_code text not null,

    -- 고객에게 돌아가는 돈. 항목 대금 합 + 배송비 환불액이다.
    -- 수수료는 여기 안 들어간다 — 우리가 셀러에게서 덜 떼는 것이지 고객 돈이 아니다.
    amount bigint not null,

    -- 배송비를 따로 든다. seller_order 단위라 항목별로 못 나눈다 —
    -- 그 묶음의 항목이 전부 환불될 때만 값이 차고, 부분 환불이면 0 이다.
    --
    -- 전자상거래법 제18조는 반환 비용 부담을 사유로 가른다(단순 변심이면 소비자, 하자면 사업자).
    -- 지금 접수가 사유를 안 받아서 전부 단순 변심으로 본다(OrderStatusService 의 같은 주석).
    -- 하자 반품이 붙는 청크는 이 컬럼에 값을 넣을 뿐 구조를 안 바꾼다.
    shipping_fee_refund bigint not null default 0,

    -- 낸 사람과 처리한 사람. 둘을 갈라 두는 것이 이 표의 존재 이유다.
    requested_by_user_id bigint not null references app_user (user_id) on delete restrict,
    request_reason text,

    approved_by_user_id bigint references app_user (user_id) on delete restrict,
    decision_reason text,
    decided_at timestamptz,

    -- 환급 기한. 요청일 다음날부터 3영업일째 24시다(D2 R5, 전자상거래법 제18조제2항).
    --
    -- 박제한다. 승인 때 다시 계산하면 그 사이 임시공휴일이 추가됐을 때 지나간 요청의
    -- 기한까지 흔들린다(D10) — seller_order.withdrawal_expire_at 과 같은 이유다.
    --
    -- "기한 안에 승인" 을 check 로 못 건다. 늦은 환불도 되기는 해야 해서다 —
    -- 막으면 기한을 넘긴 돈이 영영 안 나간다. 강제는 「넘긴 것이 조회로 드러난다」까지가 끝이고
    -- 그 위로는 못 올라간다(D23 축 2 에서 이 요건이 닿는 가장 낮은 층이다).
    due_at timestamptz not null,

    -- PG 가 채번한 환불 거래번호. 승인번호와 같은 이유로 형식을 안 좁힌다(D23).
    gateway_refund_number text,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint refund_number_unique unique (refund_number),

    -- R-20260819-K3M9P7. 난수 집합은 주문번호와 같다 — 0·O·1·I 를 뺀 32자(D9).
    constraint refund_number_format_check
        check (refund_number ~ '^R-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$'),

    constraint refund_status_check
        check (status in ('requested', 'approved', 'rejected')),

    constraint refund_reason_code_check
        check (reason_code in ('cancelled', 'withdrawal', 'payment_error')),

    constraint refund_amount_check              check (amount > 0),
    constraint refund_shipping_fee_refund_check check (shipping_fee_refund >= 0),

    -- 처리된 요청에는 처리자와 시각이 있고, 안 처리된 요청에는 없다.
    -- 셋을 따로 두면 "승인인데 누가 했는지 없는" 행이 생기고, 그 행은 자기승인 검사도 빠져나간다.
    constraint refund_decision_check
        check ((status = 'requested')
               = (approved_by_user_id is null and decided_at is null)),

    -- 반려에는 사유가 있어야 한다. 승인은 없어도 된다 —
    -- 반려는 돈이 안 나가는 결정이라 왜 그랬는지가 없으면 고객에게 답할 말이 없다.
    constraint refund_rejection_reason_check
        check ((status <> 'rejected') or (decision_reason is not null)),

    -- 자기가 낸 요청은 자기가 승인 못 한다.
    --
    -- 앱에도 같은 검사가 있지만 그건 강제 지점 3위라 새 입구가 생기면 빠뜨린다(D23 축 2).
    -- 여기 있으면 psql 로 넣어도 걸린다. 앱 쪽은 422 로 이유를 주는 몫이다(7c 와 같은 두 겹).
    constraint refund_self_approval_check
        check (approved_by_user_id is null
               or approved_by_user_id <> requested_by_user_id),

    -- 승인된 환불에는 PG 거래번호가 있고 그 밖에는 없다.
    -- payment_approval_number_check 와 같은 모양이다 — 결과에 딸린 값이 결과와 어긋나면
    -- "이 환불이 나갔나" 를 status 로 묻는 코드와 컬럼으로 묻는 코드가 다른 답을 낸다.
    constraint refund_gateway_refund_number_check
        check ((status = 'approved') = (gateway_refund_number is not null)),

    constraint refund_request_reason_length_check
        check (length(request_reason) between 1 and 500),
    constraint refund_decision_reason_length_check
        check (length(decision_reason) between 1 and 500),
    constraint refund_gateway_refund_number_length_check
        check (length(gateway_refund_number) between 1 and 64)
);

comment on table refund is '환불 요청과 승인. 요청자와 승인자가 달라야 한다(12a)';
comment on column refund.amount is '고객에게 돌아가는 돈. 수수료는 안 들어간다';
comment on column refund.due_at is '환급 기한. 요청일 다음날부터 3영업일째 24시(D2 R5)';

-- PG 거래번호는 결제사가 유일하게 채번한다. 겹치면 우리가 같은 환불을 두 번 적은 것이다.
create unique index refund_gateway_refund_number_unique on refund (gateway_refund_number)
 where gateway_refund_number is not null;

create index refund_seller_order_idx on refund (seller_order_id, created_at desc);

-- 기한을 넘긴 미처리 요청을 찾는 자리다(D2 R5). 이 인덱스가 없으면 그 조회가 전체를 훑는다.
create index refund_pending_due_idx on refund (due_at) where status = 'requested';


-- 환불 항목. 어느 주문 항목을 몇 개 돌려주나.
--
-- refund 가 seller_order 단위인데 이 표가 있는 이유는 부분 환불이다.
-- 수량 3개 중 1개만 반품하면 대금도 수수료도 그 비율로 갈라야 하고,
-- 그 값을 저장 안 하면 정산이 「이 항목에서 수수료를 얼마 포기했나」를 못 센다.
create table refund_item (
    refund_item_id bigint not null generated always as identity primary key,

    refund_id     bigint not null references refund (refund_id) on delete cascade,
    order_item_id bigint not null references order_item (order_item_id) on delete cascade,

    quantity int not null,

    -- 돌려줄 대금. 단가 × 수량이라 나눗셈이 없다 — 절사가 안 생긴다.
    amount bigint not null,

    -- 이 항목에서 우리가 포기하는 수수료. 정산이 읽는다(청크 19).
    --
    -- 여기는 나눗셈이 있다. commission_amount 가 항목 단위로 이미 절사된 값이라(D8)
    -- 수량으로 또 나누면 잔액이 뜨는데, 마지막 남은 수량을 환불할 때 그 잔액을 전부 실어서 닫는다
    -- (money-invariants 「통째로 환불하면 commission_refund = commission_amount」).
    -- 그 규칙은 앱에 있고 여기는 상한만 본다 — 나눠 담는 방식은 여러 가지고 상한은 하나다.
    commission_refund bigint not null,

    created_at timestamptz not null default now(),

    -- 한 환불에 같은 항목이 두 줄이면 상한 검사는 통과하는데 합계가 두 번 더해진다.
    constraint refund_item_unique unique (refund_id, order_item_id),

    constraint refund_item_quantity_check          check (quantity > 0),
    constraint refund_item_amount_check            check (amount > 0),
    constraint refund_item_commission_refund_check check (commission_refund >= 0)
);

comment on table refund_item is '환불에 담긴 주문 항목과 수량. 부분 환불을 표현한다';

create index refund_item_refund_idx on refund_item (refund_id);
create index refund_item_order_item_idx on refund_item (order_item_id);


-- 한 환불의 합계가 항목 합과 맞는지 본다.
--
-- 한 행 안에서 안 끝나는 등식이라 check 로 못 건다. V16 의 assert_order_amounts 와 같은 모양이고
-- 같은 이유로 앱에 안 둔다 — 입구가 하나가 아니다.
create or replace function assert_refund_totals(p_refund_id bigint) returns void
language plpgsql as $$
declare
    r record;
    v_item_sum bigint;
    v_items int;
begin
    select amount, shipping_fee_refund
      into r
      from refund
     where refund_id = p_refund_id;

    -- 환불이 이미 지워졌다. 검사할 것이 없다.
    if not found then
        return;
    end if;

    select coalesce(sum(amount), 0), count(*)
      into v_item_sum, v_items
      from refund_item
     where refund_id = p_refund_id;

    if v_items = 0 then
        raise exception '항목이 없는 환불이다 (refund_id=%)', p_refund_id;
    end if;

    if r.amount <> v_item_sum + r.shipping_fee_refund then
        raise exception '환불 금액이 항목 합과 다르다 (refund_id=%, 저장=%, 항목합=%, 배송비=%)',
            p_refund_id, r.amount, v_item_sum, r.shipping_fee_refund;
    end if;
end;
$$;


-- 주문 하나의 환불 합이 결제액을 안 넘는지 본다(money-invariants).
--
-- 반려된 요청은 안 센다. 승인 대기중인 요청은 센다 —
-- 안 세면 전액 환불 요청을 열 번 넣어 두고 나중에 전부 승인하는 경로가 열린다.
-- 상한을 요청 시점에 거는 것이 승인 시점에 거는 것보다 이른 자리다.
create or replace function assert_refund_within_payment(p_order_id bigint) returns void
language plpgsql as $$
declare
    v_paid bigint;
    v_refunded bigint;
begin
    select amount
      into v_paid
      from payment
     where order_id = p_order_id and status = 'approved';

    select coalesce(sum(r.amount), 0)
      into v_refunded
      from refund r
      join seller_order so on so.seller_order_id = r.seller_order_id
     where so.order_id = p_order_id
       and r.status <> 'rejected';

    if v_refunded = 0 then
        return;
    end if;

    -- 승인된 결제가 없는데 환불이 있다. 낸 적 없는 돈을 돌려주는 것이라 금액과 무관하게 막는다.
    if v_paid is null then
        raise exception '결제 승인이 없는 주문의 환불이다 (order_id=%)', p_order_id;
    end if;

    if v_refunded > v_paid then
        raise exception '환불 합이 결제액을 넘는다 (order_id=%, 결제=%, 환불합=%)',
            p_order_id, v_paid, v_refunded;
    end if;
end;
$$;


-- 한 주문 항목에서 누적으로 얼마나 나갔는지 본다.
--
-- 환불이 여러 번 날 수 있어서 한 행만 봐서는 못 막는다 —
-- 수량 3개짜리를 2개씩 두 번 환불하면 각각은 상한 안이고 합은 넘는다.
create or replace function assert_refund_item_within_order_item(p_order_item_id bigint)
returns void
language plpgsql as $$
declare
    oi record;
    v_quantity int;
    v_amount bigint;
    v_commission bigint;
begin
    select quantity, line_amount, commission_amount
      into oi
      from order_item
     where order_item_id = p_order_item_id;

    if not found then
        return;
    end if;

    select coalesce(sum(ri.quantity), 0), coalesce(sum(ri.amount), 0),
           coalesce(sum(ri.commission_refund), 0)
      into v_quantity, v_amount, v_commission
      from refund_item ri
      join refund r on r.refund_id = ri.refund_id
     where ri.order_item_id = p_order_item_id
       and r.status <> 'rejected';

    if v_quantity > oi.quantity then
        raise exception '환불 수량이 주문 수량을 넘는다 (order_item_id=%, 주문=%, 환불누계=%)',
            p_order_item_id, oi.quantity, v_quantity;
    end if;

    if v_amount > oi.line_amount then
        raise exception '환불 대금이 항목 금액을 넘는다 (order_item_id=%, 항목=%, 환불누계=%)',
            p_order_item_id, oi.line_amount, v_amount;
    end if;

    if v_commission > oi.commission_amount then
        raise exception '수수료 환불이 항목 수수료를 넘는다 (order_item_id=%, 항목=%, 환불누계=%)',
            p_order_item_id, oi.commission_amount, v_commission;
    end if;
end;
$$;


create or replace function check_refund() returns trigger
language plpgsql as $$
declare
    v_order_id bigint;
begin
    perform assert_refund_totals(new.refund_id);

    select so.order_id into v_order_id
      from seller_order so
     where so.seller_order_id = new.seller_order_id;

    if found then
        perform assert_refund_within_payment(v_order_id);
    end if;

    -- 상태가 바뀌면(반려) 그 환불에 딸린 항목의 누계도 같이 줄어든다.
    -- 항목 트리거는 안 도므로 여기서 같이 본다.
    perform assert_refund_item_within_order_item(ri.order_item_id)
       from refund_item ri
      where ri.refund_id = new.refund_id;

    return null;
end;
$$;


create or replace function check_refund_item() returns trigger
language plpgsql as $$
declare
    v_order_id bigint;
begin
    if tg_op <> 'INSERT' then
        perform assert_refund_totals(old.refund_id);
        perform assert_refund_item_within_order_item(old.order_item_id);
    end if;

    if tg_op <> 'DELETE' then
        perform assert_refund_totals(new.refund_id);
        perform assert_refund_item_within_order_item(new.order_item_id);

        select so.order_id into v_order_id
          from refund r
          join seller_order so on so.seller_order_id = r.seller_order_id
         where r.refund_id = new.refund_id;

        if found then
            perform assert_refund_within_payment(v_order_id);
        end if;
    end if;

    return null;
end;
$$;


-- 지연 트리거인 이유는 V16 과 같다. refund 가 먼저 들어가고 refund_item 이 뒤에 붙는데
-- 참조 방향이 그 반대라 순서를 못 바꾼다 — 즉시 트리거면 refund 를 넣는 순간
-- 항목이 0개라 언제나 깨진다.
create constraint trigger refund_amounts_check
    after insert or update on refund
    deferrable initially deferred
    for each row execute function check_refund();

create constraint trigger refund_item_amounts_check
    after insert or update or delete on refund_item
    deferrable initially deferred
    for each row execute function check_refund_item();
