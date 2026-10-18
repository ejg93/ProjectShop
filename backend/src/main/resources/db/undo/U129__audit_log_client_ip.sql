-- V129 를 되돌린다. **감사 기록의 접속지가 사라진다** — 안전성 확보조치 기준 제8조의 항목 하나가 빈다. `Q273` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table audit_log drop column client_ip;
