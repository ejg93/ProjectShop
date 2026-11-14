-- V140 을 되돌린다. **관리자의 2차 인증 초기화가 403 이다** — 기기를 잃은 계정은 운영자 SQL 로만 푼다. `Q461` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
delete from role_permission
 where permission_id in (select permission_id from permission where resource = 'user' and action = 'reset_second_factor');
delete from permission where resource = 'user' and action = 'reset_second_factor';
