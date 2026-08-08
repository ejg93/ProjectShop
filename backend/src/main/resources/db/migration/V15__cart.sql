-- 장바구니. 사기 전에 담아 두는 자리다.
--
-- 비로그인도 담을 수 있다. 동의를 안 받고도 되는 이유는 개인정보법 제15조제1항 4호다 —
-- "계약을 체결하는 과정에서 정보주체의 요청에 따른 조치" 가 정확히 담아 달라는 요청이다.
-- 다만 처리방침에 자동수집장치(쿠키) 고지가 필요하고(13a) 보유기간을 정해야 한다(D13, 30일).

create table cart (
    cart_id bigint not null generated always as identity primary key,

    -- 로그인 장바구니는 계정이 주인이다.
    user_id bigint references app_user (user_id) on delete cascade,

    -- 비로그인 장바구니는 쿠키가 주인이다.
    --
    -- 세션 ID 를 안 쓴다. 로그인할 때 세션 고정 방어로 ID 가 바뀌어서(D14),
    -- 세션에 묶으면 로그인하는 순간 장바구니를 잃고 병합할 대상을 못 찾는다.
    cart_token text,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    -- 둘 다 비면 주인이 없는 장바구니가 되고, 둘 다 있으면 어느 쪽인지 모른다.
    constraint cart_owner_check check (num_nonnulls(user_id, cart_token) = 1)
);

-- 한 사람에 하나, 한 토큰에 하나. 부분 인덱스라 null 끼리는 안 부딪힌다.
create unique index cart_user_id_key on cart (user_id) where user_id is not null;
create unique index cart_token_key on cart (cart_token) where cart_token is not null;

-- 방치된 비로그인 장바구니를 파기 배치가 고른다(D13, 5i).
create index cart_stale_idx on cart (updated_at) where user_id is null;

create trigger cart_set_updated_at
    before update on cart
    for each row execute function set_updated_at();

create table cart_item (
    cart_item_id bigint not null generated always as identity primary key,

    cart_id bigint not null references cart (cart_id) on delete cascade,

    -- 애그리거트를 넘는 참조인데 cascade 다. domain-model.md 의 기본 규칙에서 벗어난다.
    --
    -- 그 규칙의 이유는 "이 주문이 어떤 SKU 였나를 끝까지 따라갈 수 있어야" 인데
    -- 장바구니는 거래 기록이 아니라 그냥 지우는 것이라(D13) 따라갈 이유가 없다.
    -- 상품이 사라지면 담아 둔 것도 같이 사라지는 편이 맞다.
    sku_id bigint not null references sku (sku_id) on delete cascade,

    -- 0개를 담는다는 뜻이 없다. 빼려면 행을 지운다.
    quantity int not null,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint cart_item_quantity_check check (quantity > 0),

    -- 같은 조합을 두 줄로 담지 않는다. 다시 담으면 수량이 바뀐다.
    constraint cart_item_sku_unique unique (cart_id, sku_id)
);

create trigger cart_item_set_updated_at
    before update on cart_item
    for each row execute function set_updated_at();
