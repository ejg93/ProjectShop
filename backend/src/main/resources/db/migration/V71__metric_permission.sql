-- 지표 조회 권한(`Q53`). `/actuator/prometheus` 를 관리자만 보게 한다.
--
-- **역할이 답을 바꾸므로 판정 엔진이다**(`permission-rules.md` 「판정을 안 지나는 입구」의 가르는 물음).
-- 소유 조건으로 막을 자원이 아니다 — 지표에 주인이 없다.
insert into permission (resource, action, description) values
    ('metric', 'read', '플랫폼 지표를 조회한다');

-- 관리자에게만 붙인다. V3 의 관리자 블록은 그 시점의 permission 전체를 훑은 것이라
-- 나중에 생긴 권한은 안 걸린다 — 그 파일 주석이 「새 권한은 그 청크의 마이그레이션이 붙인다」고 적어 뒀다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
from permission p
join role r on r.code = 'admin'
where p.resource = 'metric' and p.action = 'read';
