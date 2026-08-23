-- 반품 스키마와 상태기계(청크 43, 44 를 흡수했다).
--
-- 지금까지 반품은 `seller_order` 의 상태 둘(`return_requested`·`returned`)과 사유 하나
-- (`return_reason`, V29)가 전부였다. 그래서 **수거·입고·검수·귀책이 어디에도 없다** —
-- 물건이 돌아왔는지, 하자가 사실인지, 반품 배송비를 누가 무는지가 코드에 없는 사실이었다.
--
-- 스키마와 상태기계를 갈라 치지 않는다(2026-08-23 검토). 갈라 치면 그 사이가 **제약 없는 표**
-- 구간이고, 상태 규칙이 뒤에 오면 이미 들어간 행이 그 규칙을 안 지킨다.
--
--
-- ## 상태를 어디에 두나 (사용자 선택)
--
-- **묶음은 요약, 반품 표가 진행이다.**
--
--   seller_order.status    delivered → return_requested → returned
--                                                       ↘ delivered (거절)
--   return_request.status  requested → picked_up → received → inspected → approved | rejected
--
-- 묶음 상태는 「이 묶음이 반품 중인가/끝났나」에만 답한다 — 자동확정 배치(`OrderStatusBatch`)와
-- 정산이 그 한 가지를 묻고, 수거 단계까지 알 필요가 없다. 진행은 이 표가 든다.
--
-- **둘이 어긋나는 것은 지연 제약 트리거가 막는다**(아래 `assert_return_bundle_status`).
-- 상태를 트리거가 **옮기지는 않는다** — 옮기면 `order_status_history` 없는 전이가 생기고,
-- 그러면 「무엇이 무엇으로 언제 바뀌었나」가 이력에서 빠진다(V18).
--
--
-- ## 노출 번호를 안 만든다
--
-- `identifier-rules.md` 의 셀러 주문 행이 **「반품 접수」를 이미 그 번호의 소비자로 적어 뒀다.**
-- 묶음 하나에 열린 반품은 하나뿐이라(`return_request_open_idx`) `seller_order_number` 가
-- 반품을 유일하게 가리킨다. 세어 보고 하나뿐이면 안 만든다(`CLAUDE.md` 「확장성을 재는 방법」).
--
--
-- ## 법
--
-- 조문은 V21 이 국가법령정보센터에서 원문을 확인해 옮긴 것을 그대로 쓴다.
--
--   제17조제1항  단순 변심. 배송완료 기산 7일
--   제17조제3항  표시·광고와 다른 경우. 공급받은 날부터 3개월
--   제17조제5항  훼손에 소비자 책임이 있는지 다투면 **우리가 증명한다**
--   제18조제9항  반환에 필요한 비용은 소비자 부담. 위약금·손해배상 청구 금지
--   제18조제10항 제17조제3항의 경우는 판매자 부담
--
-- 제18조제9항·제10항이 이 청크에서 처음 **코드에 닿는다.** 그전에는 약관 문구(V21·V28)에만
-- 있었고, 문구는 강제 지점 5순위라 아무것도 안 막았다(`D23` 축 2).


