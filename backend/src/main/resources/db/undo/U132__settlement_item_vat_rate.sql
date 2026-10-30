-- V132 를 되돌린다. **과거 정산서의 공급가액·세액이 다시 지금 세율로 역산된다** — 세율이 그대로인 동안만 값이 같다. `Q320` 의 코드(`VatSplit` 요율 칸·정산 마감 insert)를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table settlement_item drop constraint settlement_item_vat_rate_range_check;
alter table settlement_item drop constraint settlement_item_vat_platform_check;
alter table settlement_item drop column supply_amount;
alter table settlement_item drop column vat_rate_bp;
drop function vat_supply_amount(bigint, int);
