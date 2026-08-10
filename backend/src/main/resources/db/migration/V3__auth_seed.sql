-- 역할과 권한의 초기 데이터.
-- 사용자 계정은 넣지 않는다. 비밀번호 해시가 청크 5에서 정해지고, 여기서 만든 해시는 그때 못 쓴다.

-- 역할 코드에 'seller' 를 안 쓴다. 그 이름은 조직(seller 테이블)이 갖는다 —
-- 둘이 같은 이름이면 "seller 를 지운다" 가 폐업인지 역할 회수인지 안 갈린다(D1).
-- 판정 범위(role_permission.scope)의 'seller' 는 조직 범위라는 뜻이라 그대로 둔다.
--
-- 실무자 역할 seller_staff 는 여기 없다. 권한을 어디까지 줄지가 청크 5a 에서 정해지고,
-- 권한 없는 역할을 미리 넣으면 판정 테스트가 빈 역할을 물고 돈다.
insert into role (code, name, description, is_system, is_org_role) values
    ('customer',     '고객',      '상품을 보고 주문한다',                       true, false),
    ('seller_owner', '셀러 대표', '셀러의 상품을 등록하고 그 셀러의 주문을 처리한다', true, true),
    ('admin',        '관리자',    '모든 자원을 보고 역할과 권한을 편집한다',        true, false);

insert into permission (resource, action, description) values
    ('product', 'create',        '상품을 등록한다'),
    ('product', 'read',          '상품을 조회한다'),
    ('product', 'update',        '상품의 정보와 가격을 수정한다'),
    ('product', 'delete',        '상품을 내린다'),
    ('product', 'review',        '상품을 승인하거나 반려한다'),
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

-- 판매자(대표). 상품은 자기 셀러 것 전부를 건드리고, 주문은 자기 상품이 들어간 것만 본다.
-- product:read 가 all 인 건 판매자도 남의 상품을 보는 구매자이기 때문이다.
--
-- 상품이 own 이 아니라 seller 인 이유는 상품의 주인이 사람이 아니라 셀러라서다(ADR 0004).
-- own 으로 두면 대상에 주인 계정이 없어서 판정이 아무것도 안 덮는다 — 상품을 하나도 못 만든다.
--
-- own 은 담당자 축에서 쓴다. product.created_by_user_id 를 가리키고,
-- "내가 등록한 상품만" 이 필요한 seller_staff 가 청크 5a 에서 그걸 받는다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, v.scope
from (values
    ('product', 'create',        'seller'),
    ('product', 'read',          'all'),
    ('product', 'update',        'seller'),
    ('product', 'delete',        'seller'),
    ('order',   'create',        'own'),
    ('order',   'read',          'seller'),
    ('order',   'update_status', 'seller'),
    ('payment', 'read',          'seller'),
    ('user',    'read',          'own'),
    ('user',    'update',        'own')
) as v (resource, action, scope)
join permission p on p.resource = v.resource and p.action = v.action
join role r on r.code = 'seller_owner';

-- 관리자. 권한을 나열하지 않고 permission 전체를 all 스코프로 준다.
-- 뒤 청크에서 권한이 늘어도 이 파일은 그대로 두고, 새로 추가된 권한만 그 청크의 마이그레이션이 붙인다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin';
