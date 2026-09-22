-- 쿠폰 정의와 발급(`49`).
--
-- **법의 경계를 컬럼으로 박제한다.** 전자금융거래법 제2조제14호의 선불전자지급수단은
-- 「이전 가능한 금전적 가치가 전자적 방법으로 저장된 것」이다 — 그 성질을 가지면
-- **충전·잔액 관리·미사용 잔액 환급·소멸시효 고지** 의무가 한꺼번에 붙는다.
--
-- **우리 쿠폰은 그 밖에 둔다**(사용자 결정, 2026-09-22). 근거는 셋이고 전부 이 표의 모양이다.
--
--   1. **잔액이 없다.** 발급 한 건이 주문 한 건에 통째로 쓰이고 남는 값이 없다.
--   2. **충전이 없다.** 사는 경로가 없고 우리가 발급하는 것뿐이다.
--   3. **이전이 없다.** 발급이 사람에 붙어 있고 넘길 자리가 없다.
--
-- **그래서 `balance`·`charged_amount`·`transferred_to_user_id` 같은 칸을 안 만든다.**
-- 안 만드는 것이 곧 강제 지점이다 — 칸이 없으면 그 기능을 넣는 날 **마이그레이션이 필요하고,
-- 그때 이 주석을 읽게 된다.** 값으로 막으면 그 값을 바꾸는 것만으로 법의 반대편에 선다.
--
-- 판단이 바뀌는 조건도 적어 둔다: **위 셋 중 하나라도 깨지면 다시 검토한다**(`D2` 「안 걸리는 것도
-- 근거를 남긴다」).

-- ---------------------------------------------------------------------------
-- 1. 쿠폰 정의
-- ---------------------------------------------------------------------------

create table coupon (
    coupon_id bigint not null generated always as identity primary key,

    -- 사람이 부르는 안정된 키. 발급 코드가 아니다 — 그것은 발급 행이 든다.
    code text not null unique,

    name text not null,

    -- 정액이냐 정률이냐. **자유 문자열로 두면 계산하는 쪽이 모르는 값을 만난다**(`D23`).
    discount_kind text not null,

    -- 정액이면 원, 정률이면 bp 다(1000 = 10.00%). `commission_bp` 와 같은 단위다(`D8`).
    -- **한 칸에 담되 뜻은 종류가 정한다** — 칸을 둘로 두면 둘 다 찬 행과 둘 다 빈 행이 생긴다.
    discount_value bigint not null,

    -- 정률의 상한. 정액에는 없다.
    max_discount_amount bigint,

    -- 이 금액 이상일 때만 쓴다. 0 이면 조건이 없다.
    min_order_amount bigint not null default 0,

    -- **부담 주체.** 정산이 이 값으로 갈린다(`51`) — 자유 문자열이면 정산이 사유별로 못 가른다.
    bearer text not null,

    -- 셀러 부담이면 그 셀러. 몰 부담이면 없다. 아래 사슬 검사가 묶는다.
    seller_id bigint references seller (seller_id) on delete restrict,

    -- 발급이 열려 있는 구간. 발급 자체의 창이고, 쓰는 기한은 발급 행이 든다.
    issue_start_at timestamptz not null default now(),
    issue_end_at   timestamptz,

    -- 발급하면 며칠 쓸 수 있나. 발급할 때 이 값으로 기한을 박제한다 —
    -- 정의가 바뀌어도 **이미 나간 쿠폰의 기한은 안 바뀐다**(`V26` 과 같은 이유).
    valid_days int not null default 30,

    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,

    constraint coupon_code_length_check check (length(code) between 1 and 50),
    constraint coupon_name_length_check check (length(name) between 1 and 100),

    constraint coupon_discount_kind_check
        check (discount_kind in ('amount', 'percent')),

    constraint coupon_bearer_check
        check (bearer in ('mall', 'seller')),

    -- 셀러 부담에는 셀러가 있고 몰 부담에는 없다. **한쪽만 찬 행이 안 생긴다** —
    -- 셀러 없는 셀러 부담은 정산에서 갈 곳이 없고, 셀러가 붙은 몰 부담은 두 번 빠진다.
    constraint coupon_bearer_seller_check
        check ((bearer = 'seller') = (seller_id is not null)),

    -- 정액은 원 단위 양수, 정률은 1bp~10000bp(0.01%~100%) 다.
    constraint coupon_discount_value_check
        check ((discount_kind = 'amount' and discount_value > 0)
               or (discount_kind = 'percent' and discount_value between 1 and 10000)),

    -- 상한은 정률에만 있다. 정액에 상한을 두면 그 값이 곧 할인액이라 뜻이 없다.
    constraint coupon_max_discount_check
        check ((discount_kind = 'percent' and (max_discount_amount is null
                                               or max_discount_amount > 0))
               or (discount_kind = 'amount' and max_discount_amount is null)),

    constraint coupon_min_order_amount_check check (min_order_amount >= 0),
    constraint coupon_valid_days_check       check (valid_days between 1 and 3650),

    constraint coupon_issue_window_check
        check (issue_end_at is null or issue_end_at > issue_start_at)
);

comment on column coupon.discount_value is '정액이면 원, 정률이면 bp. 뜻은 discount_kind 가 정한다';
comment on table coupon is '할인 쿠폰. 잔액·충전·이전이 없어서 선불전자지급수단이 아니다(D2)';

create trigger coupon_set_updated_at
    before update on coupon
    for each row execute function set_updated_at();

create index coupon_issuable_idx on coupon (issue_start_at)
    where deleted_at is null;

-- ---------------------------------------------------------------------------
-- 2. 발급
-- ---------------------------------------------------------------------------

-- **발급이 사람에 붙는다.** 넘길 자리를 안 만드는 것이 위 1번 경계의 셋째다.
create table coupon_issue (
    coupon_issue_id bigint not null generated always as identity primary key,

    coupon_id bigint not null references coupon (coupon_id) on delete restrict,
    user_id   bigint not null references app_user (user_id) on delete restrict,

    issued_at  timestamptz not null default now(),

    -- 발급 시점에 박제한다. 정의의 `valid_days` 가 바뀌어도 이미 나간 것은 안 바뀐다.
    expires_at timestamptz not null,

    -- 쓴 시각. 차 있으면 끝난 쿠폰이다. **쓰는 것은 `50` 이 한다.**
    used_at timestamptz,

    -- 어느 주문에 썼나. 쓴 시각과 사슬로 묶인다.
    used_order_id bigint references shop_order (order_id) on delete restrict,

    constraint coupon_issue_expiry_after_issued_check
        check (expires_at > issued_at),

    -- 쓴 것에는 시각과 주문이 둘 다 있고, 안 쓴 것에는 둘 다 없다.
    -- **한쪽만 찬 행**은 「어디에 썼는지 모르는 사용」이라 정산이 못 따라간다.
    constraint coupon_issue_used_check
        check ((used_at is null) = (used_order_id is null)),

    -- 한 사람이 같은 쿠폰을 한 번만 받는다. **앱에서만 세면 두 번 눌러 두 장이 된다.**
    constraint coupon_issue_once unique (coupon_id, user_id)
);

-- 「내 쿠폰함」 경로. 안 쓰고 안 지난 것만 본다.
create index coupon_issue_usable_idx on coupon_issue (user_id, expires_at)
    where used_at is null;

create index coupon_issue_order_idx on coupon_issue (used_order_id)
    where used_order_id is not null;
