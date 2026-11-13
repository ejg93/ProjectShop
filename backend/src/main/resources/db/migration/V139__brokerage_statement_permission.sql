-- 중개 명세 권한(`Q456`, 부가가치세법 제75조 `D2` R46). 새 자원 `brokerage_statement` — 표대로 가른다(`V91` 머리말).
--
-- **목록 셋을 같이 넓힌다.** 권한 자원 목록은 `check` 셋이 묶는다 — `permission_resource_check`(`V134`) ·
-- `permission_field_group_resource_check`(`V135`) · `audit_log_target_type_check`(`V133`, 거부 기록이 자원 이름을 대상 종류로 쓴다).
-- 하나만 넓히면 권한 행은 들어가는데 거부 기록이 `check` 에 걸려 판정이 500 이 된다. 셋이 `PermissionResource`·`AuditTargetType`
-- 과 같은지는 `EnumConstraintTest`·`AuditTargetTypeTest` 가 잰다.
--
-- `audit_log_target_type_check` 는 `not valid` 그대로 다시 건다(`V133` — 옛 행을 다시 안 검사한다). 나머지 둘은 검증한다.
alter table permission drop constraint permission_resource_check,
    add constraint permission_resource_check check (resource in (
        'audit', 'batch', 'brokerage_statement', 'compensation', 'coupon', 'coupon_issue', 'inquiry', 'metric', 'order',
        'payment', 'product', 'review', 'role', 'sales_stats', 'seller_member', 'settlement', 'user', 'webhook'
    ));

alter table permission_field_group drop constraint permission_field_group_resource_check,
    add constraint permission_field_group_resource_check check (resource in (
        'audit', 'batch', 'brokerage_statement', 'compensation', 'coupon', 'coupon_issue', 'inquiry', 'metric', 'order',
        'payment', 'product', 'review', 'role', 'sales_stats', 'seller_member', 'settlement', 'user', 'webhook'
    ));

alter table audit_log drop constraint audit_log_target_type_check,
    add constraint audit_log_target_type_check check (target_type in (
        'user', 'product', 'review', 'seller', 'seller_order', 'seller_invitation', 'coupon', 'coupon_issue',
        'compensation', 'copyright_report', 'webhook_endpoint', 'webhook_delivery',
        'audit', 'batch', 'brokerage_statement', 'inquiry', 'metric', 'order', 'payment', 'role', 'sales_stats',
        'seller_member', 'settlement', 'webhook'
    )) not valid;

-- `kind` 가 `write` 면 감사자 거부가 트리거로 붙는다(`V75`).
insert into permission (resource, action, kind, description) values
    ('brokerage_statement', 'read',   'read',  '판매·결제 중개 명세 분기 목록을 보고 파일을 내려받는다'),
    ('brokerage_statement', 'submit', 'write', '중개 명세를 국세청에 냈다고 제출 기록을 남긴다');

-- 관리자는 전부(`V3` 의 관례). 셀러에게는 안 준다 — 명세는 몰이 국세청에 내는 것이고 남의 셀러 신원이 같이 실린다.
insert into role_permission (role_id, permission_id, scope)
select r.role_id, p.permission_id, 'all'
  from permission p
  join role r on r.code = 'admin'
 where p.resource = 'brokerage_statement';

-- 감사자는 조회만 손으로 넣는다. 제출의 거부는 위 insert 가 트리거를 깨워 이미 들어갔다(`Q59`).
insert into role_permission (role_id, permission_id, scope, effect)
select r.role_id, p.permission_id, 'all', 'allow'
  from permission p
  join role r on r.code = 'auditor'
 where p.resource = 'brokerage_statement' and p.action = 'read';
