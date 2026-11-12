-- V134 를 되돌린다. **권한 표의 자원이 다시 자유 글자가 된다** — `PermissionResource` 는 남아 판정 입구는 그대로 닫혀 있지만, DB 는 목록 밖 자원 시드를 안 막는다. `Q409b` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table permission drop constraint permission_resource_check;
