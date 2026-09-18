-- 상품 하나에 사진 열 장까지(`Q102`, `media-rules.md` 「받는 것 — 제한값」).
--
-- **`check` 로는 못 한다.** 그것은 자기 행 안에서 끝나는 조건이고, 여기 세는 것은
-- **같은 상품의 다른 행**이다(`coding-rules.md` 「불변식 — 어디에 거나」의 트리거 줄,
-- 「주문 금액 = 항목 합」과 같은 자리).
--
-- **앱 검증을 안 걷는다.** 사용자에게 422 와 슬러그를 주는 것은 앱이고, 이 트리거는
-- **그 검증을 빠뜨린 입구**를 막는 그물이다 — 새 입구가 생기면 빠뜨리는 것이 3위의 성질이다.
--
-- **리뷰 둘이 같은 자리를 짚어서 잡혔다**(2026-09-18 마무리 26차 독립 리뷰 · PR #53 3회차).
-- 크기와 형식은 `check` 로 2위에 내렸는데 이 값만 3위에 남아 있었다.

create or replace function reject_product_image_overflow() returns trigger as $$
declare
    -- 값의 출처는 `media-rules.md` 다. 고칠 때 `ProductImageService.MAX_IMAGES_PER_PRODUCT` 와
    -- 그 문서를 같이 고친다 — 세 자리가 갈리면 앱이 받은 것을 DB 가 거절한다.
    max_images constant int := 10;
    current_count int;
begin
    -- **행 잠금을 안 건다.** 동시에 열한 장째 둘이 들어오면 둘 다 통과할 수 있다 —
    -- 그것을 막으려면 상품 행을 잠가야 하고, 그러면 사진 업로드가 상품 수정과 줄을 선다.
    -- **여기서 막는 것은 「빠뜨린 입구」지 경합이 아니다**(경합은 앱이 이미 한 트랜잭션에 있다).
    select count(*) into current_count
      from product_image
     where product_id = new.product_id;

    if current_count >= max_images then
        raise exception '상품 하나에 사진은 % 장까지다(Q102): product_id=%', max_images, new.product_id;
    end if;

    return new;
end;
$$ language plpgsql;

create trigger product_image_limit
    before insert on product_image
    for each row
    execute function reject_product_image_overflow();
