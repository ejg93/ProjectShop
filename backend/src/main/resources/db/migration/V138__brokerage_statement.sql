-- 판매·결제 중개 명세(`Q455`). 부가가치세법 제75조제1항제1호·제2항 — 통신판매를 중개하는 자는 분기마다 다음 달 15일까지
-- 국세청에 명세를 낸다(`D2` R46). 시행령 제121조제1항이 월별 거래 명세를, 시행규칙 제78조가 별지 제48호서식(판매대행자료)을 정한다 —
-- 의뢰자(셀러)별·월별·결제구분별 건수와 금액(부가세 포함)이다.
--
-- **표가 셋이다.** 분기(머리) · 줄(셀러×승인 월×결제수단) · 제출 기록. 제출을 머리의 빈 칸으로 두지 않는다 — 행이 있으면 제출됨이다
-- (`coding-rules.md` 「빈 값에 뜻을 싣지 않는다」).
--
-- **수치만 박제한다**(2026-11-13 사용자 결정). 셀러 신원(상호·대표자·사업자번호)은 내려받을 때 `seller` 에서 붙인다 — 제출본의 sha256 을
-- 제출 기록에 남겨 무엇을 냈는지는 그 해시가 증명한다.
--
-- **줄의 결제수단은 우리 값이다**(`payment.method`). 서식 코드(C·R …)는 파일을 쓸 때 바꾼다 — 대문자 한 글자 목록을 DB 에 하나 더 두지 않는다.
create table brokerage_statement (
    brokerage_statement_id bigint generated always as identity primary key,
    period_year int not null,
    period_quarter int not null,
    period_start date not null,
    period_end date not null,
    due_date date not null,
    generated_at timestamptz not null default now(),

    constraint brokerage_statement_period_unique unique (period_year, period_quarter),
    constraint brokerage_statement_period_quarter_check check (period_quarter between 1 and 4),
    constraint brokerage_statement_period_check check (period_start < period_end and due_date > period_end)
);

create table brokerage_statement_line (
    brokerage_statement_id bigint not null references brokerage_statement (brokerage_statement_id) on delete cascade,
    seller_id bigint not null references seller (seller_id) on delete restrict,
    sales_month date not null,
    method text not null,
    sale_count int not null,
    sale_amount bigint not null,

    primary key (brokerage_statement_id, seller_id, sales_month, method),
    constraint brokerage_statement_line_method_check check (method in ('card', 'transfer')),
    constraint brokerage_statement_line_sale_count_check check (sale_count > 0),
    constraint brokerage_statement_line_sale_amount_check check (sale_amount >= 0),
    constraint brokerage_statement_line_sales_month_check check (sales_month = date_trunc('month', sales_month)::date)
);

create table brokerage_statement_submission (
    brokerage_statement_id bigint not null primary key
        references brokerage_statement (brokerage_statement_id) on delete restrict,
    submitted_at timestamptz not null default now(),
    submitted_by_user_id bigint not null references app_user (user_id) on delete restrict,
    file_sha256 text not null,

    constraint brokerage_statement_submission_file_sha256_check check (file_sha256 ~ '^[0-9a-f]{64}$')
);

create index brokerage_statement_line_seller_id_idx on brokerage_statement_line (seller_id);
create index brokerage_statement_submission_submitted_by_user_id_idx
    on brokerage_statement_submission (submitted_by_user_id);

comment on table brokerage_statement is
    '판매·결제 중개 명세 분기(Q455, R46 — 부가가치세법 제75조). 분기당 하나, 기한은 줄을 세울 때 박제한다';
comment on table brokerage_statement_line is
    '명세 줄 — 셀러×결제 승인 월×결제수단의 건수(묶음 수)와 셀러 공급 총액(줄 금액 + 배송비 − 셀러 부담 할인, 환불 미차감)';
comment on table brokerage_statement_submission is
    '명세 제출 기록. 행이 있으면 제출됨이다. file_sha256 은 그때 낸 파일의 해시';
