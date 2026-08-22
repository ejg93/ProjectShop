-- 환급 지연배상금(청크 12a-4, `D2` R5).
--
-- 전자상거래법 제18조제3항이 기한을 넘기면 **시행령 제21조의3 의 이율을 곱한 이자를 더한 금액**을
-- 환급하라고 한다. 이율은 **연 100분의 15** 다.
--
-- **금액이 아니라 이자를 따로 담는다.** `amount` 에 합쳐 넣으면 「대금이 얼마였나」를 못 되찾고,
-- 정산이 셀러에게 물릴 몫과 우리가 늦어서 무는 몫을 못 가른다 — 늦은 것은 우리 책임이다(`R5`).

alter table refund add column delay_interest bigint not null default 0;

comment on column refund.delay_interest is
    '환급이 기한을 넘겨서 붙은 지연배상금. 연 15%(시행령 제21조의3, D2 R5). 안 늦었으면 0';

-- 음수 이자가 없다. 안 늦은 것은 0 이지 마이너스가 아니다.
alter table refund add constraint refund_delay_interest_check
    check (delay_interest >= 0);

-- 기한 전에 처리한 건에는 이자가 붙을 수 없다.
--
-- **`decided_at` 이 있는 건만 본다.** 아직 안 정한 건은 기한을 넘겼어도 이자가 0 이고,
-- 정하는 순간 그때까지의 기간으로 계산된다.
alter table refund add constraint refund_delay_interest_timing_check
    check (delay_interest = 0 or (decided_at is not null and decided_at > due_at));
