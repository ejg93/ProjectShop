-- V130 을 되돌린다. **접속기록을 아무나 고치고 지울 수 있게 된다** — 안전성 확보조치 기준 제8조③ 의 보관 조치가 빈다. `Q280a` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
drop trigger audit_log_no_truncate on audit_log;
drop trigger audit_log_guard on audit_log;
drop function audit_log_guard();
