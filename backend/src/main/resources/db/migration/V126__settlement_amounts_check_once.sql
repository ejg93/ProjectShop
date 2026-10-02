-- 정산 금액 검사를 정산마다 한 번만 센다(`Q245`).
--
-- **왜**: `V52` 의 지연 행 트리거 둘이 줄마다 `assert_settlement_amounts` 를 부르고, 그 함수가 매번 그 정산의
-- 줄을 다 센다. 마감이 정산 하나에 줄 n 개를 넣으면 커밋이 n × n 을 읽는다 — 로컬 10만 건에서 줄 7만에 196초
-- (`doc/notes/perf-local-100k.md`). 줄 4만을 넣은 커밋이 119초 → 0.13초(2026-10-03 설계 실험).
--
-- **트리거와 표는 그대로다**(2026-10-03 사용자 결정 (가)). 지급액 = 줄 합이라는 검사는 바뀌지 않고,
-- 같은 트랜잭션 안에서 **이미 센 정산을 다시 안 셀 뿐이다.**
--
-- **표시**: 센 정산 번호를 트랜잭션 로컬 설정 `shop.settlement_verified` 에 `,번호,` 꼴로 적는다
-- (`set_config(…, true)` — 트랜잭션이 끝나면 사라지고, savepoint 를 되돌리면 같이 되돌아간다).
--
-- **표시를 지우는 자리가 구멍을 막는다.** 센 뒤에 같은 트랜잭션이 줄이나 지급액을 또 바꾸면 다시 세야 한다.
-- 그래서 두 표를 바꾸는 문장이 **시작될 때**(`before … for each statement`) 표시를 통째로 지운다 —
-- 0줄 문장에도 돈다. 지연 행 트리거는 문장이 끝난 뒤(커밋이나 `set constraints … immediate`)에 돌므로
-- 표시가 지워진 다음이다. `SettlementSchemaTest` 「지급액은」이 이 경로를 잰다.
create or replace function assert_settlement_amounts(p_settlement_id bigint)
    returns void language plpgsql as $$
declare
    v_seen text := coalesce(current_setting('shop.settlement_verified', true), '');
    v_mark text := ',' || p_settlement_id || ',';
    v_payout bigint;
    v_item_sum bigint;
    v_items int;
begin
    -- 이 트랜잭션에서 이미 셌고 그 뒤로 두 표를 바꾼 문장이 없다.
    if position(v_mark in v_seen) > 0 then
        return;
    end if;

    select payout_amount into v_payout
      from settlement where settlement_id = p_settlement_id;

    -- 정산서가 이미 지워졌다. 검사할 것이 없다 — 표시도 안 남긴다.
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

    perform set_config('shop.settlement_verified', v_seen || v_mark, true);
end;
$$;

-- 두 표를 바꾸는 문장이 시작되면 센 표시를 다 지운다.
create function reset_settlement_verified() returns trigger
language plpgsql as $$
begin
    perform set_config('shop.settlement_verified', '', true);
    return null;
end;
$$;

-- 둘 다에 건다. 줄만 바꾸는 경로와 지급액만 고치는 경로가 각각 있다(`V52` 의 금액 트리거와 같은 이유).
create trigger settlement_item_resets_amount_check
    before insert or update or delete on settlement_item
    for each statement execute function reset_settlement_verified();

create trigger settlement_resets_amount_check
    before insert or update or delete on settlement
    for each statement execute function reset_settlement_verified();
