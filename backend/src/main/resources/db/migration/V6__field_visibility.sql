-- 행 단위 권한만으로는 부족한 자리가 있다.
-- 셀러가 주문을 보는 건 제3자 제공이라(doc/reference/commerce-compliance.md R8)
-- 배송에 필요한 항목까지만 보여야 하고, 결제 수단은 볼 이유가 없다.
-- "주문을 읽을 수 있다" 는 지금 모델로 표현되지만 "주문의 이 부분만" 은 표현되지 않는다.

-- 필드를 하나씩 관리하면 컬럼이 늘 때마다 데이터가 늘고 빠뜨린 컬럼이 조용히 새어 나간다.
-- 민감도가 같은 것끼리 묶어서 그룹 단위로 다룬다.
create table permission_field_group (
    id          bigint      generated always as identity primary key,
    resource    text        not null,
    code        text        not null,
    description text        not null default '',
    created_at  timestamptz not null default now(),
    constraint permission_field_group_key unique (resource, code)
);

insert into permission_field_group (resource, code, description) values
    ('order', 'basic',    '주문번호, 금액, 상태, 일시'),
    ('order', 'shipping', '수령인, 연락처, 배송지 주소'),
    ('order', 'payment',  '결제 수단, 승인번호'),
    ('user',  'basic',    '표시 이름, 가입일'),
    ('user',  'contact',  '전자우편, 연락처');

-- 어느 규칙이 어느 그룹까지 보게 하나.
-- role_permission 의 기본키가 셋이라 외래키도 셋을 함께 참조한다.
-- 효과까지 참조하는 이유는 allow 와 deny 가 서로 다른 그룹을 가리킬 수 있어서다.
create table role_permission_field (
    role_id        bigint not null,
    permission_id  bigint not null,
    effect         text   not null,
    field_group_id bigint not null references permission_field_group (id) on delete cascade,
    primary key (role_id, permission_id, effect, field_group_id),
    foreign key (role_id, permission_id, effect)
        references role_permission (role_id, permission_id, effect) on delete cascade
);

create index role_permission_field_group_idx on role_permission_field (field_group_id);

-- 연결이 하나도 없는 규칙은 제한이 없는 것으로 본다.
-- 반대로 두면 필드 그룹을 정의하지 않은 자원까지 전부 연결을 달아야 하고, 빠뜨리면 화면이 빈다.
-- 대신 새 자원에 그룹을 정의할 때 그 자원의 기존 규칙에 연결을 다는 것이 같이 와야 한다.
-- 안 그러면 그룹은 있는데 아무도 제한받지 않는 상태가 된다.

-- 고객은 자기 주문이라 전부 본다. 그룹을 명시해서 "제한이 없어서 다 보이는 것" 과 구분한다.
insert into role_permission_field (role_id, permission_id, effect, field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.id
from role_permission rp
join role r on r.id = rp.role_id and r.code = 'customer'
join permission p on p.id = rp.permission_id and p.resource = 'order' and p.action = 'read'
join permission_field_group g on g.resource = 'order'
where rp.effect = 'allow';

-- 판매자는 배송에 필요한 것까지만 본다. payment 그룹을 안 붙이는 것이 이 마이그레이션의 핵심이다.
insert into role_permission_field (role_id, permission_id, effect, field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.id
from role_permission rp
join role r on r.id = rp.role_id and r.code = 'seller'
join permission p on p.id = rp.permission_id and p.resource = 'order' and p.action = 'read'
join permission_field_group g on g.resource = 'order' and g.code in ('basic', 'shipping')
where rp.effect = 'allow';

-- 감사자는 조회 범위가 전체지만 결제 수단까지 볼 이유는 없다.
-- 조회 권한을 준 것과 모든 필드를 준 것이 같지 않다는 사례다.
insert into role_permission_field (role_id, permission_id, effect, field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.id
from role_permission rp
join role r on r.id = rp.role_id and r.code = 'auditor'
join permission p on p.id = rp.permission_id and p.resource = 'order' and p.action = 'read'
join permission_field_group g on g.resource = 'order' and g.code in ('basic', 'shipping')
where rp.effect = 'allow';
