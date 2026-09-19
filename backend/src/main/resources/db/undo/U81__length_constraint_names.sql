-- V81 을 되돌린다. **데이터는 안 사라진다** — 이름과 빈 문자열 허용 여부만 바뀐다.
--
-- 되돌리면 여섯이 다시 `LengthConstraintTest` 의 쓸기(`conname like '%length_check'`)를
-- 비껴간다. **그때는 회계 쪽도 같이 되돌려야 한다** — 표에 이름이 남아 있는데 제약이
-- 안 걷히면 「없어진 제약이 목록에 남는 것도 실패다」에 걸린다(`Q28`).

alter table product_image
    drop constraint product_image_object_key_length_check,
    drop constraint product_image_thumbnail_key_length_check,
    drop constraint product_image_original_name_length_check,
    add constraint product_image_object_key_length
        check (length(object_key) <= 200),
    add constraint product_image_thumbnail_key_length
        check (length(thumbnail_key) <= 200),
    add constraint product_image_original_name_length
        check (length(original_name) <= 255);

alter table copyright_report
    drop constraint copyright_report_reporter_name_length_check,
    drop constraint copyright_report_reporter_email_length_check,
    drop constraint copyright_report_claimed_work_length_check,
    add constraint copyright_report_reporter_name_length
        check (length(reporter_name) <= 100),
    add constraint copyright_report_reporter_email_length
        check (length(reporter_email) <= 320),
    add constraint copyright_report_claimed_work_length
        check (length(claimed_work) <= 2000);
