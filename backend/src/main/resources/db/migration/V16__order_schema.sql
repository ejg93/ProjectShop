-- 주문. 산 것과 낸 돈이 여기 남는다.
--
-- 주문은 두 층이다(D7). 결제는 주문 하나에 한 번이고 배송은 셀러마다 따로 굴러간다.
-- 한 층으로 두면 "셀러 A 는 배송 끝, B 는 준비 중" 을 표현할 수 없다.
--
--   shop_order                 결제가 붙는다
--    ├ order_shipping          배송지. 파기 대상이라 떼어 놨다
--    └ seller_order (셀러별)   배송·취소·반품이 붙는다
--        └ order_item          박제된 상품과 금액
--
-- 금액 등식과 등식마다의 강제 지점은 money-invariants.md 에 있다.
-- 여기는 그것을 제약으로 내린 자리다.


-- 주문. 결제 단위다.
--
-- deleted_at 이 없다. 거래기록 5년 보존(D2 R6)을 지키는 방법이 "지우지 말자" 는 약속이 아니라
-- 지울 컬럼이 없는 구조다(D23 「가장 낮은 층에 건다」). 파기는 10a 가 보존 기간을 보고 행을 지운다.
--
-- 이름이 shop_order 인 것은 order 가 SQL 예약어라서다. user 를 못 써서 app_user 가 된 것과 같다.
-- 기본키는 naming-rules 의 그 예외 규칙대로 order_id 다 — 접두사는 회피용이고 이 테이블이 담는 것은 주문이다.
create table shop_order (
    order_id bigint not null generated always as identity primary key,

    -- 바깥에 내보내는 번호. 내부 ID 를 노출하면 총량과 증가 속도가 샌다(D9).
    order_number text not null,

    -- 주문자. restrict 다 — 5년 남을 기록이 가리킬 곳을 잃으면 안 된다(D13).
    -- 탈퇴는 update 라 이 제약에 안 걸리고, 계정 행은 남고 그 안의 개인정보만 비워진다(5i).
    user_id bigint not null references app_user (user_id) on delete restrict,

    status text not null default 'payment_pending',

    -- 항목 금액의 합. 배송비는 안 들어간다.
    total_amount bigint not null default 0,

    -- 항목별로 잘라 둔 수수료의 합. 전체에서 한 번 자른 값과 1원 다를 수 있고 그게 맞는 값이다(D8).
    commission_total bigint not null default 0,

    -- 셀러별 배송비의 합.
    shipping_fee_total bigint not null default 0,

    -- 결제할 금액. 상품 금액과 배송비를 가르는 이유는 배송비에 수수료를 안 매기기 때문이다(D3).
    payable_amount bigint not null default 0,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    constraint shop_order_number_unique unique (order_number),

    -- 20260809-4F2K91. 뒤 6자리는 0·O·1·I 를 뺀 32자에서 뽑는다(D9) —
    -- 전화로 번호를 부르는 상황에서 잘못 듣는 것을 줄인다.
    constraint shop_order_number_format_check
        check (order_number ~ '^[0-9]{8}-[2-9A-HJ-NP-Z]{6}$'),

    constraint shop_order_status_check
        check (status in ('payment_pending', 'paid', 'payment_expired', 'payment_failed')),

    constraint shop_order_total_amount_check       check (total_amount >= 0),
    constraint shop_order_commission_total_check   check (commission_total >= 0),
    constraint shop_order_shipping_fee_total_check check (shipping_fee_total >= 0),

    -- 한 행 안에서 끝나는 등식이라 check 다. 나머지 두 합계는 다른 테이블을 봐야 해서 트리거로 간다.
    constraint shop_order_payable_amount_check
        check (payable_amount = total_amount + shipping_fee_total)
);

comment on column shop_order.order_number is '노출 번호. URL 과 화면은 이것을 쓴다(D9)';
comment on column shop_order.total_amount is '항목 금액 합. 배송비 제외';

create index shop_order_user_idx on shop_order (user_id, created_at desc);

-- 결제 만료 배치(11)가 훑는다. 만료되면 인덱스에서 빠져서 두 번 돌아도 훑을 것이 없다.
create index shop_order_payment_pending_idx on shop_order (created_at)
 where status = 'payment_pending';

create trigger shop_order_set_updated_at
    before update on shop_order
    for each row execute function set_updated_at();