-- 반품 하나. 접수부터 판정까지가 한 행이다.
create table return_request (
    return_request_id bigint not null generated always as identity primary key,

    -- 묶음이 사라지면 그 반품은 가리킬 것이 없다. 애그리거트 안쪽이라 cascade 다.
    seller_order_id bigint not null
        references seller_order (seller_order_id) on delete cascade,

    -- requested → picked_up → received → inspected → approved | rejected.
    --
    -- **inspected 를 따로 둔다.** 검수하는 사람과 판정하는 사람이 갈릴 수 있어서다 —
    -- 제17조제5항이 훼손의 입증을 우리에게 지우므로, 셀러가 「훼손됐다」는 소견을 내도
    -- 그것으로 끝나지 않고 우리가 판정한다. 소견과 판정을 한 상태로 뭉치면
    -- **셀러의 주장이 곧 결론**이 되고, 그 구조에서는 입증책임이 우리에게 있다는 사실이 사라진다.
    --
    -- 검수를 건너뛴 승인은 막지 않는다. 오배송처럼 다툴 것이 없는 건이 있다.
    -- 다만 입고(received)는 못 건너뛴다 — 환급 기산점이 「반환받은 날」이다(제18조제2항).
    --
    -- **접수 취소가 없다.** 소비자가 접수를 무르는 것은 청약철회의 철회라
    -- 그 효과가 안 정해졌다. 정하는 자리는 반품 접수 입구를 세우는 청크다(`43a`).
    status text not null default 'requested',

    -- 어느 조항으로 받았나. seller_order.return_reason 과 같은 목록이다(V29).
    --
    -- 값을 복사해 두는 것이 아니라 **접수 시점의 사실을 이 행이 든다.** 거절되면 묶음은
    -- delivered 로 돌아가고 그 컬럼은 비워지는데, 「무슨 사유로 접수됐다가 거절됐나」는 남아야 한다.
    reason_code text not null,

    requested_by_user_id bigint not null
        references app_user (user_id) on delete restrict,
    requested_at timestamptz not null default now(),

    picked_up_at timestamptz,
    received_at  timestamptz,

    inspected_at timestamptz,
    inspected_by_user_id bigint references app_user (user_id) on delete restrict,

    decided_at timestamptz,
    decided_by_user_id bigint references app_user (user_id) on delete restrict,

    -- 반품 배송비를 누가 무나. consumer | seller.
    --
    -- **제18조제9항이 원칙(소비자), 제10항이 예외(판매자)다.** 판정 때 정해진다 —
    -- 접수 시점의 사유만으로 못 정하는 이유는 하자 주장을 안 거르기 때문이다(제17조제5항).
    -- 「하자라고 접수했는데 검수에서 아니었다」가 성립하고, 그때 부담은 소비자로 간다.
    return_shipping_fee_bearer text,

    -- 돌아온 물건을 다시 팔 수 있나. 승인일 때만 답이 있다.
    --
    -- `state-machines.md` 가 「반품은 재고를 안 되돌린다, 그 판단은 반품 축이 맡는다」고
    -- 미뤄 둔 것이 이 컬럼이다. 상태만으로는 못 정한다 — 같은 approved 라도
    -- 단순 변심으로 돌아온 새 물건과 파손된 물건이 다르다.
    restock boolean,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint return_request_status_check
        check (status in ('requested', 'picked_up', 'received',
                          'inspected', 'approved', 'rejected')),

    constraint return_request_reason_code_check
        check (reason_code in ('change_of_mind', 'defect')),

    -- 시각이 상태를 따라간다. 「입고됐다는데 입고 시각이 없는」 행을 막는다.
    --
    -- 앞 단계의 시각이 있어야 다음 상태로 간다는 것만 본다. 뒤 단계의 시각이
    -- 미리 차 있는 것은 아래 return_request_future_timestamps_check 가 막는다.
    constraint return_request_timeline_check
        check (case status
                   when 'requested' then true
                   when 'picked_up'  then picked_up_at is not null
                   when 'received'   then received_at is not null
                   when 'inspected'  then received_at is not null and inspected_at is not null
                   else received_at is not null and decided_at is not null
               end),

    -- 아직 안 지난 단계의 시각이 차 있으면 막는다.
    constraint return_request_future_timestamps_check
        check (case status
                   when 'requested' then num_nonnulls(picked_up_at, received_at,
                                                      inspected_at, decided_at) = 0
                   when 'picked_up' then num_nonnulls(received_at, inspected_at, decided_at) = 0
                   when 'received'  then num_nonnulls(inspected_at, decided_at) = 0
                   when 'inspected' then decided_at is null
                   else true
               end),

    -- 검수한 사람과 판정한 사람은 그 시각과 같이 있거나 같이 없다.
    constraint return_request_inspected_by_check
        check ((inspected_at is null) = (inspected_by_user_id is null)),

    constraint return_request_decided_by_check
        check ((decided_at is null) = (decided_by_user_id is null)),

    -- 판정이 끝났다는 것과 종점 상태라는 것이 같은 말이어야 한다.
    constraint return_request_decision_check
        check ((status in ('approved', 'rejected')) = (decided_at is not null)),

    constraint return_shipping_fee_bearer_check
        check (return_shipping_fee_bearer is null
               or return_shipping_fee_bearer in ('consumer', 'seller')),

    -- 부담 주체는 판정과 같이 정해진다.
    constraint return_request_bearer_decided_check
        check ((status in ('approved', 'rejected'))
               = (return_shipping_fee_bearer is not null)),

    -- **제18조제10항을 여기서 막는다.** 하자로 인정한 반품의 배송비를 소비자에게 물리면
    -- 행이 안 들어간다. 문구가 아니라 제약이라 새 입구가 생겨도 안 빠진다.
    constraint return_request_defect_bearer_check
        check (status <> 'approved'
               or reason_code <> 'defect'
               or return_shipping_fee_bearer = 'seller'),

    -- **제18조제9항.** 거절된 반품은 물건을 돌려보내는 비용까지 소비자가 문다.
    -- 제10항의 예외는 「제17조제3항의 경우」인데 거절은 그 경우가 아니라고 판정한 것이다.
    constraint return_request_rejected_bearer_check
        check (status <> 'rejected' or return_shipping_fee_bearer = 'consumer'),

    -- 재고 복구 판단은 승인일 때만 있다. 거절이면 물건이 소비자에게 돌아간다.
    constraint return_request_restock_check
        check ((status = 'approved') = (restock is not null))
);

