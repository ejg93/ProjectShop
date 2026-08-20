-- 계약내용 서면이 빠진 주문이 서지 않게 한다(Q3, D2 R22).
--
-- 전자상거래법 제13조제2항 후단이 「계약내용에 관한 서면을 재화등을 공급할 때까지 교부」라고 한다.
-- V27 이 그 서면을 order_contract_document 로 박제하는데, 넣는 코드가 이렇게 생겼다.
--
--   insert into order_contract_document (...)
--   select :orderId, d.policy_document_id, v.clause
--     from (values ('withdrawal_guide', 'withdrawal'), ...) as v (code, clause)
--     join lateral (select ... from policy_document p
--                    where p.code = v.code and p.effective_at <= now() ...) d on true
--
-- 정책 문서가 없거나 아직 시행 전이면 join 이 아무것도 안 물어서 **조용히 0행**이다.
-- update() 의 반환값도 안 본다. 그러면 서면 없는 주문이 서고, 그 사실은 나중에
-- 주문 상세를 열어야 드러난다 — 이미 공급이 끝난 뒤다.
--
-- order_contract_document_clause_unique 는 같은 조항이 두 번 들어가는 것만 막는다.
-- **넷이 다 있는지는 아무도 안 봤다.**
--
-- V23 이 환불 합계에, V30 이 발송 기한에 쓴 지연 제약 트리거가 같은 물음의 선례다 —
-- 「행 하나가 옳은가」가 아니라 「행 집합이 온전한가」라 check 로는 못 막는다.


create or replace function assert_contract_documents_complete() returns trigger
language plpgsql as $$
declare
    v_missing text;
begin
    -- 없는 조항의 이름을 모은다. 개수만 세면 무엇이 빠졌는지를 사람이 다시 찾아야 한다.
    select string_agg(want.clause, ', ' order by want.clause) into v_missing
      from (values ('withdrawal'), ('exchange'), ('dispute'), ('terms')) as want (clause)
     where not exists (
               select 1
                 from order_contract_document d
                where d.order_id = new.order_id
                  and d.clause = want.clause);

    if v_missing is not null then
        raise exception '계약내용 서면이 빠진 주문이다: order_id=%, 없는 조항 %',
                        new.order_id, v_missing
              using errcode = 'check_violation';
    end if;

    return null;
end;
$$;

comment on function assert_contract_documents_complete() is
    '주문마다 계약내용 서면 네 조항이 다 박제됐는지 본다(D2 R22, 전자상거래법 제13조제2항)';

-- 지연이라야 한다. 주문 행을 먼저 넣고 서면을 그다음에 넣는데,
-- 즉시 검사하면 그 사이에 걸린다.
create constraint trigger shop_order_contract_documents_complete
    after insert on shop_order
    deferrable initially deferred
    for each row execute function assert_contract_documents_complete();


-- 조항 이름이 두 곳에 생겼다 — 위 목록과 order_contract_document_clause_check 다.
--
-- 하나를 늘리고 다른 하나를 안 늘리면 **새 조항이 빠져도 아무도 안 본다.**
-- 그것을 OrderContractTest 가 대조한다(`ProductStatusTest.withdrawalReasonMatchesConstraint`
-- 가 같은 이유로 서 있는 테스트다). 여기서는 못 막고 테스트가 천장이다(D23 축 2).
comment on constraint order_contract_document_clause_check on order_contract_document is
    '허용하는 조항 이름. 늘리면 assert_contract_documents_complete() 도 같이 늘린다';
