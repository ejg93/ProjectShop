-- V138 을 되돌린다. **중개 명세와 제출 기록이 다 사라진다** — 부가가치세법 제75조 제출 증빙을 잃는다. `Q455`~`Q457` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
drop table brokerage_statement_submission;
drop table brokerage_statement_line;
drop table brokerage_statement;
