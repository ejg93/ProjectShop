-- V133 를 되돌린다. **감사 로그의 대상 종류가 다시 자유 글자가 된다** — `AuditTargetType` 은 남아 앱이 적는 값은 그대로 닫혀 있지만, DB 는 목록 밖 값을 안 막는다. `Q381` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table audit_log drop constraint audit_log_target_type_check;
