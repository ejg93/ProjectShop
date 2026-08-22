-- 알림을 무엇으로 보냈고 무엇을 보냈는지를 남긴다(청크 54a, `D18`).
--
-- 표가 셋인 이유는 **수명이 셋이라서**다(`D18-1`). 템플릿 판은 안 지우고, 발송 메타는
-- 거래 5년·광고 6개월이며, 개인화된 본문은 여섯 달만 산다. 한 표에 담으면 파기가
-- 컬럼을 `null` 로 비우는 일이 되고, 안 비운 행이 섞여도 아무것도 안 깨진다.

-- 문구의 판. `policy_document`(`V11`)와 같은 모양이다.
--
-- 코드 상수로 두면 개정한 순간 **지나간 발송의 본문을 복원할 수 없다.**
-- 지난 판은 이미 나간 메일의 근거라 안 고치고, 개정은 새 행으로 쌓는다.
create table notification_template (
    notification_template_id bigint not null generated always as identity primary key,

    -- 코드에서 참조하는 안정된 키. 개정돼도 안 바뀐다.
    code text not null,

    version int not null default 1,

    -- 메일 제목과 본문. 사건마다 달라지는 값은 변수로 꽂고, 문구 자체는 변수로 안 만든다(`D18`).
    subject text not null,
    body    text not null,

    -- 거래 통지인가 광고성 정보인가. **이 값이 동의·야간·수명을 전부 가른다**(`D18`).
    -- 문구가 정하는 성질이라 판에 둔다 — 같은 템플릿으로 보낸 것끼리 종류가 갈리면 안 된다.
    kind text not null,

    -- 이 판이 효력을 갖는 시각. 개정판을 미리 넣어 두고 시점에 갈아 끼운다.
    effective_at timestamptz not null default now(),

    created_at timestamptz not null default now(),

    constraint notification_template_code_version_key unique (code, version),

    constraint notification_template_kind_check
        check (kind in ('transactional', 'advertising'))
);

comment on table notification_template is
    '알림 문구의 판. 지난 판을 남겨야 그때 보낸 본문을 복원할 수 있다(D18)';

-- 보낸 것 하나에 행 하나. 분쟁에서 「보냈다」를 증명하는 자리다(`D18`).
create table notification (
    notification_id bigint not null generated always as identity primary key,

    -- 받는 사람. 주소는 계정에서 온다 — 여기에 주소를 복사하면 개인정보가 한 벌 더 쌓인다.
    user_id bigint not null references app_user (user_id),

    -- 무슨 사건 때문에 보냈나. 자유 텍스트로 두면 사건별 집계를 못 한다(`D23` 「열거값」).
    event_type text not null,

    -- 종류를 판에서 박제한다. `order_item` 이 상품명을 박제한 것과 같은 이유다 —
    -- **수명이 이 값에서 나오므로**(거래 5년·광고 6개월) 나중에 판이 고쳐져도
    -- 이미 나간 것의 보존 기간이 따라 움직이면 안 된다.
    kind text not null,

    -- 어느 판으로 보냈나. 판을 지우면 이력이 가리킬 곳을 잃는다.
    notification_template_id bigint not null
        references notification_template (notification_template_id) on delete restrict,

    channel text not null,

    -- 대기·성공·실패. 실패면 이유도 남긴다.
    status text not null,

    -- 종류까지다. 주소를 적으면 개인정보가 파기 대상 밖으로 샌다(`D18` 「개인정보」).
    failure_reason text,

    -- 어느 사건 때문에 보냈나. **셋 중 많아야 하나**를 채운다.
    --
    -- 다형 참조(`target_type` + `target_id`)로 두면 컬럼이 안 늘어나는 대신 외래키를 못 건다.
    -- 사라진 주문을 가리키는 이력이 생겨도 DB 가 안 막고, 파기가 대상을 지울 때 같이 치우는 것도
    -- 앱이 해야 한다. `order_contract_document` 가 같은 판단으로 컬럼을 나눴다.
    --
    -- 전부 비는 경우가 있다 — 광고성 정보와 비밀번호 재설정은 걸리는 자원이 없다.
    -- 그래서 `= 1` 이 아니라 `<= 1` 이다.
    order_id        bigint references shop_order  (order_id)        on delete cascade,
    seller_order_id bigint references seller_order (seller_order_id) on delete cascade,
    refund_id       bigint references refund       (refund_id)       on delete cascade,

    created_at timestamptz not null default now(),

    -- 실제로 나간 시각. 대기 중이면 비어 있다.
    sent_at timestamptz,

    constraint notification_kind_check
        check (kind in ('transactional', 'advertising')),

    constraint notification_channel_check
        check (channel in ('email')),

    constraint notification_status_check
        check (status in ('pending', 'succeeded', 'failed')),

    constraint notification_event_type_check
        check (event_type in ('order_placed', 'payment_completed', 'supply_delayed',
                              'refund_completed', 'password_reset', 'email_change',
                              'advertisement')),

    constraint notification_target_check
        check (num_nonnulls(order_id, seller_order_id, refund_id) <= 1),

    -- 성공한 것에만 시각이 있고, 성공했으면 반드시 있다.
    constraint notification_sent_at_check
        check ((status = 'succeeded') = (sent_at is not null)),

    -- 실패한 것에만 이유가 있다.
    constraint notification_failure_reason_check
        check ((status = 'failed') = (failure_reason is not null))
);

