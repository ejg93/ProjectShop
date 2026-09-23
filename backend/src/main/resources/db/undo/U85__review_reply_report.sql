-- V85 를 되돌린다. **답글과 신고 기록이 전부 사라진다.**
--
-- **운영정책 고지는 안 지운다**(`Q173`). 시행된 판은 `policy_document_immutable` 이 지우지 못하게 막고,
-- 그것이 맞다 — 공개한 고지를 거두는 것은 새 판을 쌓는 것이다(전자문서법 제4조의2). 전에는 여기서
-- 지우려 해서 **이 파일이 통째로 실패했고**, 그 탓에 `U83` 도 남은 답글 표에 걸렸다. 한 번도 안 돌려 봐서
-- 몰랐다. 후기를 같이 내리는 날에는 「후기를 운영하지 않는다」는 새 판을 따로 쌓는다(`D2` `R27`).
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).

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
