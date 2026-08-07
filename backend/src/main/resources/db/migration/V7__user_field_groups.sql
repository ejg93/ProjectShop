-- V6 이 user 자원의 필드 그룹을 정의해 놓고 어느 규칙에도 연결하지 않았다.
-- 그래서 user:read 는 전부 제한이 없다. 그룹은 있는데 아무도 제한받지 않는 상태다.
-- V6 주석이 경고한 상황이 그 마이그레이션 안에서 이미 벌어졌다.
--
-- 지금은 user:read 를 쓰는 API 가 없어서 무해하지만,
-- 셀러가 고객 계정을 조회하는 경로가 생기면(문의·CS) 그때는 연락처가 그대로 나간다.

-- 자기 계정은 전부 본다. 그룹을 명시해서 "제한이 없어서 다 보이는 것" 과 구분한다.
insert into role_permission_field (role_id, permission_id, effect, permission_field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.permission_field_group_id
from role_permission rp
join role r on r.role_id = rp.role_id and r.code in ('customer', 'seller')
join permission p on p.permission_id = rp.permission_id and p.resource = 'user' and p.action = 'read'
join permission_field_group g on g.resource = 'user'
where rp.effect = 'allow';

-- 감사자는 계정을 전체 범위로 조회하지만 연락처를 볼 이유는 없다.
-- order:read 에서 payment 를 뺀 것과 같은 판단이다.
insert into role_permission_field (role_id, permission_id, effect, permission_field_group_id)
select rp.role_id, rp.permission_id, rp.effect, g.permission_field_group_id
from role_permission rp
join role r on r.role_id = rp.role_id and r.code = 'auditor'
join permission p on p.permission_id = rp.permission_id and p.resource = 'user' and p.action = 'read'
join permission_field_group g on g.resource = 'user' and g.code = 'basic'
where rp.effect = 'allow';

-- 관리자는 연결하지 않는다. order:read 와 같이 제한 없음으로 둔다.
-- 관리자가 개인정보를 어디까지 봐야 하는지는 청크 16b(임퍼소네이션)에서 감사 로그와 함께 정한다.
-- 여기서 제한을 걸면 관리자 화면이 무엇을 못 보는지 모르는 채로 만들어진다.
