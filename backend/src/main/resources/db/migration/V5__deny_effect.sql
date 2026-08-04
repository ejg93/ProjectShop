-- 허용만 쌓이는 구조에서는 우선순위 문제가 안 생긴다.
-- 역할을 여럿 가진 사용자에게 "이 역할이 허용해도 저 역할이 막는다" 를 표현하려고 효과를 둔다.

alter table role_permission add column effect text not null default 'allow';
alter table role_permission add constraint role_permission_effect_check
    check (effect in ('allow', 'deny'));

-- 기본키에 효과를 넣는다. 넣지 않으면 같은 역할·권한에 allow 와 deny 를 같이 못 달아서
-- "전체는 허용하되 일부는 막는다" 가 표현되지 않는다.
-- 효과당 스코프는 하나로 유지한다. 같은 효과가 두 스코프로 잡히면 어느 쪽이 이기는지 규칙이 하나 더 필요해진다.
alter table role_permission drop constraint role_permission_pkey;
alter table role_permission add primary key (role_id, permission_id, effect);

comment on column role_permission.effect is
    'allow=허용, deny=거부. 판정에서 deny 가 allow 를 이긴다';

comment on column role_permission.scope is
    'allow 면 허용 범위, deny 면 거부 범위. own=자기 것, seller=자기 상품이 걸린 것, all=전체';

-- 읽기전용 감사자. deny 가 실제로 필요한 역할이다.
--
-- 권한을 안 주는 것과 deny 로 막는 것은 다르다.
-- 안 주면 다른 역할이 그 권한을 허용할 때 통과한다. 사용자는 역할을 여럿 가질 수 있어서
-- 감사자에게 관리자 역할이 같이 붙으면 관리자 쪽 허용이 그대로 먹는다.
-- deny 를 달면 어느 역할이 허용해도 막힌다.
--
-- 감사자에게 관리자를 같이 주는 상황이 이상해 보이지만, 실무에서는 반대로 온다.
-- 관리자에게 감사 기간 동안 쓰기를 막는 역할을 얹는 식이다. 역할을 회수하지 않고 덧씌워서 막는다.
insert into role (code, name, description, is_system, is_org_role) values
    ('auditor', '감사자', '모든 자원을 조회하고 어떤 자원도 변경하지 않는다', true, false);

insert into role_permission (role_id, permission_id, scope, effect)
select r.id, p.id, 'all', 'allow'
from (values
    ('product', 'read'),
    ('order',   'read'),
    ('payment', 'read'),
    ('user',    'read'),
    ('role',    'read')
) as v (resource, action)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'auditor';

-- 쓰기는 전부 막는다. 읽기 권한을 나열한 것과 달리 이쪽은 조회가 아닌 권한을 통째로 집는다.
-- 뒤 청크에서 권한이 늘어도 그것이 쓰기면 감사자는 자동으로 막혀야 한다.
-- 다만 이 insert 는 지금 있는 권한만 훑으므로, 새 권한을 넣는 마이그레이션이 감사자 deny 도 같이 넣어야 한다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.id, p.id, 'all', 'deny'
from permission p
join role r on r.code = 'auditor'
where p.action not in ('read');

-- 자기가 만든 주문의 상태는 자기가 못 바꾼다.
-- 판매자 역할은 order:update_status 를 seller 스코프로 갖는데, 자기 계정으로 자기 상품을 산 경우
-- 구매자이면서 판매자가 되어 혼자 주문을 진행시킬 수 있다.
-- own 범위만 거부하면 남의 주문에 대한 권한은 그대로 남는다. 스코프가 넓은 쪽이 이기는 규칙과
-- 효과가 좁은 쪽이 이기는 규칙이 한 행에서 만나는 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.id, p.id, 'own', 'deny'
from permission p
join role r on r.code = 'seller'
where p.resource = 'order' and p.action = 'update_status';
