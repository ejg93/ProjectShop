-- V83 을 되돌린다. **후기가 전부 사라진다** — 다른 소비자가 쓴 글이라
-- 우리가 만든 데이터가 아니고, 되돌리면 복구할 방법이 없다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop trigger if exists review_check_target on review;
drop function if exists check_review_target();

drop trigger if exists review_set_updated_at on review;

drop index if exists review_user_id_idx;
drop index if exists review_product_id_idx;
drop table if exists review;
