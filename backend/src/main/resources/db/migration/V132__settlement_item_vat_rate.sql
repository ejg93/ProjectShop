-- 정산서 수수료 줄에 부가세율과 공급가액을 박제한다(`Q320`).
--
-- **왜**: 공급가액·세액을 읽을 때마다 지금 세율 상수로 역산했다(`VatSplit`). 세율이 바뀐 날 **과거 정산서의
-- 공급가액·세액이 같이 바뀐다** — 이미 낸 세금계산서와 정산서가 다른 값을 말하게 된다.
-- 부가가치세법은 세율 적용 시점을 조문으로 안 두고, 개정 부칙이 매번 「시행 이후 재화나 용역을 **공급하는 분부터**
-- 적용한다」고 적는다 — 공급시기의 세율이다. 수수료는 우리가 셀러에게 공급하는 용역이고 그 공급시기는
-- 역무 제공이 끝나는 때(제16조제1항제1호)라 **정산 마감**이 박제 시점이다.
--
-- **왜 `order_item` 이 아닌가**: 상품(재화)의 공급시기는 결제·인도라 시점이 다르고, 그쪽 세액을 쓰는 코드가 0 이다.
-- 세금계산서·현금영수증 연동이 생기는 날 그쪽을 따로 박제한다.
--
-- **요율과 금액을 둘 다 둔다** — `business-model.md` 「언제 박제하나」와 같은 꼴. 요율만이면 반올림 규칙이 바뀔 때
-- 과거 금액이 달라지고, 금액만이면 왜 그 금액인지 못 따라간다. 세액은 `amount - supply_amount` 가 정의다.

-- 부가세 포함 금액의 공급가액. **`VatSplit.supplyOf` 와 같은 식이다** — 부호를 떼고 만분율로 반올림해 다시 붙인다.
-- 둘이 같은지는 `SettlementSchemaTest` 가 잰다. 넣는 순간의 대조는 아래 `settlement_item_vat_supply_check` 가 한다.
create function vat_supply_amount(p_amount bigint, p_rate_bp int) returns bigint
language sql immutable as $$
    select sign(p_amount)::bigint
           * ((abs(p_amount) * 10000 + (10000 + p_rate_bp) / 2) / (10000 + p_rate_bp))
$$;

alter table settlement_item add column vat_rate_bp int;
alter table settlement_item add column supply_amount bigint;

comment on column settlement_item.vat_rate_bp is
    '부가세율(만분율). 정산 마감 때 박제한다 — 용역의 공급시기(부가가치세법 제16조제1항제1호)의 세율(제30조). 플랫폼이 공급한 줄에만 있다(D2 R17, Q320)';
comment on column settlement_item.supply_amount is
    '공급가액. 세금계산서 기재 사항(부가가치세법 제32조제1항제3호). 세액은 amount - supply_amount 다(Q320)';

-- 지금까지 마감된 줄은 전부 10% 시절이다(제30조).
update settlement_item
   set vat_rate_bp = 1000,
       supply_amount = vat_supply_amount(amount, 1000)
 where kind in ('commission', 'commission_reversal');

-- 플랫폼이 공급한 줄이면 반드시 있고, 아니면 없다. `supplier` 생성 열과 같은 목록이다(`V88`).
-- **칸마다 따로 잰다** — 둘을 `and` 로 묶으면 셀러 줄에 한 칸만 찬 행이 「둘 다 찼나 = 아니다」로 지나간다(PR #119 리뷰).
alter table settlement_item add constraint settlement_item_vat_platform_check
    check ((kind in ('commission', 'commission_reversal')) = (vat_rate_bp is not null)
           and (kind in ('commission', 'commission_reversal')) = (supply_amount is not null));

alter table settlement_item add constraint settlement_item_vat_rate_range_check
    check (vat_rate_bp between 0 and 10000);

-- 공급가액은 넣는 순간의 식으로 박제한 세율의 역산이다. **읽을 때 다시 재지 않는다** — 반올림 규칙이 바뀌어
-- 함수를 고쳐도 이미 선 행은 재검사되지 않아 박제가 선다(마무리 74차 독립 리뷰). 읽기 쪽은 식에 안 기대는
-- 불변식만 본다(`SettlementQuery.Line`).
alter table settlement_item add constraint settlement_item_vat_supply_check
    check (supply_amount is null or supply_amount = vat_supply_amount(amount, vat_rate_bp));
