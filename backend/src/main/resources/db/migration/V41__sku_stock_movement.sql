-- 재고가 왜 움직였는지를 남긴다(청크 53).
--
-- `52` 가 재고를 `sku_stock` 으로 갈라 놓으면서 카운터를 진실로 뒀다. 그 값만으로는
-- **「어제 20이었는데 왜 17인가」에 답할 수가 없다** — 주문이 셋 나간 것인지, 사람이 고친 것인지,
-- 취소가 되돌린 뒤 다시 나간 것인지가 남지 않는다.
--
-- **이력은 설명이지 진실이 아니다.** 판정은 여전히 `sku_stock` 의 조건부 UPDATE 가 한다(`D11`) —
-- 집계로 팔 수 있나를 정하면 음수를 막던 방어가 경쟁에 약해진다.
--
-- **대신 이력 없는 변동을 구조에서 없앤다**(사용자 선택). 재고를 옮기는 일을 함수 하나로 모으고,
-- `sku_stock.on_hand` 를 직접 고치는 경로는 트리거가 거부한다. 그래서 이 표의 합은
-- 언제나 `on_hand` 와 같고, 그것을 테스트가 대조한다.

create table sku_stock_movement (
    sku_stock_movement_id bigint not null generated always as identity primary key,

    sku_id bigint not null references sku (sku_id) on delete cascade,

    -- 부호 있는 변화량. 음수가 나간 것이다. **0 은 사건이 아니다.**
    quantity int not null,

    -- 왜 움직였나. 자유 텍스트로 두면 사유별 집계를 못 한다(`D23` 「열거값」).
    reason text not null,

    -- 어느 주문 때문인지. 주문은 5년 뒤 파기되므로 `set null` 이다 —
    -- 그때 사라지는 것은 「어느 주문」이고 「몇 개가 언제 나갔나」는 남는다.
    order_id bigint references shop_order (order_id) on delete set null,

    created_at timestamptz not null default now(),

    constraint sku_stock_movement_quantity_check check (quantity <> 0),

    constraint sku_stock_movement_reason_check
        check (reason in ('initial', 'order_placed', 'order_cancelled', 'adjustment'))
);

comment on table sku_stock_movement is '재고 이동 이력. 진실은 sku_stock 이고 이 표는 그 값이 왜 그런지를 설명한다(청크 53)';

create index sku_stock_movement_sku_id_idx on sku_stock_movement (sku_id, sku_stock_movement_id);

-- 재고를 옮기는 유일한 입구.
--
-- **조건부 UPDATE 의 뜻을 그대로 지킨다**(`D11`) — 나가는 쪽은 가용 재고가 모자라면 아무것도 안 하고
-- `false` 를 준다. 예외로 바꾸면 「재고 부족」이 오류 경로로 올라가서, 부르는 쪽이
-- 정상적인 실패와 진짜 고장을 같은 자리에서 받게 된다.
--
-- 함수가 이력까지 같이 넣는다. 앱이 두 문장으로 하면 한쪽만 도는 트랜잭션이 생길 수 있고,
-- 그 자리를 막으려고 또 제약을 만들게 된다.
create or replace function move_stock(p_sku_id bigint, p_quantity int,
                                      p_reason text, p_order_id bigint)
    returns boolean as $$
declare
    v_changed int;
begin
    if p_quantity = 0 then
        raise exception '0 은 재고 이동이 아니다' using errcode = 'check_violation';
    end if;

    -- 아래 트리거에게 「함수를 지나온 변경」이라고 알린다. 트랜잭션이 끝나면 사라진다.
    perform set_config('shop.stock_move', '1', true);

    if p_quantity < 0 then
        update sku_stock
           set on_hand = on_hand + p_quantity
         where sku_id = p_sku_id and available_count >= -p_quantity;
    else
        update sku_stock
           set on_hand = on_hand + p_quantity
         where sku_id = p_sku_id;
    end if;

    get diagnostics v_changed = row_count;
    perform set_config('shop.stock_move', '0', true);

    if v_changed = 0 then
        return false;
    end if;

    insert into sku_stock_movement (sku_id, quantity, reason, order_id)
    values (p_sku_id, p_quantity, p_reason, p_order_id);

    return true;
end;
$$ language plpgsql;

-- 함수를 안 지나온 재고 변경을 거부한다.
--
-- **관례로 두면 안 지켜진다.** 「재고는 `move_stock()` 으로만 옮긴다」를 문서에만 적으면
-- 새 경로를 만드는 사람이 그 문장을 안 읽고 UPDATE 를 쓴다 — 그러면 이력에 구멍이 나는데
-- 구멍은 숫자가 안 맞는 날에야 드러난다.
--
-- `of on_hand` 라 안전 재고를 고치는 UPDATE 는 안 걸린다. 그쪽은 파는 선을 정하는 일이지
-- 물건이 움직인 것이 아니다.
create or replace function assert_stock_move_allowed() returns trigger as $$
begin
    if coalesce(current_setting('shop.stock_move', true), '0') <> '1' then
        raise exception '재고는 move_stock() 으로만 옮긴다 (sku_id=%)', new.sku_id
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$ language plpgsql;

create trigger sku_stock_requires_move
    after update of on_hand on sku_stock
    for each row execute function assert_stock_move_allowed();

-- 처음 넣은 재고도 이동이다.
--
-- 안 남기면 이력의 합이 `on_hand` 와 처음부터 어긋나서, 대조가 「얼마부터 셌나」를 따로 알아야 한다.
-- 0 으로 만들어지는 행은 사건이 없는 것이라 안 남긴다.
create or replace function record_initial_stock() returns trigger as $$
begin
    if new.on_hand <> 0 then
        insert into sku_stock_movement (sku_id, quantity, reason)
        values (new.sku_id, new.on_hand, 'initial');
    end if;
    return null;
end;
$$ language plpgsql;

create trigger sku_stock_records_initial
    after insert on sku_stock
    for each row execute function record_initial_stock();

-- 이미 있는 재고에 시작점을 만들어 준다. 이 마이그레이션 전에 만들어진 행들이다.
insert into sku_stock_movement (sku_id, quantity, reason)
select sku_id, on_hand, 'initial' from sku_stock where on_hand <> 0;
