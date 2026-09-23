-- 웹훅 발송 기록(`30`·`31`, `D16`).
--
-- **엔드포인트 × 사건마다 한 줄이다.** 같은 사건이 같은 주소에 두 번 가는 것을 유일 제약이 막는다 —
-- 스위퍼가 두 대 떠도, 한 회차가 죽고 다음 회차가 다시 훑어도 줄은 하나다(「최소 한 번」은 보내기 쪽의 말이다).
--
-- **시도 수와 다음 시각이 칸이다**(`31`). 앱 메모리에 두면 재기동에 사라지고, 사라지면 실패한 발송을 아무도 다시 안 보낸다.
-- `31` 이 이 칸을 쓴다 — 표는 여기서 한 번에 세운다.
create table webhook_delivery (
    webhook_delivery_id bigint not null generated always as identity primary key,

    -- 엔드포인트를 지우면 기록도 같이 간다 — 셀러가 지운 주소의 발송 이력을 들고 있을 이유가 없다.
    webhook_endpoint_id bigint not null
        references webhook_endpoint (webhook_endpoint_id) on delete cascade,

    -- 사건 id 가 곧 `webhook-id` 헤더다(Standard Webhooks). 받는 쪽이 이것으로 중복을 거른다.
    outbox_event_id bigint not null references outbox_event (outbox_event_id) on delete cascade,

    -- pending   보낼 차례를 기다린다(다음 시각이 있다)
    -- sent      2xx 를 받았다
    -- failed    다시 보내도 안 될 실패 — 4xx, 안쪽 주소로 바뀐 엔드포인트(`WebhookUrlPolicy`)
    -- exhausted 다시 보낼 수 있었는데 최대 횟수를 다 썼다(`31`). 셀러가 손으로 다시 보낸다
    status text not null default 'pending',

    attempt_count int not null default 0,
    next_attempt_at timestamptz default now(),

    last_status_code int,
    last_error text,

    created_at timestamptz not null default now(),
    delivered_at timestamptz,

    constraint webhook_delivery_status_check
        check (status in ('pending', 'sent', 'failed', 'exhausted')),
    constraint webhook_delivery_attempt_count_check check (attempt_count >= 0),
    -- 기다리는 줄만 다음 시각을 든다. 끝난 줄에 시각이 남으면 스위퍼가 다시 집는다.
    constraint webhook_delivery_next_attempt_check
        check ((status = 'pending') = (next_attempt_at is not null)),
    constraint webhook_delivery_delivered_check
        check ((status = 'sent') = (delivered_at is not null)),
    constraint webhook_delivery_last_error_length_check check (length(last_error) <= 500),
    constraint webhook_delivery_last_status_code_check
        check (last_status_code is null or last_status_code between 100 and 599),

    constraint webhook_delivery_endpoint_event_unique unique (webhook_endpoint_id, outbox_event_id)
);

-- 스위퍼가 「지금 보낼 것」을 찾는 자리.
create index webhook_delivery_due_idx on webhook_delivery (next_attempt_at) where status = 'pending';
create index webhook_delivery_outbox_event_idx on webhook_delivery (outbox_event_id);

comment on table webhook_delivery is
    '웹훅 발송 기록(30). 엔드포인트×사건마다 한 줄, 시도 수와 다음 시각이 칸이다(31)';
