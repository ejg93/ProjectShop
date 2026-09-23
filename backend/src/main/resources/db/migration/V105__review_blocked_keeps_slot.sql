-- 내려간 후기는 지워도 그 주문의 후기 자리를 차지한다(`Q194`, 사용자 선택 2026-09-23).
--
-- **`V84` 가 막은 길이 옆문으로 열려 있었다.** 그 파일은 「내려간 것은 자리를 그대로 차지한다 — 조건에 넣으면 새 후기로
-- 갈아치워 제재를 우회한다」고 적고 유일성을 `deleted_at is null` 로만 걸었다. 그런데 쓴 사람의 지우기가 `blocked_at` 을
-- 안 봐서, 내려간 후기를 지우면 자리가 비고 같은 주문에 새 후기를 올릴 수 있었다(마무리 45차 독립 리뷰).
--
-- **지우기는 그대로 둔다** — 자기 글을 지울 권리를 막지 않는다. 대신 내려간 후기는 지워진 뒤에도 자리를 차지한다.
-- 되살림은 관리자만 한다(`ReviewModerationService`).
drop index review_live_per_order_item;

create unique index review_live_per_order_item
    on review (order_item_id)
    where deleted_at is null or blocked_at is not null;
