-- V101 을 되돌린다. **대행 권한이 사라지고 감사 기록의 「시킨 사람」 칸도 사라진다** — 대행 중에 남은
-- 기록은 그 사용자가 한 것처럼 읽힌다. `16b` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

delete from role_permission
 where permission_id in (select permission_id from permission
                          where resource = 'user' and action = 'impersonate');
delete from permission where resource = 'user' and action = 'impersonate';

alter table audit_log drop column impersonator_user_id;
