-- V114 를 되돌린다. 새 권한 둘의 부여(트리거가 단 감사자 거부 포함)와 권한을 지우고, 관리자의 `manage`/`all` 을 되살린다.

delete from role_permission
 where permission_id in (select permission_id from permission
                          where resource = 'webhook' and action in ('read', 'delete'));
delete from permission where resource = 'webhook' and action in ('read', 'delete');

update permission
   set description = '셀러 웹훅 엔드포인트를 등록·조회·삭제하고 발송을 다시 보낸다'
 where resource = 'webhook' and action = 'manage';

insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from role r
  join permission p on p.resource = 'webhook' and p.action = 'manage'
 where r.code = 'admin';