comment on table return_request is
    '반품 하나. 접수·수거·입고·검수·판정을 상태로 들고, 묶음 상태는 요약만 든다(D7, 청크 43·44)';

comment on column return_request.return_shipping_fee_bearer is
    '반품 배송비 부담 주체. 제18조제9항이 원칙(소비자), 제10항이 예외(판매자)다(D2 R3)';

comment on column return_request.restock is
    '돌아온 물건을 다시 팔 수 있나. 승인 판정이 같이 정한다 — 상태만으로는 못 정한다';

create trigger return_request_set_updated_at
    before update on return_request
    for each row execute function set_updated_at();

-- 묶음 하나에 열린 반품은 하나다.
--
-- 수거가 한 번이라 동시에 두 건이 굴러가면 어느 물건이 어느 건인지 사람이 가른다.
-- **닫힌 것은 여럿 가능하다** — 거절된 뒤 다시 접수하는 길이 하자 반품에는 열려 있어야 한다
-- (제17조제3항의 3개월이 거절로 사라지지 않는다).
create unique index return_request_open_idx
    on return_request (seller_order_id)
 where status not in ('approved', 'rejected');

-- 셀러가 처리해야 할 것을 찾는 자리. 판정이 안 끝난 건을 오래된 순으로 본다.
create index return_request_pending_idx
    on return_request (status, requested_at)
 where status not in ('approved', 'rejected');


-- 무엇을 몇 개 반품하나.
--
-- `refund_item` 과 같은 모양이다(V23). 묶음 통째로만 받으면 「셋 중 하나만 하자」를
-- 표현할 자리가 없고, 그 경우 환불 금액과 수수료 반환도 항목 단위로 못 센다.
create table return_request_item (
    return_request_item_id bigint not null generated always as identity primary key,

    return_request_id bigint not null
        references return_request (return_request_id) on delete cascade,

    order_item_id bigint not null
        references order_item (order_item_id) on delete cascade,

    quantity integer not null,

    created_at timestamptz not null default now(),

    constraint return_request_item_quantity_check check (quantity > 0),

    -- 같은 항목을 한 반품에 두 줄로 넣으면 수량이 두 곳에서 세어진다.
    constraint return_request_item_unique unique (return_request_id, order_item_id)
);

comment on table return_request_item is
    '반품에 담긴 주문 항목과 수량. 부분 반품을 항목 단위로 센다(refund_item 과 같은 모양)';


