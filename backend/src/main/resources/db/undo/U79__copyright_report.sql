-- V79 를 되돌린다. **신고 기록이 전부 사라진다** — 저작권법이 요구한 절차를 돌린 증거라
-- (`D2` `R42`), 되돌리는 것 자체가 법적으로 위험한 자리다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop index if exists copyright_report_pending;
drop table if exists copyright_report;

-- 권한도 같이 거둔다. 표가 없는데 동작만 남으면 아무 데도 안 걸린 권한이 하나 뜬다.
delete from role_permission
 where permission_id in (select permission_id from permission
                          where resource = 'product' and action = 'moderate');
delete from permission where resource = 'product' and action = 'moderate';
