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
create table app_user (
    id            bigint generated always as identity primary key,
    email         text        not null,
    password_hash text        not null,
    display_name  text        not null,
    status        text        not null default 'active',
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint app_user_status_check check (status in ('active', 'suspended', 'withdrawn'))
);

-- 대소문자만 다른 이메일로 두 번 가입하는 걸 막는다.
create unique index app_user_email_key on app_user (lower(email));

create trigger app_user_set_updated_at
    before update on app_user
    for each row execute function set_updated_at();

-- 역할. code 는 코드에서 참조하는 안정된 키고, name 은 화면에 보이는 이름이다.
create table role (
    id          bigint generated always as identity primary key,
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
    id          bigint      generated always as identity primary key,
    resource    text        not null,
    action      text        not null,
    description text        not null default '',
    created_at  timestamptz not null default now(),
    constraint permission_resource_action_key unique (resource, action)
);

create table user_role (
    user_id    bigint      not null references app_user (id) on delete cascade,
    role_id    bigint      not null references role (id) on delete restrict,
    granted_at timestamptz not null default now(),
    primary key (user_id, role_id)
);

-- 역할이 지워질 때 이 행이 남으면 권한이 유령으로 뜬다. 그래서 cascade 로 같이 지운다.
-- 반대로 user_role.role_id 는 restrict 다. 사용자가 달린 역할은 지우기 전에 회수부터 하게 만든다.
create table role_permission (
    role_id       bigint not null references role (id) on delete cascade,
    permission_id bigint not null references permission (id) on delete cascade,
    -- 행 단위 조건. own=자기 것, seller=자기 상품이 걸린 것, all=전체.
    -- 한 역할이 같은 권한을 두 스코프로 갖는 건 판정을 모호하게 만들어서 pk 로 막는다.
    scope         text   not null,
    primary key (role_id, permission_id),
    constraint role_permission_scope_check check (scope in ('own', 'seller', 'all'))
);

-- 두 테이블의 기본키가 (user_id, ...) (role_id, ...) 로 시작해서 판정 쿼리 방향은 이미 인덱스를 탄다.
-- 반대 방향, 즉 "이 역할을 가진 사용자 목록" 은 관리자 화면에서 쓰므로 따로 깔아 둔다.
create index user_role_role_id_idx on user_role (role_id);
