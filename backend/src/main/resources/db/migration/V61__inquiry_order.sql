-- 주문에 붙는 문의(청크 58-2).
--
-- 없어진 22 행이 「대상 자원(**주문**·상품)」을 적어 뒀는데 58 이 상품과 계정만 담았다 —
-- **「이 주문 언제 와요?」를 담을 값이 없다.**
--
-- dispute(불만·분쟁)로 대신하면 안 된다. 그 표는 전자상거래법 시행령 제6조 4호의
-- **소비자의 불만 또는 분쟁처리에 관한 기록**(3년)이라, 단순 문의까지 거기 쌓이면
-- **「분쟁이 몇 건이었나」에 답을 못 한다.**


alter table inquiry add column seller_order_id bigint
    references seller_order (seller_order_id) on delete restrict;

comment on column inquiry.seller_order_id is
    '주문 문의의 대상 묶음. 셀러가 자기 것만 보는 근거도 이 값이다(청크 58-2)';

alter table inquiry drop constraint inquiry_kind_check;

alter table inquiry add constraint inquiry_kind_check
    check (kind in ('product', 'order', 'processing_stop', 'access_objection', 'dispute'));


-- 종류가 대상을 정한다.
--
-- **상품 문의는 상품에, 주문 문의는 묶음에, 계정에 붙는 셋은 아무 데도 안 붙는다.**
-- V53 이 상품만 보던 것을 묶음까지 보게 넓힌다 — 한쪽만 걸면
-- 「주문 문의인데 상품이 붙어 있는」 행이 생기고 그 행은 상품 화면의 목록에 섞여 나간다.
alter table inquiry drop constraint inquiry_product_check;

alter table inquiry add constraint inquiry_target_check
    check ((kind = 'product') = (product_id is not null)
           and (kind = 'order') = (seller_order_id is not null));


-- **주문 문의는 공개가 성립하지 않는다.**
--
-- 남의 주문을 남이 보면 안 된다 — 무엇을 언제 샀는지가 그 질문에 그대로 들어 있다.
-- V54 가 상품 문의만 공개를 허용하게 해 뒀고, order 는 그 목록에 안 들어가므로
-- 조건을 안 바꿔도 막힌다. **그래도 이 자리에 적어 둔다** —
-- 다음에 종류가 늘 때 「왜 product 만 있나」를 이 주석이 답한다.
--
--   inquiry_visibility_check: kind = 'product' or is_public = false


-- 셀러가 자기 묶음의 문의를 찾는 자리.
create index inquiry_seller_order_idx on inquiry (seller_order_id, created_at desc)
 where seller_order_id is not null;
