-- V100 을 되돌린다. **후기 사진의 행이 사라지고 저장소의 객체는 남는다** — 표가 열쇠를 들고 있어서,
-- 되돌리기 전에 그 열쇠로 객체를 먼저 지우지 않으면 주인 없는 파일이 공개 버킷에 남는다(`media-rules.md`).
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop trigger review_image_limit on review_image;
drop function reject_review_image_overflow();
drop table review_image;
