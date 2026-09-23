-- 내려간 후기는 지워도 그 주문의 후기 자리를 차지한다(`Q194`, 사용자 선택 2026-09-23).
--
-- **`V84` 가 막은 길이 옆문으로 열려 있었다.** 그 파일은 「내려간 것은 자리를 그대로 차지한다 — 조건에 넣으면 새 후기로
-- 갈아치워 제재를 우회한다」고 적고 유일성을 `deleted_at is null` 로만 걸었다. 그런데 쓴 사람의 지우기가 `blocked_at` 을
-- 안 봐서, 내려간 후기를 지우면 자리가 비고 같은 주문에 새 후기를 올릴 수 있었다(마무리 45차 독립 리뷰).
--
-- **지우기는 그대로 둔다** — 자기 글을 지울 권리를 막지 않는다. 대신 내려간 후기는 지워진 뒤에도 자리를 차지한다.
-- 되살림은 관리자만 한다(`ReviewModerationService`). 지운 뒤에 내려간 후기를 푸는 길은 아직 없다(`Q203`).
--
-- **색인이 아니라 넣기 전 트리거로 막는다**(마무리 46차 독립 리뷰). 유일 색인의 조건에 `blocked_at` 을 넣었더니 둘이 깨졌다 —
--   ① 지나간 우회(내려간 뒤 지우고 새로 쓴 쌍)가 운영 DB 에 하나라도 있으면 색인을 못 세워 배포가 멈춘다.
--   ② 신고가 대기 중일 때 쓴 사람이 지우고 새로 쓴 뒤 관리자가 그 신고를 받아들이면, 옛 행이 색인 조건에 들어와
--      새 행과 겹쳐 500 이 나고 그 신고는 끝내 못 받아들인다.
-- 트리거는 **새 행을 넣을 때만** 본다 — 지난 행을 다시 재지 않고, 지운 행을 내리는 것도 막지 않는다.
-- 살아 있는 후기 하나는 원래 색인(`V84` 의 `review_live_per_order_item`, `deleted_at is null`)이 그대로 든다.
create function review_blocked_keeps_slot() returns trigger
language plpgsql as $$
begin
    if exists (select 1 from review
                where order_item_id = new.order_item_id
                  and blocked_at is not null) then
        -- 유일성 위반과 같은 코드로 낸다 — 앱이 「이미 쓴 후기」와 같은 답으로 옮긴다.
        raise exception '내려간 후기가 이 주문 줄의 후기 자리를 차지한다: order_item %', new.order_item_id
            using errcode = 'unique_violation';
    end if;
    return new;
end;
$$;

create trigger review_blocked_keeps_slot
    before insert on review
    for each row execute function review_blocked_keeps_slot();
