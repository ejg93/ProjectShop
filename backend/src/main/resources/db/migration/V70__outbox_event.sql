-- 바깥에 알릴 사건을 한 표에 모은다(`Q57`, `D12`).
--
-- **왜 표에 적어 두나.** 주문을 바꾸는 것과 남에게 알리는 것을 한꺼번에 성공시킬 방법이 없다.
-- 알리다 실패하면 주문만 바뀌고 아무도 모르고, 알린 뒤 주문이 롤백되면 없던 일을 알린 것이 된다.
-- 같은 DB 에 적어 두면 **둘이 같이 성공하거나 같이 없던 일이 된다** — `D11` 「한 트랜잭션」이 공짜다.
--
-- **앱이 아니라 원천 표의 트리거가 채운다**(사용자 결정 2026-09-14). 앱이 채우면
-- 새 경로를 만드는 사람이 그 한 줄을 빠뜨리고, **빠뜨린 것은 아무도 안 알린 날에야 드러난다.**
-- `sku_stock_requires_move` 가 재고에서 막은 것과 같은 판단이다.
--
-- 보내는 것은 여기 없다. 발행기는 청크 33 이 세운다 — 트랜잭션 안에서 바깥을 안 부른다(`D11`).

create table outbox_event (
    outbox_event_id bigint not null generated always as identity primary key,

    -- `shop.<자원>.<사건>`. 자원은 표 이름(단수), 사건은 과거형이다(`D12`).
    type text not null,

    -- CloudEvents 의 `source`. `ErrorCode` 의 `tag:` 체계와 같다(`D5`).
    -- 진짜 도메인이 생기면 같이 바뀌고 **그것은 계약 변경이다.**
    source text not null default 'tag:projectshop.example,2026:shop',

    -- 무엇에 대한 사건인가. 노출 번호가 있으면 그것이고 없으면 내부 id 다(`D12` 표).
    subject text not null,

    -- **원천 행의 시각이지 발행 시각이 아니다.** 소비자가 순서를 판단하는 값이라
    -- 발행이 밀렸다고 이 값이 밀리면 안 된다(`D10`).
    occurred_at timestamptz not null,

    data jsonb not null,

    created_at timestamptz not null default now(),

    -- 비어 있으면 아직 안 보냈다. 발행기(`33`)가 이 칸을 본다.
    published_at timestamptz,

    -- 닫힌 목록이다. Java `EventType` 과 `EnumConstraintTest` 가 대조한다(`D23` 「열거값」).
    constraint outbox_event_type_check check (type in (
        'shop.order.status_changed', 'shop.seller_order.status_changed', 'shop.sku.stock_moved',
        'shop.refund.status_changed', 'shop.return_request.status_changed',
        'shop.settlement.payout_changed', 'shop.batch.run_finished'))
);

-- 발행기가 읽는 것은 안 보낸 것뿐이다. 부분 인덱스라 보낸 행이 쌓여도 안 커진다.
create index outbox_event_unpublished_idx on outbox_event (outbox_event_id)
    where published_at is null;

comment on table outbox_event is
    '바깥에 알릴 사건. 원천 표의 트리거가 채운다 (D12, Q57)';
comment on column outbox_event.occurred_at is
    '원천 행의 시각. 발행 시각이 아니다 (D12 봉투)';

-- ---------------------------------------------------------------------------
-- 방벽 — 앱은 이 표에 직접 안 넣는다
-- ---------------------------------------------------------------------------
--
-- **관례로 두면 안 지켜진다.** 「사건은 트리거가 적는다」를 문서에만 적으면 다음 사람이
-- 서비스에서 `insert` 를 쓴다 — 그러면 **한 사건이 두 벌 나가거나**, 트리거가 없는 경로에서만
-- 적혀서 **어느 경로가 적고 어느 경로가 안 적는지가 코드에 흩어진다.**
--
-- 트리거 함수가 `set_config` 로 잠깐 열고 넣는다. 그 밖의 `insert` 는 거부다.
create or replace function assert_outbox_write_allowed() returns trigger as $$
begin
    if coalesce(current_setting('shop.outbox_write', true), '0') <> '1' then
        raise exception '아웃박스는 원천 표의 트리거만 채운다 (type=%)', new.type
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger outbox_event_requires_trigger
    before insert on outbox_event
    for each row execute function assert_outbox_write_allowed();

-- 트리거 함수들이 공통으로 부른다. 방벽을 열고 넣고 닫는 것을 한 자리에 둔다 —
-- 여섯 함수가 각자 `set_config` 를 부르면 하나가 닫는 것을 빠뜨리는 날이 온다.
create or replace function emit_outbox_event(
        p_type text, p_subject text, p_occurred_at timestamptz, p_data jsonb)
    returns void as $$
begin
    -- 세 번째 인자가 `true` 라 **이 트랜잭션 안에서만** 산다. 커밋·롤백이면 저절로 풀린다.
    perform set_config('shop.outbox_write', '1', true);
    insert into outbox_event (type, subject, occurred_at, data)
    values (p_type, p_subject, p_occurred_at, p_data);
    perform set_config('shop.outbox_write', '0', true);
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------------
-- 원천 트리거 — 넣기만 하는 표는 `after insert`
-- ---------------------------------------------------------------------------

