-- V141 을 되돌린다. **저작권 통보 이력이 사라진다** — 권리주장자·복제·전송자에게 알린 증거다. `Q463` 의 코드를 같이 걷을 때만 쓴다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
delete from notification_body
 where notification_id in (select notification_id from notification where copyright_report_id is not null);
delete from notification where copyright_report_id is not null;
delete from notification_template
 where code in ('copyright_taken_down_reporter', 'copyright_taken_down_uploader',
                'copyright_reinstatement_reporter', 'copyright_reinstated_uploader');

drop index notification_copyright_report_idx;
drop index notification_copyright_uploader_unique;
drop index notification_copyright_reporter_unique;

alter table notification drop constraint notification_event_type_check;
alter table notification add constraint notification_event_type_check
    check (event_type in ('order_placed', 'payment_completed', 'supply_delayed',
                          'refund_completed', 'password_reset', 'email_change',
                          'advertisement', 'consent_result', 'consent_reconfirm'));

alter table notification drop constraint notification_recipient_check;
alter table notification drop constraint notification_target_check;
alter table notification add constraint notification_target_check
    check (num_nonnulls(order_id, seller_order_id, refund_id, user_consent_id) <= 1);

alter table notification alter column user_id set not null;
alter table notification drop column copyright_report_id;
