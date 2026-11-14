-- V144 을 되돌린다. **후기 사진 반복 침해 셈이 작성자를 잃는다.** `Q466` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
drop index copyright_report_uploader_strike_idx;
drop index copyright_report_product_strike_idx;
alter table copyright_report drop constraint copyright_report_review_uploader_check;
alter table copyright_report drop column uploader_user_id;
