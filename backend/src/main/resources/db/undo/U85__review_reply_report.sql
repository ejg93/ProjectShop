-- V85 를 되돌린다. **답글과 신고 기록이 전부 사라지고 운영정책 고지도 내려간다.**
--
-- 고지가 내려가는 것이 제일 위험하다 — 후기는 계속 게시되는데 그 규칙이 안 보이면
-- 전자상거래법 제21조의4 를 어긴 상태가 된다(`D2` `R27`). 후기 자체를 같이 내릴 때만 되돌린다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

delete from policy_document where code = 'review_policy';

delete from role_permission
 where permission_id in (select permission_id from permission
                          where resource = 'review'
                            and action in ('reply', 'report', 'moderate'));
delete from permission
 where resource = 'review' and action in ('reply', 'report', 'moderate');

drop trigger if exists review_report_transition_check on review_report;
drop function if exists check_review_report_transition();
drop index if exists review_report_pending_idx;
drop table if exists review_report;

drop trigger if exists review_reply_check_seller on review_reply;
drop function if exists check_review_reply_seller();
drop trigger if exists review_reply_set_updated_at on review_reply;
drop table if exists review_reply;

-- 조회 인덱스를 V83 의 판으로 되돌린다.
drop index if exists review_product_id_idx;
create index review_product_id_idx on review (product_id, created_at desc)
    where deleted_at is null;

alter table review drop constraint if exists review_blocked_reason_check;
alter table review drop constraint if exists review_block_check;
alter table review drop column if exists blocked_reason;
alter table review drop column if exists blocked_at;
