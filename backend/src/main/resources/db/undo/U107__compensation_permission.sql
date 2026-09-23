-- V107 을 되돌린다. 권한 행만 지운다 — 부여가 권한을 가리키므로 부여를 먼저 지운다.

delete from role_permission
 where permission_id in (select permission_id from permission where resource = 'compensation');
delete from permission where resource = 'compensation';
