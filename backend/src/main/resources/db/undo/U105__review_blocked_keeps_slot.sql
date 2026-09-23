-- V105 를 되돌린다. 내려간 후기를 지우면 다시 자리가 빈다 — 제재 우회가 돌아온다(`Q194`).

drop trigger review_blocked_keeps_slot on review;
drop function review_blocked_keeps_slot();
