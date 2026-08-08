-- 셀러는 사람이 아니라 조직이다. 한 셀러에 계정이 여럿 붙고, 그 안에서 하는 일이 갈린다.
-- 이 마이그레이션으로 역할 부여에 대상이 생긴다. "CS 담당" 이 아니라 "A셀러의 CS 담당" 이다.

-- 신원정보(R1)를 여기 둔다. 마켓플레이스라 이 값이 셀러마다 다르다.
--
-- 중개자에게는 표시 의무보다 센 것이 걸린다. 전자상거래법 제20조② 가
-- "중개의뢰자가 사업자면 신원정보를 확인하여 청약 전까지 소비자에게 제공" 하라고 하고,
-- 제20조의2 가 안 했을 때의 연대책임을 붙인다.
--
-- 그래서 "언제부터 필수인가" 는 우리가 정할 것이 아니다. 판매 개시 전이다.
-- status 로 그 경계를 표현하고 아래 check 가 막는다.
create table seller (
    seller_id  bigint      generated always as identity primary key,
    code       text        not null unique,
    name       text        not null,

    -- pending 으로 시작한다. 정보가 안 찬 셀러가 파는 일이 없게 하려는 것이다.
    status     text        not null default 'pending',

    -- 법정 상호. name 은 화면에 쓰는 이름이라 상호와 다를 수 있다.
    business_name       text,
    representative_name text,

    -- 하이픈 없이 숫자 10자리로 담는다. 표시할 때 넣는다.
    business_reg_no     text,

    address text,
    phone   text,
    email   text,

    -- 통신판매업 신고번호. not null 이 아니다.
    --
    -- 제12조 단서가 공정위 고시에 면제 기준을 위임했고, 직전연도 거래 50회 미만이거나
    -- 간이과세자면 신고를 안 해도 된다. not null 로 두면 합법적인 셀러가 등록을 못 한다.
    --
    -- 면제 사유를 같이 받는다. 안 받으면 "아직 안 넣은 것" 과 "면제라 없는 것" 이 안 갈린다.
    mail_order_no            text,
    mail_order_exempt_reason text,

    -- 기본 수수료율. 상품에서 덮어쓸 수 있다(D3). 1000 = 10.00%(D8).
    commission_bp int not null default 1000,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint seller_status_check
        check (status in ('pending', 'active', 'suspended', 'closed')),

    constraint seller_commission_bp_check
        check (commission_bp between 0 and 10000),

    constraint seller_business_reg_no_check
        check (business_reg_no is null or business_reg_no ~ '^[0-9]{10}$'),

    constraint seller_exempt_reason_check
        check (mail_order_exempt_reason is null
               or mail_order_exempt_reason in ('under_50_transactions', 'simplified_taxpayer')),

    -- 신고번호와 면제 사유가 같이 있으면 어느 쪽이 사실인지 알 수 없다.
    constraint seller_mail_order_check
        check (mail_order_no is null or mail_order_exempt_reason is null),

    -- 판매를 시작하려면 신원정보가 채워져 있어야 한다(제20조②).
    -- 신고번호는 둘 중 하나면 된다 — 번호가 있거나, 면제 사유가 있거나.
    constraint seller_verified_fields_check check (
        status <> 'active'
        or (business_name is not null
            and representative_name is not null
            and business_reg_no is not null
            and address is not null
            and phone is not null
            and email is not null
            and (mail_order_no is not null or mail_order_exempt_reason is not null))
    )
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