-- 배송지. 주문에서 떼어 놨다.
--
-- 주문에 사람 정보를 박제하지 않는다(D13). 주문은 5년 보존인데 배송지는 파기 대상이라(R9)
-- 같은 행에 두면 5년 남길 것과 지울 것이 한 행에서 엉킨다.
-- 떼어 놓으면 파기가 행 삭제 하나로 끝나고, 남는 컬럼의 not null 을 안 풀어도 된다.
--
-- 동의를 따로 안 받는다. 계약 이행에 필요한 정보고 개인정보법 제15조제1항 4호가 근거다 —
-- 장바구니(9)와 같은 자리다. 대신 고지(13a)와 보유기간(D13)과 파기 코드(10a)가 따라온다.
--
-- 기본키가 order_shipping_id 가 아니라 order_id 인 것은 주문 하나에 배송지가 하나여서다.
-- 대리키를 두고 unique 를 거는 것보다 이쪽이 1:1 을 구조로 말한다.
create table order_shipping (
    order_id bigint not null primary key
        references shop_order (order_id) on delete cascade,

    receiver_name  text not null,
    receiver_phone text not null,
    postal_code    text not null,
    address1       text not null,
    address2       text,

    -- 문 앞에 놔 주세요 같은 것. 사람 정보가 섞여 들어올 수 있어서 파기 대상에 같이 든다.
    delivery_memo text,

    created_at timestamptz not null default now()
);

comment on table order_shipping is '배송지. 파기 대상이라 주문에서 분리했다(D2 R9, D13). 파기는 10a 가 행을 지운다';


-- 셀러별 주문. 배송이 여기서 굴러간다.
--
-- 셀러 권한 경계와 그대로 맞아떨어진다. scope=seller 가 이 테이블을 자르므로
-- 셀러가 남의 배송 정보를 볼 경로가 구조에서 사라진다.
create table seller_order (
    seller_order_id bigint not null generated always as identity primary key,

    -- 바깥에 내보내는 번호(D9). 이 단위를 부르는 곳이 넷이다 —
    -- 부분 취소·환불(12), 정산 명세의 한 줄(17~21), 반품 접수(43·44), 나중의 송장.
    -- 주문번호+셀러코드로 가리키면 그 넷에 컬럼 둘로 나가고, 셀러 코드는 사람이 정하는 값이라
    -- 이관·정정으로 바뀐다. 노출 번호는 한 번 내보내면 못 바꾸는 값이다(D9).
    --
    -- 앞에 S- 가 붙는다. 형식이 주문번호와 같으면 CS 가 눈으로 구분을 못 한다.
    seller_order_number text not null,

    order_id  bigint not null references shop_order (order_id) on delete restrict,
    seller_id bigint not null references seller (seller_id)    on delete restrict,

    -- 결제 전 구간을 어떤 상태로 볼지는 청크 11 이 정한다.
    -- D7 의 배송 상태 목록에 결제 대기에 해당하는 값이 없어서 기본값만 두고 넘긴다.
    status text not null default 'preparing',

    -- 배송은 셀러가 하므로 배송비도 셀러 몫이다(D3). 수수료를 안 매긴다.
    shipping_fee bigint not null default 0,

    -- 배송완료 시점에 박제한다(11). 청약철회 기산점이라 나중에 계산하면 달력이 바뀐 뒤다.
    delivered_at         timestamptz,
    withdrawal_expire_at timestamptz,
    auto_confirm_at      timestamptz,

    -- 거래가 끝난 시각. 구매확정·취소·반품완료가 여기를 채운다(11).
    --
    -- 보존 기간의 기산점이 "거래 종료일" 이라(D13) 이 값이 없으면 무엇을 언제 파기할지 못 정한다.
    -- updated_at 으로 대신하지 않는다 — 전이가 아닌 수정에도 밀려서 파기가 무한정 미뤄진다.
    closed_at timestamptz,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),

    -- 한 주문에 같은 셀러가 두 줄로 들어가면 배송비가 두 번 붙는다.
    constraint seller_order_unique unique (order_id, seller_id),

    constraint seller_order_number_unique unique (seller_order_number),

    -- S-20260811-K3M9P7. 난수 집합은 주문번호와 같다 — 0·O·1·I 를 뺀 32자(D9).
    constraint seller_order_number_format_check
        check (seller_order_number ~ '^S-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$'),

    constraint seller_order_status_check
        check (status in ('preparing', 'shipping', 'delivered', 'confirmed',
                          'cancelled', 'return_requested', 'returned')),

    constraint seller_order_shipping_fee_check check (shipping_fee >= 0),

    -- 배송완료 전에 청약철회 만료일이 있으면 기산점 없이 기한만 있는 것이 된다.
    constraint seller_order_delivered_dates_check
        check (delivered_at is not null
               or (withdrawal_expire_at is null and auto_confirm_at is null))
);

create index seller_order_seller_idx on seller_order (seller_id, created_at desc);
create index seller_order_order_idx  on seller_order (order_id);


