-- 권한 표의 자원 종류를 값 목록으로 닫는다(`Q409b`).
--
-- **왜**: 권한 거부 기록이 이 글자를 `audit_log.target_type` 으로 쓴다(`PermissionResource.auditTarget()`). 목록 밖 자원이
-- 시드로 들어오면 열거형이 그 자원을 못 부르고, 그 자원의 규칙은 아무 판정에도 안 걸린다. 분기하는 값이라 `check` + 열거형이다
-- (`coding-rules.md` 「열거값을 어디에 두나」). 목록은 `PermissionResource` 17 과 같고 둘이 같은지는 `EnumConstraintTest` 가,
-- 시드와 열거형이 같은지는 `AuditTargetTypeTest` 가 잰다.
--
-- **왜 검증함(`not valid` 아님)**: 표가 시드만 들고 `V130` 같은 update 금지 트리거가 없다. 운영도 같은 마이그레이션을 지나서
-- 기존 행이 목록 안이다 — 목록 밖이면 배포가 여기서 죽는 것이 맞다.
alter table permission add constraint permission_resource_check check (resource in (
    'audit', 'batch', 'compensation', 'coupon', 'coupon_issue', 'inquiry', 'metric', 'order', 'payment',
    'product', 'review', 'role', 'sales_stats', 'seller_member', 'settlement', 'user', 'webhook'
));
