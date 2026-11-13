-- V139 을 되돌린다. **중개 명세 권한이 사라져 관리자 화면이 403 이다.** `Q456`·`Q457` 의 코드를 같이 걷을 때만 쓴다.
-- 권한 행을 먼저 지운다 — 남아 있으면 옛 목록 `check` 를 못 건다. `audit_log` 는 `not valid` 라 그 자원의 옛 기록이 있어도 걸린다.
--
-- 되돌리기 전에 `scripts/db-dump.sh` 를 돌린다(`64`).
delete from role_permission
 where permission_id in (select permission_id from permission where resource = 'brokerage_statement');
delete from permission where resource = 'brokerage_statement';

alter table permission drop constraint permission_resource_check,
    add constraint permission_resource_check check (resource in (
        'audit', 'batch', 'compensation', 'coupon', 'coupon_issue', 'inquiry', 'metric', 'order', 'payment',
        'product', 'review', 'role', 'sales_stats', 'seller_member', 'settlement', 'user', 'webhook'
    ));

alter table permission_field_group drop constraint permission_field_group_resource_check,
    add constraint permission_field_group_resource_check check (resource in (
        'audit', 'batch', 'compensation', 'coupon', 'coupon_issue', 'inquiry', 'metric', 'order', 'payment',
        'product', 'review', 'role', 'sales_stats', 'seller_member', 'settlement', 'user', 'webhook'
    ));

alter table audit_log drop constraint audit_log_target_type_check,
    add constraint audit_log_target_type_check check (target_type in (
        'user', 'product', 'review', 'seller', 'seller_order', 'seller_invitation', 'coupon', 'coupon_issue',
        'compensation', 'copyright_report', 'webhook_endpoint', 'webhook_delivery',
        'audit', 'batch', 'inquiry', 'metric', 'order', 'payment', 'role', 'sales_stats', 'seller_member',
        'settlement', 'webhook'
    )) not valid;
