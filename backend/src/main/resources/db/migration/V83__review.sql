-- 상품 후기(`46`).
--
-- **산 것과 안 이어진 후기가 못 생긴다.** 대상이 상품이 아니라 **주문 항목**이고 `not null` 이다 —
-- 「이 상품을 샀다」가 아니라 「이 주문의 이 줄을 샀다」라야 자격을 셀 수 있다(`47`).
-- 상품만 가리키면 안 산 사람의 후기를 막을 자리가 앱밖에 없다(`D23` 축 2 — 3위).
--
-- **`restrict` 다.** 후기가 가리키는 주문 줄은 지워지지 않는다 — 거래 기록이 5년 남고(`R6`)
-- 그 기록을 가리키는 후기가 먼저 끊기면 「무엇에 대한 후기인가」를 못 답한다.
create table review (
    review_id bigint not null generated always as identity primary key,

    order_item_id bigint not null
        references order_item (order_item_id) on delete restrict,

    -- 조회는 상품으로 들어온다. 매번 order_item → sku → product 를 타면 목록 쿼리가
    -- 조인 셋을 끼고 돈다 — **박제가 아니라 조회 경로다.**
    -- 주문 줄과 어긋나는 것은 아래 트리거가 막는다.
    product_id bigint not null
        references product (product_id) on delete restrict,

    -- 쓴 사람. 주문자와 같아야 하고 그것도 아래 트리거가 막는다.
    -- `restrict` 인 것은 `shop_order.user_id` 와 같은 이유다 — 탈퇴는 `update` 라 안 걸리고,
    -- 계정 행은 남고 그 안의 개인정보만 비워진다(`5i`).
    user_id bigint not null
        references app_user (user_id) on delete restrict,

    -- 별 다섯. 표시할 때 반올림하지 않는다 — 저장값이 곧 사람이 고른 값이다.
    rating smallint not null,

    body text not null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    -- 수명. null 이면 존재한다. **업무 상태와 섞지 않는다**(`D4`·`D13`) —
    -- 신고로 내리는 것은 상태고 여기는 「없어졌다」다. 그 둘을 가르는 것은 `48` 이다.
    deleted_at timestamptz,

    constraint review_rating_check
        check (rating between 1 and 5),

    -- 상한은 화면이 접지 않고 읽는 크기다. **하한을 안 둔다** — 몇 글자부터 후기인가는
    -- 입구가 정할 일이고(`47`), 제약으로 내리면 그 결정을 스키마가 먼저 해 버린다.
    constraint review_body_length_check
        check (length(body) between 1 and 2000)
);

comment on column review.product_id is '조회 경로. 주문 줄이 가리키는 상품과 같아야 한다 — review_check_target 이 막는다';
comment on column review.deleted_at is '수명. null 이면 존재한다. 신고로 내린 상태(`48`)와 축이 다르다';

-- 목록은 상품별로 최신순이다. 내린 것은 안 센다.
create index review_product_id_idx on review (product_id, created_at desc)
    where deleted_at is null;

-- 「내가 쓴 후기」 경로.
create index review_user_id_idx on review (user_id) where deleted_at is null;

create trigger review_set_updated_at
    before update on review
    for each row execute function set_updated_at();

-- 박제한 둘이 주문 줄과 어긋나는 것을 막는다.
--
-- `check` 로 못 내린다 — 다른 표 넷을 봐야 해서 행 검사의 범위를 넘는다.
-- `V4` 의 `check_user_role_target` 이 같은 이유로 트리거인 자리고, 여기도 같다.
--
-- **앱에만 두면 조용히 틀린다.** 상품이 어긋나면 남의 상품 페이지에 이 후기가 뜨고,
-- 사람이 어긋나면 안 산 사람이 쓴 것이 된다 — 둘 다 화면에서 그럴듯해 보인다.
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

create trigger review_check_target
    before insert or update on review
    for each row execute function check_review_target();