-- 셀러에게 보이는 셀러 주문.
--
-- 결제가 끝난 것만 든다. 셀러가 할 일이 생기는 시점이 결제 완료고, 만료·실패는 거래가 안 선 것이라
-- 셀러의 목록에 있을 이유가 없다. 결제 대기 건이 보이면 셀러가 아직 안 팔린 것을 준비한다.
--
-- 조회 쿼리마다 조건을 적지 않고 뷰로 둔 이유는 이 조건이 늘기 때문이다 —
-- 청크 12 가 가상계좌 입금 대기와 부분 환불을 들고 온다. 쿼리에 흩어 두면 새 조회에서 빠뜨리고,
-- 빠뜨리면 미결제 건이 셀러에게 새면서 오류는 안 난다.
--
-- 청크 12 가 shop_order.paid_at 을 만들면 이 조건을 그쪽으로 옮긴다.
-- 상태 목록이 몇 개로 늘든 "결제가 성립했나" 는 한 컬럼으로 답한다.
--
-- 조회 전용이다. 전이는 실테이블을 update 한다 — 뷰를 거치면 무엇이 갱신 가능한지가 흐려진다.
create view seller_order_visible as
select so.seller_order_id,
       so.seller_order_number,
       so.order_id,
       so.seller_id,
       so.status,
       so.shipping_fee,
       so.delivered_at,
       so.withdrawal_expire_at,
       so.auto_confirm_at,
       so.closed_at,
       so.created_at,
       so.updated_at
  from seller_order so
  join shop_order o on o.order_id = so.order_id
 where o.status = 'paid';

comment on view seller_order_visible is
    '셀러에게 보이는 셀러 주문. 결제가 끝난 것만 든다(11c-2)';

create trigger seller_order_set_updated_at
    before update on seller_order
    for each row execute function set_updated_at();


-- 주문 항목. 무엇을 얼마에 샀나가 여기 박제된다.
--
-- sku_id 를 두고 가격을 조인해 오면 셀러가 가격을 바꿨을 때 과거 주문 금액이 같이 바뀐다.
-- 돈이 걸린 값은 전부 복사해 넣는다.
--
-- 항목에 상태를 안 둔다(D7). 취소·반품의 최소 단위는 seller_order 다.
create table order_item (
    order_item_id bigint not null generated always as identity primary key,

    seller_order_id bigint not null
        references seller_order (seller_order_id) on delete restrict,

    -- restrict 다. 어떤 SKU 였나를 끝까지 따라갈 수 있어야 한다(domain-model.md).
    -- 그래서 주문에 쓰인 SKU 는 지우지 못하고 판매중지로 내린다 — 그 처리는 10-2 가 붙인다.
    sku_id bigint not null references sku (sku_id) on delete restrict,

    -- 주문 시각의 값. 상품이 이름을 바꿔도 영수증은 안 바뀐다.
    product_name text not null,

    -- "검정 / M". 옵션 조합을 사람이 읽는 형태로 굳힌다 — 옵션 행이 나중에 바뀌어도 남는다.
    option_label text,

    unit_price bigint not null,
    quantity   int    not null,
    line_amount bigint not null,

    -- 주문 시점의 요율과 그것으로 계산한 수수료액을 둘 다 박제한다(D3).
    -- 요율만 두면 절사 규칙을 바꿀 때 과거 수수료가 달라진다.
    commission_bp     int    not null,
    commission_amount bigint not null,

    created_at timestamptz not null default now(),

    constraint order_item_quantity_check   check (quantity >= 1),
    constraint order_item_unit_price_check check (unit_price >= 0),
    constraint order_item_commission_bp_check check (commission_bp between 0 and 10000),

    -- 한 행 안에서 끝나는 등식 둘. 앱을 안 거치는 입구에서도 막힌다.
    constraint order_item_line_amount_check
        check (line_amount = unit_price * quantity),

    -- 정수 나눗셈이 곧 버림이다(D8). 절사 규칙이 등식 안에 들어가서 따로 표현할 것이 없다.
    -- 규칙을 반올림으로 바꾸면 이 제약을 갈아 끼워야 한다 — money-invariants.md 에 적어 뒀다.
    constraint order_item_commission_amount_check
        check (commission_amount = line_amount * commission_bp / 10000)
);

create index order_item_seller_order_idx on order_item (seller_order_id);

-- 상품을 고칠 때 "주문에 쓰인 SKU 인가" 를 본다(10-2).
create index order_item_sku_idx on order_item (sku_id);


