-- 아웃박스 페이로드를 `D12`·`D9`·`D22` 에 맞춘다(`Q66`).
--
-- `Q57` 이 카탈로그를 옮기면서 규칙 넷을 어겼고 마무리 18차 독립 리뷰가 찾았다.
-- **실물로 밟힌 것이 하나 있다**: 재고와 반품이 둘 다 작은 내부 id 를 `subject` 에 실어서
-- **한 대상에 사건이 둘로 세어졌다** — 소비자가 `subject` 로 묶으면 남의 사건과 섞인다.
--
-- **지금이 고칠 수 있는 마지막 자리다.** `shop.batch.run_finished` 의 이름은 **대외 계약**이고
-- 웹훅(`29`)이 한 번 내보내면 못 고친다 — 고치는 것이 아니라 새 이름을 더하고 둘을 같이 내보내는 일이 된다.

-- ---------------------------------------------------------------------------
-- 1. 이름 — 자원 토막은 표 이름(단수)이다
-- ---------------------------------------------------------------------------

-- `D12` 가 「자원은 `D22` 의 표 이름(단수)」이라 정해 놓고 자기 표에서 어겼다.
-- 실물 표는 `batch_run` 이고 `batch` 라는 표는 없다.
alter table outbox_event drop constraint outbox_event_type_check;

alter table outbox_event add constraint outbox_event_type_check check (type in (
    'shop.order.status_changed', 'shop.seller_order.status_changed', 'shop.sku.stock_moved',
    'shop.refund.status_changed', 'shop.return_request.status_changed',
    'shop.settlement.payout_changed', 'shop.batch_run.finished'));

-- 이미 쌓인 행도 옮긴다. **불변 트리거가 `type` 도 본다**(`V70` 의 `assert_outbox_event_immutable`) —
-- 처음 쓸 때 「안 본다」고 적었는데 사실이 아니었다(마무리 22차 독립 리뷰). 빈 표에서는 이 `update` 가
-- 0행이라 테스트도 CI 도 초록인데, **행이 하나라도 있는 DB 에서는 마이그레이션이 죽는다.**
--
-- 그래서 그 트리거를 잠깐 끄고 켠다. **끄는 범위가 이 문장 하나여야 한다** —
-- 켜는 것을 빠뜨리면 그 뒤로 봉투를 아무나 고칠 수 있고, 그것은 트리거가 없는 것과 같다.
alter table outbox_event disable trigger outbox_event_immutable;

update outbox_event set type = 'shop.batch_run.finished' where type = 'shop.batch.run_finished';

alter table outbox_event enable trigger outbox_event_immutable;

-- ---------------------------------------------------------------------------
-- 2. 반품 — `subject` 가 내부 id 였다
-- ---------------------------------------------------------------------------

-- `D9` 「자원별 노출 방식」이 반품에 **「없다 — `seller_order_number`」** 로 이미 정했는데
-- `D12` 표가 「노출 번호가 아직 없다」로 다르게 적었고 `V70` 이 그것을 옮겼다.
-- **`D9` 가 식별자의 출처다.**
--
-- `actor_type` 을 안 싣는다: 원천 표에 **주체의 종류를 담는 칸이 없다**(`requested_by_user_id`·
-- `decided_by_user_id` 뿐이다). 사람 id 는 개인정보라 못 싣는다(`D12` 페이로드 규칙 1·3) —
-- 종류 칸이 서는 청크가 이 줄을 다시 본다.
create or replace function emit_return_request_status_event() returns trigger as $$
declare
    v_seller_order_number text;
begin
    select so.seller_order_number into v_seller_order_number
      from seller_order so
     where so.seller_order_id = new.seller_order_id;

    perform emit_outbox_event(
        'shop.return_request.status_changed',
        v_seller_order_number,
        new.updated_at,
        jsonb_build_object(
            'seller_order_number', v_seller_order_number,
            'from_status', old.status,
            'to_status', new.status,
            'reason_code', new.reason_code));
    return null;
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------------
-- 3. 환불 — 내부 id 는 없었지만 금액과 주체가 틀렸다
-- ---------------------------------------------------------------------------

-- **`amount` 가 실제로 나간 돈이 아니었다.** `RefundService.settle` 이 지연이자를 더해 보내는데
-- 사건은 `new.amount` 만 실었다 — 소비자가 환급액으로 읽으면 틀린다.
-- 이름을 `refund_amount` 로 갈라 두고 `delay_interest` 를 같이 싣는다(`D8` 은 원 단위 정수다).
--
-- `actor_type` 은 결정자를 먼저 본다. 접수 전이(`requested`)에는 결정자가 없어 요청자가 답한다.
-- `occurred_at` 은 결정 시각이 있으면 그것이다 — `updated_at` 은 전이가 아닌 수정에도 밀린다.
create or replace function emit_refund_status_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.refund.status_changed',
        new.refund_number,
        coalesce(new.decided_at, new.updated_at),
        jsonb_build_object(
            'refund_number', new.refund_number,
            'from_status', old.status,
            'to_status', new.status,
            'actor_type', coalesce(new.approved_by_type, new.requested_by_type),
            'refund_amount', new.amount,
            'delay_interest', new.delay_interest));
    return null;
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------------
-- 4. 정산 — 셀러 내부 id 를 실었다
-- ---------------------------------------------------------------------------

-- `seller_id` 는 내부 id 고 셀러에게는 노출 번호(`seller.code`)가 있다 —
-- `D12` 페이로드 규칙 2 가 「내부 id 는 `sku_id` 처럼 **노출 번호가 없는 것만**」이다.
--
-- 주체는 언제나 관리자다(`D7` 「정산 지급 상태」 — 돈이 우리에게서 나가는 자리라
-- 요청·승인이 둘 다 관리자다). 원천 표에 종류 칸이 없어 그 사실을 상수로 적는다.
-- `occurred_at` 은 `now()` 대신 결정 시각을 쓴다.
create or replace function emit_settlement_payout_event() returns trigger as $$
declare
    v_seller_code text;
begin
    select s.code into v_seller_code from seller s where s.seller_id = new.seller_id;

    perform emit_outbox_event(
        'shop.settlement.payout_changed',
        new.settlement_number,
        coalesce(new.payout_decided_at, new.payout_requested_at, new.updated_at),
        jsonb_build_object(
            'settlement_number', new.settlement_number,
            'seller_code', v_seller_code,
            'from_status', old.payout_status,
            'to_status', new.payout_status,
            'actor_type', 'admin',
            'payout_amount', new.payout_amount));
    return null;
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------------
-- 5. 재고 — 주문 내부 id 를 실었다
-- ---------------------------------------------------------------------------

-- `sku_id` 는 노출 번호가 없어서 그대로 두는 것이 규칙에 맞다(`D12` 표가 그렇게 적었다).
-- 같이 실리던 `order_id` 는 다르다 — 주문에는 `order_number` 가 있다.
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
            'order_number', (select o.order_number from shop_order o
                              where o.order_id = new.order_id)));
    return null;
end;
$$ language plpgsql;

-- ---------------------------------------------------------------------------
-- 6. 배치 회차 — 이름이 바뀌었으니 발행도 바꾼다
-- ---------------------------------------------------------------------------

create or replace function emit_batch_run_event() returns trigger as $$
begin
    perform emit_outbox_event(
        'shop.batch_run.finished',
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
