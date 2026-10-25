-- V131 을 되돌린다. **사진 표시 이름에 제어문자·양방향 서식 문자가 다시 들어갈 수 있다** — 앱 검증(`ImagePipeline.Incoming`)만 남는다. `Q305` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
alter table review_image drop constraint review_image_original_name_chars_check;
alter table product_image drop constraint product_image_original_name_chars_check;
