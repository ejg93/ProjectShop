-- 셀러 답글과 후기 신고, 그리고 후기 운영정책 고지(`48`).
--
-- **법이 하나 걸려 있다.** 전자상거래법 제21조의4 가 후기를 게시하는 사업자에게
-- **게시기간·등급평가 및 삭제 기준·삭제 시 이의제기 절차**를 공개하라고 한다(`D2` `R27`).
-- 후기를 어떻게 다루는지가 아니라 **그 규칙을 알리는 것**이 의무다.
--
-- 그래서 **기준을 코드로 정하는 것과 그 기준을 공개하는 것이 한 청크다** — 기준만 정하고
-- 안 알리면 이 조문을 어긴다. 아래 4절이 그 공개다.

-- ---------------------------------------------------------------------------
-- 1. 셀러 답글
-- ---------------------------------------------------------------------------

-- 후기 하나에 답글 하나다. 여러 개를 허용하면 셀러가 같은 자리에 글을 쌓아
-- **후기를 밀어내는 자리**가 된다.
--
-- 표를 가르는 이유는 수명이 달라서다 — 답글은 나중에 생기고 먼저 사라질 수 있다.
create table review_reply (
    review_reply_id bigint not null generated always as identity primary key,

    -- 후기가 없으면 답글도 뜻이 없다. 애그리거트 안쪽이라 cascade 다.
    review_id bigint not null unique
        references review (review_id) on delete cascade,

    -- 답한 사람. 그 셀러에 속한 계정이어야 하고 아래 트리거가 막는다.
    user_id bigint not null
        references app_user (user_id) on delete restrict,

    body text not null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,

    constraint review_reply_body_length_check
        check (length(body) between 1 and 2000)
);

create trigger review_reply_set_updated_at
    before update on review_reply
    for each row execute function set_updated_at();

-- **「자기 상품」을 스코프가 아니라 여기서도 막는다.**
--
-- 판정의 `seller` 스코프가 입구를 막지만, 그것은 **판정을 지나는 경로에만** 걸린다.
-- 남의 상품 후기에 답글이 달리면 화면에서는 그 셀러의 말처럼 보이고,
-- 그것이 곧 **남의 상품 페이지에 끼어드는 자리**다.
create or replace function check_review_reply_seller() returns trigger as $$
declare
    owner_seller_id bigint;
begin
    select p.seller_id into owner_seller_id
      from review r
      join product p on p.product_id = r.product_id
     where r.review_id = new.review_id;

    if not exists (select 1 from seller_member
                    where seller_id = owner_seller_id and user_id = new.user_id) then
        raise exception '자기 상품의 후기에만 답한다 (review_id=%, user_id=%)',
            new.review_id, new.user_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger review_reply_check_seller
    before insert or update on review_reply
    for each row execute function check_review_reply_seller();

-- ---------------------------------------------------------------------------
-- 2. 신고와 내림
-- ---------------------------------------------------------------------------

-- 내린 후기. `inquiry`(`V53`·`V58`)와 같은 모양이고 같은 이유다.
alter table review add column blocked_at     timestamptz;
alter table review add column blocked_reason text;

-- **셋을 사슬로 묶는다.** 「내렸나 = (시각과 사유가 둘 다 있나)」로 쓰면
-- **사유만 매달아 둔 행이 통과한다** — 한쪽이 비어서 오른쪽이 거짓이 되고
-- 안 내린 것도 거짓이라 등식이 맞아 버린다.
alter table review add constraint review_block_check
    check ((blocked_at is not null) = (blocked_reason is not null));

-- 우리가 정한 사유다(약관). **법에서 온 것이 아니라는 것을 값으로 남긴다** —
-- `inquiry` 는 `advertisement` 가 정보통신망법 제50조의7 에서 왔고 여기는 그런 값이 없다.
alter table review add constraint review_blocked_reason_check
    check (blocked_reason is null
           or blocked_reason in ('advertisement', 'abuse', 'unrelated', 'privacy'));

-- 내린 후기는 목록에 안 나간다. 조회 인덱스의 조건을 같이 좁힌다.
drop index review_product_id_idx;
create index review_product_id_idx on review (product_id, created_at desc)
    where deleted_at is null and blocked_at is null;

