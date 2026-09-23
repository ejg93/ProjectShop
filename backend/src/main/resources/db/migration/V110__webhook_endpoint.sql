-- 셀러 웹훅 엔드포인트(`29`, `D12`·`D14`).
--
-- **시크릿의 평문 칸이 없다 — 그것이 이 표의 강제 지점이다.** 발송기(`30`)가 HMAC 을 만들려면 원문 키가 있어야 해서
-- 해시로는 못 둔다. 그래서 AES-GCM 암호문(`bytea`)과 키 판(`secret_key_version`)만 둔다. 키는 환경변수
-- `WEBHOOK_SECRET_KEY` 이고 표에 없다 — DB 덤프가 새도 시크릿이 안 샌다. 평문은 등록 응답에 한 번 나가고 끝이다.
--
-- **구독할 수 있는 사건은 넷이다**(2026-09-23 밤 번들 계획). 셀러 경계를 넘는 사건을 안 연다(`D14`) —
-- `shop.order.status_changed` 는 셀러 여럿에 걸치고, `shop.batch_run.finished` 는 내부 사건이고,
-- `shop.sku.stock_moved` 는 재고 원장이라 바깥에 안 낸다(`D9`). 목록은 `WebhookEventType` 과 짝이다(`EnumConstraintTest`).
create table webhook_endpoint (
    webhook_endpoint_id bigint not null generated always as identity primary key,

    -- 셀러가 지워지면 엔드포인트가 남을 이유가 없지만, 셀러 행은 지우지 않는다(`D13`) — restrict 로 둔다.
    seller_id bigint not null references seller (seller_id) on delete restrict,

    -- 받는 주소. `https` 만 받는 것은 앱(`WebhookUrlPolicy`)이 본다 — 시험은 로컬 `http` 서버로 받는다.
    -- 사설·루프백 대역을 막는 것도 앱이다: 이름이 가리키는 주소는 DNS 를 풀어야 알아서 한 행 안에서 못 본다.
    url text not null,

    event_types text[] not null,

    secret_ciphertext bytea not null,
    secret_key_version int not null,

    created_at timestamptz not null default now(),

    constraint webhook_endpoint_url_length_check check (length(url) between 1 and 2000),
    constraint webhook_endpoint_url_scheme_check check (url ~ '^https?://'),

    constraint webhook_endpoint_event_types_check
        check (cardinality(event_types) > 0
               and event_types <@ array['shop.seller_order.status_changed',
                                        'shop.refund.status_changed',
                                        'shop.return_request.status_changed',
                                        'shop.settlement.payout_changed']::text[]),

    constraint webhook_endpoint_secret_key_version_check check (secret_key_version > 0),

    -- 같은 셀러가 같은 주소를 두 번 걸면 사건 하나가 두 번 간다.
    constraint webhook_endpoint_seller_url_unique unique (seller_id, url)
);

comment on table webhook_endpoint is
    '셀러 웹훅 엔드포인트(29). 시크릿은 AES-GCM 암호문만 둔다 — 평문 칸이 없다';
comment on column webhook_endpoint.secret_ciphertext is
    'IV(12바이트) 뒤에 암호문과 태그. 키는 WEBHOOK_SECRET_KEY 이고 secret_key_version 판이다';

-- 권한. 대표만 자기 셀러에 건다 — 시크릿을 다루는 자리라 직원(`seller_staff`)에게 안 준다.
-- 관리자는 전부(`V3` 의 관례), 감사자의 거부는 `V75` 트리거가 단다(쓰기 권한이다).
insert into permission (resource, action, kind, description) values
    ('webhook', 'manage', 'write', '셀러 웹훅 엔드포인트를 등록·조회·삭제하고 발송을 다시 보낸다');

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'webhook' and p.action = 'manage'
 where r.code = 'seller_owner';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'webhook' and p.action = 'manage'
 where r.code = 'admin';

-- 대표는 seller 범위로만, 관리자는 all 로만 열렸는지 본다. 빠뜨려도 아무 오류가 안 난다 —
-- 대표에게 all 이 가면 남의 셀러 엔드포인트를 걸고 남의 사건을 받는다.
do $$
declare opened text;
begin
    select string_agg(r.code || ':' || rp.scope, ', ' order by r.code) into opened
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'webhook' and rp.effect = 'allow'
       and not ((r.code = 'seller_owner' and rp.scope = 'seller')
                or (r.code = 'admin' and rp.scope = 'all'));

    if opened is not null then
        raise exception '웹훅 권한이 정한 범위 밖으로 열렸다: %', opened;
    end if;
end $$;
