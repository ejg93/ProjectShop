-- 셀러 초대와 담당자 역할(`5a`).
--
-- 셀러는 조직이라 계정이 여럿 붙는다(`V4`). 그 계정이 **어떻게 붙나**가 여기서 선다.
-- 지금까지는 붙이는 길이 없어서 시드가 직접 `seller_member` 에 넣는 것뿐이었다.
--
-- **소속과 역할이 한 번에 들어간다.** 둘을 따로 넣으면 소속만 있고 역할이 없는 계정이나
-- 그 반대가 생기는데, 앞은 아무것도 못 하고 뒤는 `V4` 의 트리거가 막아서 실패한다.
-- 수락 한 번이 둘을 같이 넣는 것이 `SellerMemberService.accept` 다.
--
-- **토큰 원문을 안 담는다.** 표가 새면 그 값으로 남의 셀러에 들어갈 수 있어서
-- 비밀번호와 같은 취급을 한다 — 해시만 저장하고 대조도 해시로 한다(`D14`).
-- `password_reset_token`(`V67`)과 같은 모양이고 같은 이유다.
create table seller_invitation (
    seller_invitation_id bigint generated always as identity primary key,

    seller_id bigint not null references seller (seller_id) on delete cascade,

    -- 초대를 받는 주소. **계정이 아직 없을 수 있어서** user_id 가 아니라 주소로 받는다 —
    -- 아직 회원이 아닌 사람을 부르는 것이 초대의 절반이다.
    email text not null,

    -- 수락하면 줄 역할. **조직 역할만 온다** — 아래 트리거가 막는다.
    -- 전역 역할이 여기로 들어오면 셀러 하나를 가리키는 초대가 전역 권한을 주게 된다.
    role_id bigint not null references role (role_id) on delete restrict,

    -- 초대한 사람. 누가 불렀는지가 남아야 잘못 들어온 계정의 출처를 찾는다.
    invited_by_user_id bigint not null references app_user (user_id) on delete restrict,

    -- 토큰 원문의 해시. 대조는 이 값으로만 한다.
    token_hash text not null,

    issued_at  timestamptz not null default now(),

    -- 이 시각을 지나면 못 쓴다. 발급할 때 박제한다 — 수명 규칙이 바뀌어도 이미 나간 것은 안 바뀐다.
    expires_at timestamptz not null,

    -- 수락 시각과 수락한 계정. 둘은 같이 찬다(아래 사슬 검사).
    accepted_at      timestamptz,
    accepted_user_id bigint references app_user (user_id) on delete set null,

    -- 취소 시각. 부른 쪽이 거둬들인 것이다.
    revoked_at timestamptz,

    constraint seller_invitation_email_length_check
        check (length(email) between 3 and 254),

    constraint seller_invitation_token_hash_length_check
        check (length(token_hash) between 1 and 200),

    -- 수명이 0 이하일 수 없다.
    constraint seller_invitation_expiry_after_issued_check
        check (expires_at > issued_at),

    constraint seller_invitation_accepted_after_issued_check
        check (accepted_at is null or accepted_at >= issued_at),

    constraint seller_invitation_revoked_after_issued_check
        check (revoked_at is null or revoked_at >= issued_at),

    -- 수락은 시각과 계정이 사슬이다. 한쪽만 찬 행이 안 생긴다 —
    -- 「수락됐는데 누가 받았는지 모르는」 초대는 감사에서 아무것도 못 답한다.
    constraint seller_invitation_accept_check
        check ((accepted_at is null) = (accepted_user_id is null)),

    -- 끝나는 길은 하나다. 수락과 취소가 같이 찰 수 없다.
    constraint seller_invitation_outcome_check
        check (accepted_at is null or revoked_at is null)
);

comment on column seller_invitation.email is '초대받는 주소. 계정이 아직 없을 수 있어서 user_id 가 아니다';

-- 같은 해시가 둘일 수 없다.
--
-- **조회 경로가 아니다.** 인코더가 매번 다른 소금을 써서 원문으로는 해시를 못 만들고,
-- 그래서 `SellerMemberService` 는 살아 있는 것을 꺼내 하나씩 대 본다(`V67` 과 같다).
create unique index seller_invitation_token_hash_key on seller_invitation (token_hash);

create index seller_invitation_seller_id_idx on seller_invitation (seller_id);