-- 합계가 항목 합과 맞는지 본다.
--
-- 한 행 안에서 안 끝나는 등식이라 check 로 못 건다. 앱에 두지 않은 이유는 입구가 하나가 아니어서다 —
-- 배치와 psql 과 나중의 관리자 도구가 같은 테이블을 쓴다. 정산은 1원만 어긋나도 사고다.
create or replace function assert_order_amounts(p_order_id bigint) returns void
language plpgsql as $$
declare
    o record;
    v_item_sum bigint;
    v_commission_sum bigint;
    v_shipping_sum bigint;
    v_seller_orders int;
begin
    select total_amount, commission_total, shipping_fee_total
      into o
      from shop_order
     where order_id = p_order_id;

    -- 주문이 이미 지워졌다. 검사할 것이 없다.
    if not found then
        return;
    end if;

    select coalesce(sum(oi.line_amount), 0), coalesce(sum(oi.commission_amount), 0)
      into v_item_sum, v_commission_sum
      from order_item oi
      join seller_order so on so.seller_order_id = oi.seller_order_id
     where so.order_id = p_order_id;

    select coalesce(sum(shipping_fee), 0), count(*)
      into v_shipping_sum, v_seller_orders
      from seller_order
     where order_id = p_order_id;

    if v_seller_orders = 0 then
        raise exception '셀러 주문이 없는 주문이다 (order_id=%)', p_order_id;
    end if;

    if exists (select 1
                 from seller_order so
                where so.order_id = p_order_id
                  and not exists (select 1
                                    from order_item oi
                                   where oi.seller_order_id = so.seller_order_id)) then
        raise exception '항목이 없는 셀러 주문이 있다 (order_id=%)', p_order_id;
    end if;

    if o.total_amount <> v_item_sum then
        raise exception '주문 금액이 항목 합과 다르다 (order_id=%, 저장=%, 항목합=%)',
            p_order_id, o.total_amount, v_item_sum;
    end if;

    if o.commission_total <> v_commission_sum then
        raise exception '수수료 합이 항목별 수수료 합과 다르다 (order_id=%, 저장=%, 항목합=%)',
            p_order_id, o.commission_total, v_commission_sum;
    end if;

    if o.shipping_fee_total <> v_shipping_sum then
        raise exception '배송비 합이 셀러 주문의 배송비 합과 다르다 (order_id=%, 저장=%, 합=%)',
            p_order_id, o.shipping_fee_total, v_shipping_sum;
    end if;
end;
$$;

create or replace function check_amounts_on_shop_order() returns trigger
language plpgsql as $$
begin
    perform assert_order_amounts(new.order_id);
    return null;
end;
$$;

create or replace function check_amounts_on_seller_order() returns trigger
language plpgsql as $$
begin
    -- 셀러 주문이 다른 주문으로 옮겨가는 일은 없지만, 옮겨도 양쪽이 다 맞아야 한다.
    if tg_op <> 'INSERT' then
        perform assert_order_amounts(old.order_id);
    end if;
    if tg_op <> 'DELETE' then
        perform assert_order_amounts(new.order_id);
    end if;
    return null;
end;
$$;

create or replace function check_amounts_on_order_item() returns trigger
language plpgsql as $$
declare
    v_order_id bigint;
begin
    if tg_op <> 'INSERT' then
        select order_id into v_order_id
          from seller_order where seller_order_id = old.seller_order_id;
        if found then
            perform assert_order_amounts(v_order_id);
        end if;
    end if;

    if tg_op <> 'DELETE' then
        select order_id into v_order_id
          from seller_order where seller_order_id = new.seller_order_id;
        if found then
            perform assert_order_amounts(v_order_id);
        end if;
    end if;

    return null;
end;
$$;

-- 지연 트리거인 이유는 순서다.
--
-- shop_order 가 먼저 들어가고 order_item 이 뒤에 붙는데 참조 방향이 그 반대라 순서를 못 바꾼다.
-- 즉시 트리거면 shop_order 를 넣는 순간 항목 합이 0 이라 언제나 깨진다.
-- 커밋 시점까지 미루면 트랜잭션 중간에는 안 맞아도 되고 끝날 때 맞으면 된다.
--
-- 세 테이블에 다 건다. 항목만 보면 항목을 안 건드리고 합계만 고치는 경로가 빠져나간다.
create constraint trigger shop_order_amounts_check
    after insert or update on shop_order
    deferrable initially deferred
    for each row execute function check_amounts_on_shop_order();

create constraint trigger seller_order_amounts_check
    after insert or update or delete on seller_order
    deferrable initially deferred
    for each row execute function check_amounts_on_seller_order();

create constraint trigger order_item_amounts_check
    after insert or update or delete on order_item
    deferrable initially deferred
    for each row execute function check_amounts_on_order_item();