-- 주문 상태 이력. 한 표가 두 층을 든다 — 결제 층과 배송 층이고
-- `order_status_history_target_check` 가 둘 중 하나만 채워지게 막는다.
create or replace function emit_order_status_event() returns trigger as $$
begin
    if new.order_id is not null then
        perform emit_outbox_event(
            'shop.order.status_changed',
            (select order_number from shop_order where order_id = new.order_id),
            new.occurred_at,
            jsonb_build_object(
                'order_number', (select order_number from shop_order where order_id = new.order_id),
                'from_status', new.from_status,
                'to_status', new.to_status,
                'actor_type', new.actor_type));
    else
        -- 셀러 층은 둘 다 싣는다. 받는 쪽이 셀러라 자기 묶음 번호가 주인공이고,
        -- 주문번호는 손님 문의를 잇는 열쇠다.
        perform emit_outbox_event(
            'shop.seller_order.status_changed',
            (select seller_order_number from seller_order
              where seller_order_id = new.seller_order_id),
            new.occurred_at,
            jsonb_build_object(
                'seller_order_number', (select seller_order_number from seller_order
                                         where seller_order_id = new.seller_order_id),
                'order_number', (select o.order_number from seller_order so
                                   join shop_order o on o.order_id = so.order_id
                                  where so.seller_order_id = new.seller_order_id),
                'from_status', new.from_status,
                'to_status', new.to_status,
                'actor_type', new.actor_type));
    end if;
    return null;
end;
$$ language plpgsql;

create trigger order_status_history_emits_event
    after insert on order_status_history
    for each row execute function emit_order_status_event();

-- 재고 이동. **웹훅으로는 안 나간다**(`D12`) — `subject` 가 내부 id 라 밖에 내보일 것이 아니다.
-- 그래도 적어 두는 것은 안쪽 소비자(집계·알림)가 쓸 자리라서다.
create or replace function emit_stock_moved_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.sku.stock_moved',
        new.sku_id::text,
        new.created_at,
        jsonb_build_object(
            'sku_id', new.sku_id,
            'quantity', new.quantity,
            'reason', new.reason,
            'order_id', new.order_id));
    return null;
end;
$$ language plpgsql;

create trigger sku_stock_movement_emits_event
    after insert on sku_stock_movement
    for each row execute function emit_stock_moved_event();

-- 배치 회차. 행은 **끝날 때 한 번** 쓰므로 `after insert` 가 곧 「끝났다」다(`D19`).
create or replace function emit_batch_run_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.batch.run_finished',
        new.batch_name,
        new.finished_at,
        jsonb_build_object(
            'batch_name', new.batch_name,
            'baseline_date', new.baseline_date,
            'status', new.status,
            'target_count', new.target_count,
            'processed_count', new.processed_count));
    return null;
end;
$$ language plpgsql;

create trigger batch_run_emits_event
    after insert on batch_run
    for each row execute function emit_batch_run_event();

-- ---------------------------------------------------------------------------
-- 원천 트리거 — 한 행을 고치는 표는 `after update ... when 값이 달라졌을 때`
-- ---------------------------------------------------------------------------
--
-- **같은 값으로 다시 쓰면 사건이 아니다.** `when` 절이 없으면 아무 `update` 나 사건을 낳고,
-- 소비자가 같은 통지를 여러 번 받는다. `is distinct from` 이라 `null` 도 맞게 센다.

create or replace function emit_refund_status_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.refund.status_changed',
        new.refund_number,
        new.updated_at,
        jsonb_build_object(
            'refund_number', new.refund_number,
            'from_status', old.status,
            'to_status', new.status,
            'amount', new.amount));
    return null;
end;
$$ language plpgsql;

create trigger refund_emits_status_event
    after update of status on refund
    for each row when (old.status is distinct from new.status)
    execute function emit_refund_status_event();

-- 반품 요청. `subject` 가 내부 id 다 — 노출 번호가 아직 없다(`43a-5` 자리).
create or replace function emit_return_request_status_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.return_request.status_changed',
        new.return_request_id::text,
        now(),
        jsonb_build_object(
            'return_request_id', new.return_request_id,
            'seller_order_id', new.seller_order_id,
            'from_status', old.status,
            'to_status', new.status,
            'reason_code', new.reason_code));
    return null;
end;
$$ language plpgsql;

create trigger return_request_emits_status_event
    after update of status on return_request
    for each row when (old.status is distinct from new.status)
    execute function emit_return_request_status_event();

-- 정산 지급. 상태 칸 이름이 `status` 가 아니라 `payout_status` 다 —
-- 한 행에 마감 상태와 지급 상태가 같이 있어서 갈라 뒀다(`V52`).
create or replace function emit_settlement_payout_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.settlement.payout_changed',
        new.settlement_number,
        now(),
        jsonb_build_object(
            'settlement_number', new.settlement_number,
            'seller_id', new.seller_id,
            'from_status', old.payout_status,
            'to_status', new.payout_status,
            'payout_amount', new.payout_amount));
    return null;
end;
$$ language plpgsql;

create trigger settlement_emits_payout_event
    after update of payout_status on settlement
    for each row when (old.payout_status is distinct from new.payout_status)
    execute function emit_settlement_payout_event();