-- 살아 있는 초대는 (셀러, 주소) 당 하나다.
--
-- 앱에서만 세면 두 번 눌러 두 건이 되고, 그러면 하나를 취소해도 나머지로 들어온다.
-- 주소는 대소문자를 안 가린다 — `app_user` 조회가 `lower(email)` 로 도는 것과 같은 축이다.
create unique index seller_invitation_live_unique
    on seller_invitation (seller_id, lower(email))
    where accepted_at is null and revoked_at is null;

-- 초대로는 조직 역할만 준다.
--
-- `check` 로 못 내린다 — 다른 표(`role`)를 봐야 해서 행 검사의 범위를 넘는다.
-- `V4` 의 `check_user_role_target` 이 같은 이유로 트리거인 자리고, 여기도 같다.
create or replace function check_seller_invitation_role() returns trigger as $$
declare
    org_role boolean;
begin
    select is_org_role into org_role from role where role_id = new.role_id;

    if not org_role then
        raise exception '초대로는 조직 역할만 준다 (role_id=%)', new.role_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger seller_invitation_role_check
    before insert or update on seller_invitation
    for each row execute function check_seller_invitation_role();

-- 멤버 구성을 만지는 권한. **이것이 대표와 담당자를 가르는 자리다.**
--
-- 이 권한이 없으면 담당자는 자기 셀러의 일을 하면서도 사람을 더 부르거나 내보내지 못한다.
-- 권한을 안 만들고 「담당자는 멤버를 못 바꾼다」를 코드에만 두면, 입구가 하나 더 생길 때
-- 그 검사를 빠뜨린다(`D23` 축 2 — 판정이 3위고 코드 조건은 그 아래다).
--
-- `kind` 는 기본값이 없다(`V75`). 넣는 사람이 매번 정하고, `write` 면
-- `permission_denies_read_only_roles` 가 감사자 거부를 **자동으로** 단다 —
-- 그 행을 손으로 안 넣는다.
insert into permission (resource, action, kind, description) values
    ('seller_member', 'read',   'read',  '셀러에 속한 사람의 목록을 조회한다'),
    ('seller_member', 'manage', 'write', '셀러에 사람을 초대하고 역할을 주거나 회수한다');

-- 셀러 담당자. 대표가 부르는 사람이다.
--
-- 대표와 갈리는 것이 둘이다 — **멤버를 못 바꾸고**(위 권한을 안 받는다),
-- **상품은 자기가 등록한 것만 고친다**(`own`). 뒤엣것은 `V3` 주석이 이 청크를 지목해 뒀다:
-- "own 은 담당자 축에서 쓴다. product.created_by_user_id 를 가리키고,
--  '내가 등록한 상품만' 이 필요한 seller_staff 가 청크 5a 에서 그걸 받는다".
--
-- 등록은 `seller` 다. 자기가 등록한 상품만 만들 수 있다는 말은 뜻이 없다 —
-- 만들기 전에는 주인이 없어서 `own` 이 아무것도 안 덮는다(`Scope.OWN`).
insert into role (code, name, description, is_system, is_org_role) values
    ('seller_staff', '셀러 담당자', '자기가 등록한 상품을 고치고 그 셀러의 주문을 처리한다', true, true);

insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, v.scope
from (values
    ('product',       'create',        'seller'),
    ('product',       'read',          'all'),
    ('product',       'update',        'own'),
    ('product',       'delete',        'own'),
    ('order',         'create',        'own'),
    ('order',         'read',          'seller'),
    ('order',         'update_status', 'seller'),
    ('payment',       'read',          'seller'),
    ('user',          'read',          'own'),
    ('user',          'update',        'own'),
    ('seller_member', 'read',          'seller')
) as v (resource, action, scope)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'seller_staff';

-- 대표는 멤버를 부르고 내보낸다. 자기 셀러 안에서만이다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'seller'
from (values
    ('seller_member', 'read'),
    ('seller_member', 'manage')
) as v (resource, action)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'seller_owner';

-- 관리자는 새 권한을 그때마다 받는다. `V3` 의 전체 부여는 그때 있던 권한만 덮었다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin'
where p.resource = 'seller_member';

-- 감사자의 **조회 허용**만 손으로 넣는다(`V5` 와 같은 모양).
-- `manage` 거부는 위 `insert into permission` 이 트리거를 깨워 이미 들어갔다 —
-- 여기서 또 넣으면 트리거가 막는 구멍을 사람이 다시 여는 자리가 된다(`Q59`).
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
from permission p
join role r on r.code = 'auditor'
where p.resource = 'seller_member' and p.action = 'read';
