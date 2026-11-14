-- V142 을 되돌린다. **숨긴 사진이 공개로 다시 나온다**(행이 남아 있으니) — 비공개 버킷의 객체는 그대로라 깨진 그림이 된다.
-- `Q464` 의 코드를 같이 걷고, 숨긴 행을 먼저 지운 뒤에만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
drop index review_image_hidden_idx;
drop index product_image_hidden_idx;
alter table review_image drop column hidden_at;
alter table product_image drop column hidden_at;
