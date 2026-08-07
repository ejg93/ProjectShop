-- 셀러는 사람이 아니라 조직이다. 한 셀러에 계정이 여럿 붙고, 그 안에서 하는 일이 갈린다.
-- 이 마이그레이션으로 역할 부여에 대상이 생긴다. "CS 담당" 이 아니라 "A셀러의 CS 담당" 이다.

create table seller (
    seller_id  bigint      generated always as identity primary key,
    code       text        not null unique,
    name       text        not null,
    status     text        not null default 'active',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint seller_status_check check (status in ('active', 'suspended', 'closed'))
);

create trigger seller_set_updated_at
    before update on seller
    for each row execute function set_updated_at();

-- 소속과 권한을 가른다. 이 표는 "누가 이 셀러 사람인가" 만 답한다.
-- 그 사람이 무엇을 할 수 있는지는 user_role 이 답한다.
create table seller_member (
    seller_id bigint      not null references seller (seller_id) on delete cascade,
    user_id   bigint      not null references app_user (user_id) on delete cascade,
    joined_at timestamptz not null default now(),
    primary key (seller_id, user_id)
);

create index seller_member_user_id_idx on seller_member (user_id);

-- 역할을 전역용과 조직용으로 가른다.
-- 전역 역할(관리자, 고객)은 대상이 없고, 조직 역할(판매자 쪽)은 반드시 셀러 하나를 가리킨다.
alter table role add column is_org_role boolean not null default false;

update role set is_org_role = true where code = 'seller';

-- user_role 에 대상을 붙인다. null 이면 전역 부여다.
-- 널을 포함한 컬럼은 기본키에 못 들어가서 대리키로 바꾸고, 중복은 유니크 인덱스로 막는다.
alter table user_role drop constraint user_role_pkey;
alter table user_role add column user_role_id bigint generated always as identity primary key;
alter table user_role add column seller_id bigint references seller (seller_id) on delete cascade;

create unique index user_role_unique_grant
    on user_role (user_id, role_id, coalesce(seller_id, 0));

create index user_role_seller_id_idx on user_role (seller_id);

-- 역할의 종류와 대상 유무가 어긋나는 행을 막는다.
-- 이걸 애플리케이션에만 맡기면 관리자 화면과 배치가 각각 검사해야 하고, 한쪽을 빠뜨리면 판정이 조용히 어긋난다.
create or replace function check_user_role_target() returns trigger as $$
declare
    org_role boolean;
begin
    select is_org_role into org_role from role where role_id = new.role_id;

    if org_role and new.seller_id is null then
        raise exception '조직 역할은 셀러를 지정해야 한다 (role_id=%)', new.role_id;
    end if;

    if not org_role and new.seller_id is not null then
        raise exception '전역 역할에는 셀러를 지정할 수 없다 (role_id=%)', new.role_id;
    end if;

    -- 셀러에 속하지 않은 사람에게 그 셀러의 역할을 주면 소속과 권한이 어긋난다.
    if new.seller_id is not null
       and not exists (select 1 from seller_member
                       where seller_id = new.seller_id and user_id = new.user_id) then
        raise exception '셀러 소속이 아닌 사용자다 (user_id=%, seller_id=%)', new.user_id, new.seller_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger user_role_check_target
    before insert or update on user_role
    for each row execute function check_user_role_target();
