-- V118 을 되돌린다. 거두기 권한의 부여(트리거가 단 감사자 거부 포함)와 권한을 지운다.

delete from role_permission
 where permission_id = (select permission_id from permission where resource = 'inquiry' and action = 'withdraw');
delete from permission where resource = 'inquiry' and action = 'withdraw';
