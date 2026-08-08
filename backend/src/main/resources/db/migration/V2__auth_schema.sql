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
create table app_user (
    user_id       bigint generated always as identity primary key,
    email         text,
    password_hash text,
    display_name  text,
    status        text        not null default 'active',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint app_user_status_check check (status in ('active', 'suspended', 'withdrawn'))
);

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

create table user_role (
    user_id    bigint      not null references app_user (user_id) on delete cascade,
    role_id    bigint      not null references role (role_id) on delete restrict,
    granted_at timestamptz not null default now(),
    primary key (user_id, role_id)
);

-- 역할이 지워질 때 이 행이 남으면 권한이 유령으로 뜬다. 그래서 cascade 로 같이 지운다.
-- 반대로 user_role.role_id 는 restrict 다. 사용자가 달린 역할은 지우기 전에 회수부터 하게 만든다.
create table role_permission (
    role_id       bigint not null references role (role_id) on delete cascade,
    permission_id bigint not null references permission (permission_id) on delete cascade,
    -- 행 단위 조건. own=자기 것, seller=자기 상품이 걸린 것, all=전체.
    -- 한 역할이 같은 권한을 두 스코프로 갖는 건 판정을 모호하게 만들어서 pk 로 막는다.
    scope         text   not null,
    primary key (role_id, permission_id),
    constraint role_permission_scope_check check (scope in ('own', 'seller', 'all'))
);

-- 두 테이블의 기본키가 (user_id, ...) (role_id, ...) 로 시작해서 판정 쿼리 방향은 이미 인덱스를 탄다.
-- 반대 방향, 즉 "이 역할을 가진 사용자 목록" 은 관리자 화면에서 쓰므로 따로 깔아 둔다.
create index user_role_role_id_idx on user_role (role_id);
