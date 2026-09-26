-- V119 를 되돌린다. 배치 실행 권한의 부여(트리거가 단 감사자 거부 포함)와 권한을 지운다.

delete from role_permission
 where permission_id = (select permission_id from permission where resource = 'batch' and action = 'run');
delete from permission where resource = 'batch' and action = 'run';
