-- 상품 축. 파는 것이 무엇인가를 담는다.
--
-- 테이블을 다섯으로 가른다. 상품 하나에 재고 컬럼을 두면 "검정 M 은 있고 검정 L 은 없다" 를 못 적는다.
-- 파는 단위는 상품이 아니라 옵션 조합(sku)이라서, 가격과 재고는 sku 에 붙는다(domain-model.md).
--
--   product              팔 물건 하나
--   product_option       옵션 축      (색상, 사이즈)
--   product_option_value 축의 선택지  (검정, 흰색 / S, M, L)
--   sku                  조합 하나    (검정 M) — 가격과 재고가 여기 있다
--   sku_option_value     조합이 어떤 선택지로 이뤄졌나
--
-- 기본키는 <테이블>_id 다(naming-rules.md). 이 마이그레이션이 그 규칙을 처음부터 지키는 첫 자리다.

-- 수수료율은 만분율 정수다. 1000 = 10.00%.
-- 요율을 소수로 두면 금액을 곱한 결과가 소수가 되고, 어디서 자를지를 사람이 관리해야 한다.
-- 정수 만분율이면 amount * bp / 10000 의 정수 나눗셈이 곧 버림이라, D8 의 절사 규칙이 연산에 들어간다.
--
-- 셀러 기본 요율은 V4 에 있다. 상품은 그것을 덮어쓰는 예외만 든다(D3).

create table product (
    product_id  bigint not null generated always as identity primary key,

    -- 상품의 주인은 사람이 아니라 셀러다(ADR 0004). 담당자가 퇴사해도 상품은 셀러에 남는다.
    -- 애그리거트를 넘는 참조라 cascade 를 안 쓴다(domain-model.md).
    seller_id   bigint not null references seller (seller_id) on delete restrict,

    name        text   not null,
    description text,

    -- 업무 상태다. 삭제는 여기 없고 deleted_at 이 따로 답한다(D7).
    -- 검수 통과 전에는 노출되지 않으므로 draft 로 시작한다.
    status      text   not null default 'draft',

    -- 셀러 기본 요율을 덮어쓰는 값. 비어 있으면 셀러 요율을 쓴다(D3).
    -- not null 로 채워 두면 셀러 요율을 바꿔도 상품이 안 따라온다 — 예외는 예외로만 존재해야 한다.
    commission_bp int,

    -- 청약철회를 제한하는 상품인가(R4, 전자상거래법 제17조제2항).
    --
    -- 조문의 사유 중 1~3호는 받은 물건의 상태 판단이라 시스템이 못 정한다.
    -- 데이터로 표현되는 것은 셋이다 — 4호(복제 가능 재화), 5호(용역·디지털콘텐츠),
    -- 6호 위임(주문 제작).
    --
    -- 5호는 성격이 다르다. 4호·6호는 상품이기만 하면 성립하지만 5호는 "제공이 개시된" 사건이 있어야 한다.
    -- 여기 담는 것은 "이 상품이 디지털콘텐츠다" 까지고, 개시 여부는 주문 상태다(청크 11a).
    --
    -- 제한 상품은 주문 화면에서 미리 알려야 한다. 알리는 것이 선택이 아니라 제한의 성립 요건이다 —
    -- 필요한 조치를 안 하면 제한 자체가 적용되지 않는다(제17조제2항 단서).
    is_withdrawal_restricted boolean not null default false,
    withdrawal_restriction_reason text,

    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),

    -- 셀러가 상품을 내린다. 수명과 업무 상태를 가른다(domain-model.md).
    deleted_at  timestamptz,

    constraint product_status_check
        check (status in ('draft', 'pending_review', 'on_sale', 'sold_out', 'suspended')),

    constraint product_commission_bp_check
        check (commission_bp is null or commission_bp between 0 and 10000),

    -- 제한한다고만 하고 사유가 없으면 소비자에게 알릴 것이 없다.
    -- 반대로 제한하지 않는데 사유만 남으면 화면이 무엇을 보여줄지 갈린다. 둘 다 막는다.
    constraint product_withdrawal_reason_check
        check (
            (is_withdrawal_restricted and withdrawal_restriction_reason
                 in ('copyable_media', 'digital_content', 'made_to_order'))
            or (not is_withdrawal_restricted and withdrawal_restriction_reason is null)
        )
);

create trigger product_set_updated_at
    before update on product
    for each row execute function set_updated_at();

-- 목록은 언제나 셀러로 좁힌 뒤 산 것만 본다. 두 조건이 늘 같이 오므로 같이 건다.
create index product_seller_id_idx on product (seller_id) where deleted_at is null;

-- 옵션 축. "색상" 처럼 무엇으로 갈리는지를 적는다.
-- 옵션이 없는 상품은 이 표에 행이 없고, sku 가 하나만 있다.
create table product_option (
    product_option_id bigint not null generated always as identity primary key,

    -- 상품이 없으면 그 상품의 옵션은 무의미하다. 애그리거트 안쪽이라 cascade 다.
    product_id bigint not null references product (product_id) on delete cascade,

    name       text   not null,

    -- 화면에 뿌리는 순서. 입력 순서에 기대면 행을 고칠 때마다 순서가 흔들린다.
    sort_no    int    not null default 0,

    constraint product_option_name_unique unique (product_id, name)
);

-- 축의 선택지. "색상" 축의 "검정".
create table product_option_value (
    product_option_value_id bigint not null generated always as identity primary key,

    product_option_id bigint not null
        references product_option (product_option_id) on delete cascade,

    value   text not null,
    sort_no int  not null default 0,

    constraint product_option_value_unique unique (product_option_id, value)
);

-- 파는 단위. 가격과 재고가 여기 붙는다.
create table sku (
    sku_id     bigint not null generated always as identity primary key,

    product_id bigint not null references product (product_id) on delete cascade,

    -- 부가세를 포함한 판매가다(ADR 0007). 공급가액과 세액은 저장하지 않고 필요할 때 역산한다.
    -- 원 단위 정수라 bigint 다(D8).
    price       bigint not null,

    -- 재고 스키마(청크 52)가 입고·안전재고를 붙이기 전까지 이 컬럼 하나가 재고다.
    -- 주문이 조건부 UPDATE 로 깎는다(D11) — 음수를 DB 가 막아야 그 조건이 성립한다.
    stock_count int    not null default 0,

    -- 특정 색상만 단종하는 경우다. 상품 전체가 아니라 이 조합만 내린다.
    status      text   not null default 'on_sale',

    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    deleted_at  timestamptz,

    constraint sku_price_check check (price >= 0),
    constraint sku_stock_count_check check (stock_count >= 0),
    constraint sku_status_check check (status in ('on_sale', 'suspended'))
);

create trigger sku_set_updated_at
    before update on sku
    for each row execute function set_updated_at();

create index sku_product_id_idx on sku (product_id) where deleted_at is null;

-- 조합이 어떤 선택지로 이뤄졌나. "검정 M" 은 여기 두 행이다.
create table sku_option_value (
    sku_id bigint not null references sku (sku_id) on delete cascade,

    -- 선택지를 지우면 그 선택지로 만든 조합이 무엇이었는지 못 읽는다.
    -- 상품 수정에서 선택지를 빼려면 그 조합을 먼저 정리하게 만든다.
    product_option_value_id bigint not null
        references product_option_value (product_option_value_id) on delete restrict,

    primary key (sku_id, product_option_value_id)
);

create index sku_option_value_option_value_idx
    on sku_option_value (product_option_value_id);
