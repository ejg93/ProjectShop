-- V123 을 되돌린다. 검색 인덱스 둘을 지우고 확장을 내린다.

drop index product_description_trgm_idx;
drop index product_name_trgm_idx;
drop extension if exists pg_trgm;
