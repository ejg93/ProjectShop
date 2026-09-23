-- 셀러 일별 매출 집계(`40`).
--
-- **일 단위 사전 집계다**(사용자 선택 2026-09-23). 배치가 어제까지를 이 표에 채우고, 오늘 몫은 조회가 원장에서
-- 바로 센다(`41`). 통계 조회의 비용이 주문 수가 아니라 날 수에 비례한다 — 원장을 매번 합치는 뷰는 주문이 늘수록
-- 느려지는데 `D21` 에 수치가 없어 언제 느려지는지를 모른다.
--
-- **환불은 환불된 날에 넣는다**(정산과 같은 축, `money-invariants.md`). 결제한 날의 줄을 거슬러 고치면 지난
-- 통계가 조회할 때마다 바뀌고, 배치가 지난 날을 다시 세야 한다. 그래서 **한 날의 줄은 그날이 끝나면 안 바뀐다**
-- — 결제 시각과 환불 승인 시각이 둘 다 한 번 박히고 안 움직이는 값이다.
--
-- **원장과 갈리면 원장이 이긴다.** 이 표는 파생값이라 언제든 원장에서 다시 만든다(`DailySalesService.rebuild`).
-- 갈리는지는 `DailySalesServiceTest` 의 대조가 본다.
--
-- **기본키가 (셀러, 날짜)다** — 값 자체가 식별자인 표다(`naming-rules.md` 「기본키」, `holiday` 와 같은 꼴).
-- 대리키를 얹으면 「같은 셀러의 같은 날이 두 줄」을 유니크 제약으로 따로 적게 된다.
create table seller_daily_sales (
    seller_id bigint not null references seller (seller_id) on delete restrict,

    -- 한국 시각의 날짜다(`time-rules.md`). 자정 경계를 UTC 로 자르면 오전 9시 전 결제가 전날로 간다.
    sales_date date not null,

    -- 그날 결제가 승인된 셀러 묶음 수. 주문 하나가 셀러 둘에게 가면 각자 하나씩 센다.
    order_count int not null,
    sold_quantity int not null,

    -- 고객이 상품에 낸 돈 — 줄 금액에서 쿠폰 할인을 뺀 것이다. 배송비는 안 든다(셀러 매출이 아니라 운임이다).
    paid_amount bigint not null,

    -- 그날 승인된 환불. 금액은 `refund_item.amount` 의 합이라 할인 뒤 축이다 — `paid_amount` 와 같은 축이어야
    -- 둘을 빼서 순매출이 나온다. 배송비 환불과 지연배상금은 안 든다.
    refund_count int not null,
    refunded_amount bigint not null,

    -- 이 줄을 원장에서 센 시각. 다시 세면 바뀐다.
    aggregated_at timestamptz not null default now(),

    primary key (seller_id, sales_date),

    constraint seller_daily_sales_counts_check
        check (order_count >= 0 and sold_quantity >= 0 and refund_count >= 0),
    constraint seller_daily_sales_amounts_check
        check (paid_amount >= 0 and refunded_amount >= 0),

    -- 빈 날은 줄을 안 만든다. 전부 0 인 줄이 들어오면 「그날 센 결과가 0」인지 「센 적이 없는지」가 안 갈린다.
    constraint seller_daily_sales_not_empty_check
        check (order_count > 0 or refund_count > 0)
);

comment on table seller_daily_sales is
    '셀러 일별 매출 집계(40). 배치가 어제까지 채우고 오늘은 조회가 원장에서 센다. 환불은 환불된 날에 든다';
