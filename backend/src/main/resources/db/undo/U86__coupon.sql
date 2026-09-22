-- V86 을 되돌린다. **발급된 쿠폰이 전부 사라진다** — 사람에게 나간 값이라
-- 되돌리면 그 사람이 받은 것이 없어진다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop index if exists coupon_issue_order_idx;
drop index if exists coupon_issue_usable_idx;
drop table if exists coupon_issue;

drop trigger if exists coupon_set_updated_at on coupon;
drop index if exists coupon_issuable_idx;
drop table if exists coupon;