-- 수거지. 반품 표에서 떼어 놨다.
--
-- **`order_shipping` 과 같은 수단이다**(V16) — 사람 정보라 파기 대상인데 반품 사실 자체는
-- 거래기록이라 5년을 채운다. 한 행에 두면 지울 것과 남길 것이 엉킨다.
--
-- 배송지를 그대로 쓰지 않는다. 받은 곳과 보내는 곳이 다를 수 있고(직장에서 받아 집에서 수거),
-- 배송지는 거래 종료 + 6개월에 사라지는데 그때 반품 이력만 남으면 수거지가 배송지였다는
-- 사실조차 확인이 안 된다.
--
-- 동의를 따로 안 받는다. 계약 이행에 필요한 정보고 개인정보보호법 제15조제1항 4호가 근거다 —
-- 배송지와 같은 자리다. 보유기간은 `D13`, 파기는 `TransactionPurgeService` 가 든다.
create table return_pickup (
    -- 반품 하나에 수거지 하나라 본체의 기본키를 그대로 쓴다(`D22`).
    return_request_id bigint not null primary key
        references return_request (return_request_id) on delete cascade,

    sender_name  text not null,
    sender_phone text not null,
    postal_code  text not null,
    address1     text not null,
    address2     text,

    -- 「경비실에 맡겨 뒀어요」 같은 것. 사람 정보가 섞여 들어와 파기 대상에 같이 든다.
    pickup_memo text,

    created_at timestamptz not null default now(),

    -- 형식은 배송지와 같은 근거다(V33, Q7). 우편번호는 우정사업본부 고시의
    -- 국가기초구역번호 5자리, 전화번호는 오타만 거른다.
    constraint return_pickup_postal_code_check
        check (postal_code ~ '^[0-9]{5}$'),

    constraint return_pickup_sender_phone_check
        check (sender_phone ~ '^[0-9-]+$'
               and length(replace(sender_phone, '-', '')) between 9 and 13)
);

comment on table return_pickup is
    '반품 수거지. 파기 대상이라 반품에서 분리했다(D2 R9, D13). 거래 종료 + 6개월에 사라진다';


-- 반품에 사람이 쓴 글.
--
-- **`refund_note` 와 같은 수단이다**(V48) — 컬럼 자체는 사람 정보가 아닌데 글 안에 섞여 들어온다.
-- `request_reason` 은 구매자가 직접 쓰는 칸이라 특히 그렇다.
create table return_note (
    return_request_id bigint not null primary key
        references return_request (return_request_id) on delete cascade,

    -- 구매자가 쓴 반품 사유.
    request_reason text,

    -- 검수한 사람이 쓴 소견.
    inspection_note text,

    -- 판정한 사람이 쓴 승인·거절 사유.
    decision_reason text,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint return_note_request_reason_length_check
        check (length(request_reason) between 1 and 500),

    constraint return_note_inspection_note_length_check
        check (length(inspection_note) between 1 and 500),

    constraint return_note_decision_reason_length_check
        check (length(decision_reason) between 1 and 500),

    constraint return_note_any_check
        check (num_nonnulls(request_reason, inspection_note, decision_reason) >= 1)
);

comment on table return_note is
    '반품 사유·소견 글. 사람이 쓴 글이라 5년 표에서 뺐다. 거래 종료 + 6개월에 사라진다(D13)';

create trigger return_note_set_updated_at
    before update on return_note
    for each row execute function set_updated_at();


-- 거절에는 사유가 필요하다.
--
-- 표를 넘는 조건이라 `check` 로 못 건다. `refund_requires_rejection_reason`(V48)과 같은 수단으로
-- 지연 제약 트리거를 쓴다 — 커밋 시점에 보므로 서비스가 판정을 먼저 쓰고 글을 뒤에 넣어도 된다.
--
-- **`return_note` 에는 안 건다.** 파기 배치가 여섯 달 뒤 그 행을 지우는데 거기까지 보면
-- 파기가 이 제약에 막힌다 — 검사할 시점은 거절하는 순간이지 그 뒤가 아니다.
create or replace function assert_return_rejection_reason() returns trigger as $$
begin
    if new.status = 'rejected'
       and not exists (select 1 from return_note n
                        where n.return_request_id = new.return_request_id
                          and n.decision_reason is not null) then
        raise exception '반품 거절에는 사유가 필요하다 (return_request_id=%)',
            new.return_request_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger return_requires_rejection_reason
    after insert or update of status on return_request
    deferrable initially deferred
    for each row execute function assert_return_rejection_reason();