-- 신고. **한 사람이 같은 후기를 한 번만 신고한다** — 여러 번 허용하면
-- 신고 수가 여론이 아니라 한 사람의 끈기를 센다.
create table review_report (
    review_report_id bigint not null generated always as identity primary key,

    review_id bigint not null
        references review (review_id) on delete cascade,

    reporter_user_id bigint not null
        references app_user (user_id) on delete restrict,

    -- 신고 사유. 위 `review_blocked_reason_check` 와 같은 목록이다 —
    -- 신고한 사유로 내리므로 값이 갈리면 옮길 때 사람이 다시 고른다.
    reason text not null,

    -- 접수되면 `pending`. 관리자가 보고 `accepted` 또는 `rejected` 로 옮긴다(`D7`).
    status text not null default 'pending',

    created_at timestamptz not null default now(),

    -- 처리한 시각과 사람. 상태와 사슬로 묶인다.
    resolved_at      timestamptz,
    resolved_by_user_id bigint references app_user (user_id) on delete restrict,

    constraint review_report_reason_check
        check (reason in ('advertisement', 'abuse', 'unrelated', 'privacy')),

    constraint review_report_status_check
        check (status in ('pending', 'accepted', 'rejected')),

    -- 처리된 신고에는 시각과 사람이 있고, 접수 상태에는 없다.
    constraint review_report_resolution_check
        check ((status = 'pending') = (resolved_at is null)
               and (resolved_at is null) = (resolved_by_user_id is null)),

    constraint review_report_once unique (review_id, reporter_user_id)
);

create index review_report_pending_idx on review_report (created_at)
    where status = 'pending';

-- 처리한 신고는 다시 못 연다. **전이표를 코드가 아니라 여기에도 둔다**(`D7`) —
-- 되돌릴 수 있으면 「거절했다가 조용히 승인」이 가능하고, 그 사이의 판단이 기록에서 사라진다.
create or replace function check_review_report_transition() returns trigger as $$
begin
    if old.status <> 'pending' and new.status <> old.status then
        raise exception '처리한 신고는 다시 못 연다 (review_report_id=%, %→%)',
            old.review_report_id, old.status, new.status;
    end if;
    return new;
end;
$$ language plpgsql;

create trigger review_report_transition_check
    before update on review_report
    for each row execute function check_review_report_transition();

-- ---------------------------------------------------------------------------
-- 3. 누가 답하고 누가 신고하고 누가 내리나
-- ---------------------------------------------------------------------------

insert into permission (resource, action, kind, description) values
    ('review', 'reply',    'write', '자기 상품에 달린 후기에 답한다'),
    ('review', 'report',   'write', '후기를 신고한다'),
    ('review', 'moderate', 'write', '신고를 처리하고 후기를 내린다');

-- 셀러는 자기 것만 답한다. **스코프가 `seller` 다** — `all` 로 주면 남의 상품에 끼어든다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'seller'
from permission p
join role r on r.code in ('seller_owner', 'seller_staff')
where p.resource = 'review' and p.action = 'reply';

-- 신고는 **남의 글을 대상으로 한다.** `own` 으로 주면 자기 후기만 신고하게 돼서
-- 아무 뜻이 없다 — 대상을 안 보는 `all` 이고, 한 번만 신고하는 것은 유니크가 든다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code in ('customer', 'seller_owner', 'seller_staff')
where p.resource = 'review' and p.action = 'report';

-- 내리는 것은 관리자만이다. **셀러에게 열면 불리한 후기를 내리는 자리가 같이 생긴다** —
-- `inquiry:block` 을 관리자만으로 둔 것과 같은 판단(`V58`).
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin'
where p.resource = 'review' and p.action in ('reply', 'report', 'moderate');

-- ---------------------------------------------------------------------------
-- 4. R27 — 규칙을 공개한다
-- ---------------------------------------------------------------------------

-- 제21조의4 가 요구하는 넷을 한 문서에 담는다. **기준을 정한 청크가 같이 공개한다** —
-- 미루면 그 사이가 위반 구간이다.
insert into policy_document (code, version, title, body, effective_at) values
    ('review_policy', 1, '후기 운영정책', $doc$
## 게시기간

후기는 게시된 때부터 **거래 기록이 보존되는 5년** 동안 게시됩니다.
주문 기록이 보존기간을 지나 파기되면 그 주문에 달린 후기도 함께 사라집니다.

## 등급평가 기준

별점은 1점에서 5점까지이며, 작성자가 고른 값을 그대로 표시합니다.
평균 계산에서 특정 후기에 가중치를 주거나 제외하지 않습니다.

## 삭제 기준

다음 두 가지 경우에 후기가 내려갑니다.

1. **작성자가 직접 삭제한 경우.** 본인이 쓴 후기는 언제든 삭제할 수 있습니다.
2. **신고가 접수되어 관리자가 아래 사유에 해당한다고 판단한 경우.**
   - 광고 또는 홍보 목적의 게시물
   - 욕설·비방 등 타인을 해치는 표현
   - 구매한 상품과 관련이 없는 내용
   - 다른 사람의 개인정보가 포함된 내용

셀러는 후기를 삭제할 수 없습니다. 답글로만 의견을 밝힐 수 있습니다.

## 삭제 시 이의제기 절차

후기가 내려가면 작성자에게 그 사실과 사유를 알립니다.
이의가 있으면 **고객센터 문의**로 접수해 주세요. 접수된 이의는 관리자가 다시 확인하고,
판단이 뒤집히면 후기를 원래대로 되돌립니다.
$doc$, now());