comment on table notification is
    '알림 발송 이력의 메타. 본문은 notification_body 에 있고 그쪽이 먼저 사라진다(D18-1)';

-- 같은 사건에 두 번 안 보낸다.
--
-- **대상마다 따로 건다.** 셋을 한 유니크에 묶으면 안 쓰는 칸이 `null` 인데
-- Postgres 의 유니크는 `null` 을 서로 다른 값으로 봐서 막으려던 것이 그대로 통과한다.
-- `nulls not distinct` 로 끌 수도 있지만, 그러면 **대상이 아예 없는 알림**
-- (광고·비밀번호 재설정)이 `event_type` 하나로 유니크가 돼서 평생 한 번밖에 못 나간다.
--
-- 부분 유니크는 `batch_run` 의 성공 회차가 이미 쓰는 방식이다(`V42`).
create unique index notification_order_event_unique
    on notification (event_type, order_id) where order_id is not null;

create unique index notification_seller_order_event_unique
    on notification (event_type, seller_order_id) where seller_order_id is not null;

create unique index notification_refund_event_unique
    on notification (event_type, refund_id) where refund_id is not null;

-- 파기 배치가 종류와 나이로 고른다.
create index notification_kind_created_at_idx on notification (kind, created_at);

-- 개인화된 본문. **메타보다 먼저 사라진다**(`D18-1`).
--
-- 표를 갈라 둔 것이 강제 지점이다. 한 표에 두면 파기가 컬럼을 비우는 일이 되고,
-- 안 비운 행이 섞여도 아무 제약이 안 걸린다. 여기서는 행이 있거나 없거나 둘 중 하나다.
--
-- 기본키를 본체와 공유한다(`D22` 「1:1 로 붙는 확장 표」). 대리키를 얹으면 아무도 안 쓰는
-- 번호가 생기고 "발송당 하나" 를 유니크로 따로 적게 된다. `order_shipping` 과 같은 모양이다.
create table notification_body (
    notification_id bigint not null primary key
        references notification (notification_id) on delete cascade,

    -- 변수를 꽂아 완성한 글자. 판이 바뀌어도 그때 보낸 것이 남는다.
    subject text not null,
    body    text not null,

    created_at timestamptz not null default now()
);

comment on table notification_body is
    '개인화된 발송 본문. 이름·금액이 들어가서 여섯 달만 산다(D18-1)';
