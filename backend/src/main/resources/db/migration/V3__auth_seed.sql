-- 역할과 권한의 초기 데이터.
-- 사용자 계정은 넣지 않는다. 비밀번호 해시가 청크 5에서 정해지고, 여기서 만든 해시는 그때 못 쓴다.

insert into role (code, name, description, is_system) values
    ('customer', '고객',   '상품을 보고 주문한다',                  true),
    ('seller',   '판매자', '자기 상품을 등록하고 그 상품의 주문을 처리한다', true),
    ('admin',    '관리자', '모든 자원을 보고 역할과 권한을 편집한다',     true);

insert into permission (resource, action, description) values
    ('product', 'create',        '상품을 등록한다'),
    ('product', 'read',          '상품을 조회한다'),
    ('product', 'update',        '상품의 정보와 가격을 수정한다'),
    ('product', 'delete',        '상품을 내린다'),
    ('order',   'create',        '장바구니의 내용으로 주문을 만든다'),
    ('order',   'read',          '주문의 내역과 상태를 조회한다'),
    ('order',   'update_status', '주문의 상태를 다음 단계로 옮긴다'),
    ('payment', 'read',          '결제의 승인·실패 내역을 조회한다'),
    ('payment', 'refund',        '승인된 결제를 취소하고 금액을 돌려준다'),
    ('user',    'read',          '사용자의 계정 정보를 조회한다'),
    ('user',    'update',        '사용자의 계정 정보를 수정한다'),
    ('role',    'read',          '역할과 역할에 달린 권한을 조회한다'),
    ('role',    'assign',        '사용자에게 역할을 주거나 회수한다'),
    ('role',    'manage',        '역할을 만들고 역할의 권한 구성을 바꾼다');

-- 고객. 상품은 남의 것도 다 보지만 주문과 계정은 자기 것만 본다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, v.scope
from (values
    ('product', 'read',   'all'),
    ('order',   'create', 'own'),
    ('order',   'read',   'own'),
    ('payment', 'read',   'own'),
    ('user',    'read',   'own'),
    ('user',    'update', 'own')
) as v (resource, action, scope)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'customer';

-- 판매자. 상품은 자기 것만 건드리고, 주문은 자기 상품이 들어간 것만 본다.
-- product:read 가 all 인 건 판매자도 남의 상품을 보는 구매자이기 때문이다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, v.scope
from (values
    ('product', 'create',        'own'),
    ('product', 'read',          'all'),
    ('product', 'update',        'own'),
    ('product', 'delete',        'own'),
    ('order',   'create',        'own'),
    ('order',   'read',          'seller'),
    ('order',   'update_status', 'seller'),
    ('payment', 'read',          'seller'),
    ('user',    'read',          'own'),
    ('user',    'update',        'own')
) as v (resource, action, scope)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'seller';

-- 관리자. 권한을 나열하지 않고 permission 전체를 all 스코프로 준다.
-- 뒤 청크에서 권한이 늘어도 이 파일은 그대로 두고, 새로 추가된 권한만 그 청크의 마이그레이션이 붙인다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin';
