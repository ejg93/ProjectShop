-- deny 가 실제로 필요한 역할과 규칙을 넣는다.
-- 효과 컬럼 자체는 role_permission 을 만드는 V2 에 있다 — 테이블 모양은 한 파일에 둔다.

-- 읽기전용 감사자.
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
select r.role_id, p.permission_id, 'all', 'allow'
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
select r.role_id, p.permission_id, 'all', 'deny'
from permission p
join role r on r.code = 'auditor'
where p.action not in ('read');

-- 자기가 만든 주문의 상태는 자기가 못 바꾼다.
-- 판매자 역할은 order:update_status 를 seller 스코프로 갖는데, 자기 계정으로 자기 상품을 산 경우
-- 구매자이면서 판매자가 되어 혼자 주문을 진행시킬 수 있다.
-- own 범위만 거부하면 남의 주문에 대한 권한은 그대로 남는다. 스코프가 넓은 쪽이 이기는 규칙과
-- 효과가 좁은 쪽이 이기는 규칙이 한 행에서 만나는 자리다.
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'own', 'deny'
from permission p
join role r on r.code = 'seller_owner'
where p.resource = 'order' and p.action = 'update_status';
