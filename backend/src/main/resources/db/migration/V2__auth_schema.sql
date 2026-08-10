-- 권한을 코드가 아니라 데이터로 두는 테이블들이다.
-- 새 역할은 행을 넣어서 만들고 배포하지 않는다.
--
-- user ─< user_role >─ role ─< role_permission >─ permission
--                              (scope)              (resource + action)

-- updated_at 을 손으로 넣는 걸 잊어도 값이 어긋나지 않게 트리거로 박는다.
create or replace function set_updated_at() returns trigger as $$
begin
    new.updated_at := now();
    return new;
end;
$$ language plpgsql;

-- user 는 SQL 예약어라서 app_user 로 쓴다.
--
-- 이메일·이름·비밀번호 해시가 not null 이 아닌 이유는 파기 때문이다(R9).
-- 탈퇴 유예 30일이 지나면 이 셋을 null 로 비운다(D13, 청크 5i).
-- 행 자체는 안 지운다 — 주문이 user_id 로 계정을 가리키고 주문은 5년 남는다.
--
-- 그렇다고 보장을 버리지 않는다. 원래 지키려던 것은 "모든 계정에 이메일이 있다" 가 아니라
-- "살아 있는 계정에 이메일이 있다" 였다. 컬럼 제약으로는 그것을 못 적어서 not null 로 근사했던 것이고,
-- 행 조건은 check 로 정확히 적힌다. 제약을 뺀 것이 아니라 맞는 제약으로 바꾼 것이다.
--
-- 수명(deleted_at)과 업무 상태(status)를 한 컬럼에 안 섞는다(D4·D13).
-- 섞으면 복구할 때 이전 상태를 잃고, "살아 있는 것" 조건이 상태가 늘 때마다 흔들린다.
-- 수명이 별도 컬럼이면 그 조건이 deleted_at is null 하나로 고정된다.
create table app_user (
    user_id       bigint generated always as identity primary key,
    email         text,
    password_hash text,
    display_name  text,

    -- 업무 상태만 담는다. 탈퇴는 여기가 아니라 deleted_at 이다.
    status        text        not null default 'active',

    -- 수명. null 이면 존재한다.
    deleted_at    timestamptz,

    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),

    constraint app_user_status_check check (status in ('active', 'suspended')),

    -- 살아 있는 계정에는 이 셋이 있다. not null 이 근사했던 조건을 그대로 적은 것이다.
    -- 없으면 가입 코드의 버그로 이메일 없는 계정이 생겨도 DB 가 안 막는다.
    constraint app_user_alive_fields_check check (
        deleted_at is not null
        or (email is not null and password_hash is not null and display_name is not null)
    )
);

comment on column app_user.deleted_at is '수명. null 이면 존재한다. 업무 상태(status)와 축이 다르다';

-- 살아 있는 행만 고르는 조회가 대부분이라 부분 인덱스를 깐다.
create index app_user_alive_idx on app_user (user_id) where deleted_at is null;

-- 파기 배치(5i)가 대상을 고르는 조건이다. 아직 안 비운 탈퇴 계정만 들어간다.
-- 비우고 나면 인덱스에서 빠져서, 두 번 돌아도 훑을 것이 없다.
create index app_user_purge_idx on app_user (deleted_at)
 where deleted_at is not null and email is not null;

-- 대소문자만 다른 이메일로 두 번 가입하는 걸 막는다.
--
-- null 을 빼는 부분 인덱스다. 안 그러면 파기된 계정끼리 null 로 충돌한다(ADR 0007).
-- 가짜 값으로 덮는 방법(user_2831@deleted.invalid)도 저울질했는데 그것도 데이터라
-- 파기했다고 말하기 애매하다.
create unique index app_user_email_key on app_user (lower(email)) where email is not null;

create trigger app_user_set_updated_at
    before update on app_user
    for each row execute function set_updated_at();

-- 역할. code 는 코드에서 참조하는 안정된 키고, name 은 화면에 보이는 이름이다.
create table role (
    role_id     bigint generated always as identity primary key,
    code        text        not null unique,
    name        text        not null,
    description text        not null default '',
    -- 시스템 역할은 관리자 화면에서 지우지 못하게 막는다. 지우면 로그인할 사람이 없어진다.
    is_system   boolean     not null default false,

    -- 전역 역할(관리자·고객)은 대상이 없고, 조직 역할(셀러 쪽)은 반드시 셀러 하나를 가리킨다.
    -- 그 검사는 user_role 에 걸린 트리거가 한다(V4).
    is_org_role boolean     not null default false,

    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create trigger role_set_updated_at
    before update on role
    for each row execute function set_updated_at();

-- 권한. 무엇(resource)에 어떤 조작(action)을 하느냐로 쪼갠다.
create table permission (
    permission_id bigint    generated always as identity primary key,
    resource    text        not null,
    action      text        not null,
    description text        not null default '',
    created_at  timestamptz not null default now(),
    constraint permission_resource_action_key unique (resource, action)
);

-- 역할이 지워질 때 이 행이 남으면 권한이 유령으로 뜬다. 그래서 cascade 로 같이 지운다.
--
-- 효과(effect)를 두는 이유는 허용만 쌓으면 "이 역할이 허용해도 저 역할이 막는다" 를 못 적어서다.
-- 사용자는 역할을 여럿 가질 수 있고, 권한을 안 주는 것과 deny 로 막는 것은 결과가 다르다.
create table role_permission (
    role_id       bigint not null references role (role_id) on delete cascade,
    permission_id bigint not null references permission (permission_id) on delete cascade,
    -- 행 단위 조건. own=자기 것, seller=자기 상품이 걸린 것, all=전체.
    scope         text   not null,
    effect        text   not null default 'allow',

    -- 효과가 기본키에 들어간다. 안 넣으면 같은 역할·권한에 allow 와 deny 를 같이 못 달아서
    -- "전체는 허용하되 일부는 막는다" 가 표현되지 않는다.
    -- 효과당 스코프는 하나로 유지한다 — 같은 효과가 두 스코프로 잡히면 규칙이 하나 더 필요해진다.
    primary key (role_id, permission_id, effect),

    constraint role_permission_scope_check  check (scope in ('own', 'seller', 'all')),
    constraint role_permission_effect_check check (effect in ('allow', 'deny'))
);

comment on column role_permission.effect is
    'allow=허용, deny=거부. 판정에서 deny 가 allow 를 이긴다';

comment on column role_permission.scope is
    'allow 면 허용 범위, deny 면 거부 범위. own=자기 것, seller=자기 상품이 걸린 것, all=전체';

-- user_role 은 이 파일에 없다. 역할 부여가 셀러를 가리킬 수 있어서(V4) 그 테이블이 선 뒤에 만든다.
-- 여기서 만들고 나중에 컬럼을 붙이면 한 테이블의 모양이 두 파일로 흩어진다.
