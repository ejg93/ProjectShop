-- V135 를 되돌린다. **필드 그룹 표의 자원이 다시 자유 글자가 된다** — `permission` 표는 `V134` 로 닫혀 있지만, DB 는 목록 밖 자원의 필드 그룹 시드를 안 막는다. `Q428` 의 시험을 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table permission_field_group drop constraint permission_field_group_resource_check;
