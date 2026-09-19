-- V80 을 되돌린다. **잃는 것은 없다** — 제약만 걷는다.
--
-- 다만 되돌린 뒤에는 열한 장째를 막는 것이 앱 검증 하나뿐이고,
-- 그것을 빠뜨린 입구가 생기면 아무 데도 안 걸린다.

drop trigger if exists product_image_limit on product_image;
drop function if exists reject_product_image_overflow();
