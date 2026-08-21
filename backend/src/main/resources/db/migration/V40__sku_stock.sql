-- 재고를 상품 정의에서 갈라낸다(청크 52).
--
-- `sku.stock_count` 하나가 재고였다. 그 칸이 상품 정의 표에 얹혀 있어서 둘이 걸렸다.
--   1. **재고가 바뀔 때마다 `sku.updated_at` 이 밀린다.** 상품을 고친 시각과 물건이 나간 시각이
--      한 칸에 섞여서, 「이 상품이 마지막으로 수정된 때」에 답을 못 한다.
--   2. **이동 이력(청크 53)이 붙을 자리가 없다.** 이력이 생기면 진실이 두 곳이 되는데,
--      어느 쪽이 진실인지를 정하지 않으면 다음 사람이 골라서 쓴다.
--
-- **진실은 여전히 카운터다.** 이동 이력이 붙어도 그것은 「왜 줄었나」를 설명하는 자리고,
-- 재고 판정은 이 표의 조건부 UPDATE 가 한다(`D11`) — 집계로 판정하면 음수를 막던 방어가
-- 경쟁에 약해진다. 이력이 서면 합계와 이 값을 대조하는 테스트가 둘을 묶는다(청크 53).
--
-- **안전 재고를 여기서 만든다.** 판매를 멈출 선이고, 가용 재고는 그 선을 뺀 값이다.
-- 앱이 빼면 화면마다 다른 값이 나오므로 **생성 컬럼**으로 둔다 — 저장되는 값이라
-- 조건부 UPDATE 의 조건으로 바로 쓸 수 있다.

create table sku_stock (
    sku_id bigint not null primary key
        references sku (sku_id) on delete cascade,

    -- 창고에 있는 수. 주문이 조건부 UPDATE 로 깎고 취소·만료가 되돌린다(`D11`).
    on_hand int not null default 0,

    -- 이 아래로는 안 판다. 오프라인 재고나 불량 여유분처럼 팔면 안 되는 몫이 여기 든다.
    safety_stock int not null default 0,

    -- 팔 수 있는 수. **앱이 빼지 않는다** — 화면·장바구니·주문이 각자 빼면 셋이 갈린다(`D23` 축 2).
    -- 안전 재고가 재고보다 크면 음수가 되는데, 그것이 「팔 것이 없다」의 정확한 표현이라 안 막는다.
    available_count int generated always as (on_hand - safety_stock) stored,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    -- 음수 재고를 DB 가 막아야 조건부 UPDATE 가 성립한다. 앱이 조건을 빠뜨려도 여기서 걸린다.
    constraint sku_stock_on_hand_check      check (on_hand >= 0),
    constraint sku_stock_safety_stock_check check (safety_stock >= 0)
);

create trigger sku_stock_set_updated_at
    before update on sku_stock
    for each row execute function set_updated_at();

comment on table sku_stock is '판매 단위의 재고. 상품 정의와 갈랐다(청크 52). 이동 이력은 53 이 붙인다';

-- **sku 하나에 재고 행 하나를 커밋 시점에 확인한다.** 재고 행이 없는 sku 는 조회에서
-- 조용히 빠져(inner join) 살 수 없는 상품이 되는데, 그 상태가 아무 오류로도 안 드러난다.
--
-- 만드는 쪽을 트리거로 대신하지 않고 **확인만 한다**. 대신 만들어 주면 수량을 채우는 UPDATE 가
-- 반드시 뒤따라야 하고, 그러면 재고를 넣는 자리가 두 문장으로 갈려서 한쪽을 빠뜨릴 수 있다.
-- 지연 제약이라 한 트랜잭션 안에서 sku 를 먼저 넣고 재고를 나중에 넣어도 된다.
create or replace function assert_sku_stock_exists() returns trigger as $$
begin
    if not exists (select 1 from sku_stock where sku_id = new.sku_id) then
        raise exception 'sku % 에 재고 행이 없다', new.sku_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

create constraint trigger sku_requires_stock
    after insert on sku
    deferrable initially deferred
    for each row execute function assert_sku_stock_exists();

-- 있던 값을 그대로 옮긴다. `sku` 마다 한 행이고, 없는 sku 가 생기면 조회가 조용히 빈다.
insert into sku_stock (sku_id, on_hand, created_at)
select sku_id, stock_count, created_at from sku;

alter table sku
    drop constraint sku_stock_count_check,
    drop column stock_count;
