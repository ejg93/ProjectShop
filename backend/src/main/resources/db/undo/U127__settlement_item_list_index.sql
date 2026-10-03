-- V127 을 되돌린다. 정산 줄 인덱스를 옛 한 칸으로 되살린다.

drop index settlement_item_settlement_idx;
create index settlement_item_settlement_idx on settlement_item (settlement_id);
