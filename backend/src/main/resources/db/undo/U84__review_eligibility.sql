-- V84 를 되돌린다. 후기는 남고 **자격만 풀린다** — 되돌린 채로 두면 안 산 사람의 후기가
-- 들어갈 수 있으니 되돌리는 것은 앞뒤 마이그레이션을 같이 되돌릴 때뿐이다.

drop index if exists review_live_per_order_item;

-- V83 의 판으로 되돌린다. 배송 상태 검사만 빠진다.
create or replace function check_review_target() returns trigger as $$
declare
    item_product_id bigint;
    buyer_user_id   bigint;
begin
    select s.product_id, o.user_id
      into item_product_id, buyer_user_id
      from order_item oi
      join sku s          on s.sku_id = oi.sku_id
      join seller_order so on so.seller_order_id = oi.seller_order_id
      join shop_order o    on o.order_id = so.order_id
     where oi.order_item_id = new.order_item_id;

    if item_product_id is distinct from new.product_id then
        raise exception '후기의 상품이 주문 줄과 다르다 (order_item_id=%, product_id=%)',
            new.order_item_id, new.product_id;
    end if;

    if buyer_user_id is distinct from new.user_id then
        raise exception '후기를 쓴 사람이 주문자가 아니다 (order_item_id=%, user_id=%)',
            new.order_item_id, new.user_id;
    end if;

    return new;
end;
$$ language plpgsql;

delete from role_permission
 where permission_id in (select permission_id from permission where resource = 'review');
delete from permission where resource = 'review';
