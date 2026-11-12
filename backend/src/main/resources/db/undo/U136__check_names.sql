-- V136 을 되돌린다. **`check` 이름 넷이 다시 꼴 밖이 된다** — `SchemaNamingTest` 가 빨개지고 리뷰 사진 길이 셋이 길이 회계를 비껴간다. `Q427` 의 시험·문서를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table review_image rename constraint review_image_object_key_length_check to review_image_object_key_length;
alter table review_image rename constraint review_image_thumbnail_key_length_check to review_image_thumbnail_key_length;
alter table review_image rename constraint review_image_original_name_length_check to review_image_original_name_length;
alter table copyright_report rename constraint copyright_report_decision_complete_check to copyright_report_decision_complete;
