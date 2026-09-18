-- V78 을 되돌린다. **올라온 사진 정보가 전부 사라진다** — 저장소의 객체는 그대로 남고
-- 그것을 가리키던 행만 없어지므로, 되돌린 뒤에는 **주인 없는 파일**이 남는다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

drop index if exists product_image_by_product;
drop table if exists product_image;
