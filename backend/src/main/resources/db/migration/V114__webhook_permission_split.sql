-- `webhook:manage` 를 가른다(`Q211`, 마무리 47차 독립 리뷰). `V110` 이 `V3` 관례(관리자는 전부 `all`)대로 관리자에게
-- `manage` 를 `all` 로 줘서, 로그인 화면에 공개된 관리자 연습 계정으로 아무 셀러에나 주소를 걸고 새 시크릿을 받아
-- 그 셀러의 사건을 바깥으로 흘릴 수 있었다. **시크릿을 만드는 권한은 자원 주인(대표)에게만 둔다** — 관리자에게는
-- 보는 손(`read`)과 폭주·악용 엔드포인트를 내리는 손(`delete`)만 남긴다. `V3` 관례는 근거가 아니라 참고다(`D23` 축 1 의 4순위).
--
-- 감사자: `delete` 는 쓰기라 `V75` 트리거가 거부를 단다. `read` 에는 트리거가 아무것도 안 단다 —
-- 감사자의 읽기는 `V103` 과 같이 여기서 명시로 준다(읽기에 거부를 다는 것은 틀리다).

insert into permission (resource, action, kind, description) values
    ('webhook', 'read',   'read',  '셀러 웹훅 엔드포인트와 발송 결과를 조회한다'),
    ('webhook', 'delete', 'write', '셀러 웹훅 엔드포인트를 지운다');

update permission
   set description = '셀러 웹훅 엔드포인트를 등록하고 발송을 다시 보낸다(시크릿을 만든다)'
 where resource = 'webhook' and action = 'manage';

delete from role_permission
 where role_id = (select role_id from role where code = 'admin')
   and permission_id = (select permission_id from permission where resource = 'webhook' and action = 'manage');

-- 대표: 입구가 권한 하나씩 보므로 셋 다 자기 셀러 범위로 갖는다
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'seller', 'allow'
  from role r
  join permission p on p.resource = 'webhook' and p.action in ('read', 'delete')
 where r.code = 'seller_owner';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'webhook' and p.action in ('read', 'delete')
 where r.code = 'admin';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'webhook' and p.action = 'read'
 where r.code = 'auditor';

-- 정한 모양 밖으로 열린 것과 빠진 것을 둘 다 본다. `V110` 의 검사는 action 을 안 봐서 관리자에게 `manage` 가 남아도
-- 지나갔을 것이다 — 이 블록은 관리자 `manage` 가 남으면 마이그레이션을 죽인다.
do $$
declare
    opened text;
    granted integer;
begin
    select string_agg(p.action || '→' || r.code || ':' || rp.scope, ', ' order by p.action, r.code) into opened
      from role_permission rp
      join role r on r.role_id = rp.role_id
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'webhook' and rp.effect = 'allow'
       and not ((p.action = 'manage' and r.code = 'seller_owner' and rp.scope = 'seller')
             or (p.action = 'read' and ((r.code = 'seller_owner' and rp.scope = 'seller')
                                        or (r.code in ('admin', 'auditor') and rp.scope = 'all')))
             or (p.action = 'delete' and ((r.code = 'seller_owner' and rp.scope = 'seller')
                                          or (r.code = 'admin' and rp.scope = 'all'))));
    if opened is not null then
        raise exception '웹훅 권한이 정한 범위 밖으로 열렸다: %', opened;
    end if;

    select count(*) into granted
      from role_permission rp
      join permission p on p.permission_id = rp.permission_id
     where p.resource = 'webhook' and rp.effect = 'allow';
    if granted <> 6 then
        raise exception '웹훅 허용 부여가 여섯이어야 한다(대표 셋·관리자 둘·감사자 하나): %', granted;
    end if;
end $$;
