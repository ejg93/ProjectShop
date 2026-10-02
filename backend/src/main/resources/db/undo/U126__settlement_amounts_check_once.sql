-- V126 을 되돌린다. 표시를 지우는 트리거 둘과 그 함수를 지우고, 금액 검사 함수를 V52 본문으로 되살린다.

drop trigger settlement_resets_amount_check on settlement;
drop trigger settlement_item_resets_amount_check on settlement_item;
drop function reset_settlement_verified();

create or replace function assert_settlement_amounts(p_settlement_id bigint)
    returns void language plpgsql as $$
declare
    v_payout bigint;
    v_item_sum bigint;
    v_items int;
begin
    select payout_amount into v_payout
      from settlement where settlement_id = p_settlement_id;

    -- 정산서가 이미 지워졌다. 검사할 것이 없다.
    if not found then
        return;
    end if;

    select coalesce(sum(amount), 0), count(*)
      into v_item_sum, v_items
      from settlement_item where settlement_id = p_settlement_id;

    -- 줄이 없는 정산서는 지급액이 0 이어도 안 된다. 대상이 없으면 정산서를 안 만든다 —
    -- 빈 정산서가 서면 「이 달에 거래가 없었다」와 「마감이 덜 돌았다」가 안 갈린다.
    if v_items = 0 then
        raise exception '줄이 없는 정산서다 (settlement_id=%)', p_settlement_id;
    end if;

    if v_payout <> v_item_sum then
        raise exception '지급액이 항목 합과 다르다 (settlement_id=%, 저장=%, 항목합=%)',
            p_settlement_id, v_payout, v_item_sum;
    end if;
end;
$$;