-- 묶음 상태와 반품 상태가 어긋나는 것을 막는다.
--
-- **막기만 하고 옮기지는 않는다.** 트리거가 `seller_order.status` 를 바꾸면
-- `order_status_history` 없는 전이가 생기고, 그러면 「무엇이 언제 바뀌었나」가 이력에서 빠진다(V18).
-- 옮기는 것은 앱이 하고(`OrderStatusService`), 트리거는 둘이 맞는지만 본다.
--
-- 지연이라 순서를 안 정한다 — 반품을 먼저 넣든 묶음을 먼저 옮기든 커밋 때 맞으면 된다.
create or replace function assert_return_bundle_status() returns trigger as $$
declare
    v_seller_order_id bigint;
    v_bundle_status   text;
    v_open_count      integer;
    v_approved_count  integer;
begin
    v_seller_order_id := coalesce(new.seller_order_id, old.seller_order_id);

    select status into v_bundle_status
      from seller_order
     where seller_order_id = v_seller_order_id;

    -- 묶음이 이미 사라졌으면 볼 것이 없다. 파기가 지운 뒤다.
    if v_bundle_status is null then
        return null;
    end if;

    select count(*) filter (where status not in ('approved', 'rejected')),
           count(*) filter (where status = 'approved')
      into v_open_count, v_approved_count
      from return_request
     where seller_order_id = v_seller_order_id;

    if v_open_count > 0 and v_bundle_status <> 'return_requested' then
        raise exception '반품이 진행 중인데 묶음이 %다 (seller_order_id=%)',
            v_bundle_status, v_seller_order_id using errcode = 'check_violation';
    end if;

    if v_approved_count > 0 and v_bundle_status <> 'returned' then
        raise exception '승인된 반품이 있는데 묶음이 %다 (seller_order_id=%)',
            v_bundle_status, v_seller_order_id using errcode = 'check_violation';
    end if;

    -- 거절만 있는 묶음은 배송완료로 돌아간다. 그 뒤 확정까지 갔을 수 있어서 둘 다 받는다.
    if v_open_count = 0 and v_approved_count = 0
       and v_bundle_status not in ('delivered', 'confirmed') then
        raise exception '열린 반품이 없는데 묶음이 %다 (seller_order_id=%)',
            v_bundle_status, v_seller_order_id using errcode = 'check_violation';
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger return_request_bundle_status_check
    after insert or update of status, seller_order_id on return_request
    deferrable initially deferred
    for each row execute function assert_return_bundle_status();


-- 반대 방향도 같은 함수로 본다.
--
-- 묶음만 반품 상태로 옮기고 반품 행을 안 만드는 경로를 막는다. 이 방향이 없으면
-- `psql` 이나 배치가 `return_requested` 로 바꿔 놓고 수거·검수가 통째로 비어 있게 된다.
create or replace function assert_bundle_return_status() returns trigger as $$
declare
    v_open_count     integer;
    v_approved_count integer;
begin
    if new.status not in ('return_requested', 'returned') then
        return null;
    end if;

    select count(*) filter (where status not in ('approved', 'rejected')),
           count(*) filter (where status = 'approved')
      into v_open_count, v_approved_count
      from return_request
     where seller_order_id = new.seller_order_id;

    if new.status = 'return_requested' and v_open_count = 0 then
        raise exception '반품 접수 없이 묶음만 return_requested 다 (seller_order_id=%)',
            new.seller_order_id using errcode = 'check_violation';
    end if;

    if new.status = 'returned' and v_approved_count = 0 then
        raise exception '승인된 반품 없이 묶음만 returned 다 (seller_order_id=%)',
            new.seller_order_id using errcode = 'check_violation';
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger seller_order_return_status_check
    after update of status on seller_order
    deferrable initially deferred
    for each row execute function assert_bundle_return_status();
