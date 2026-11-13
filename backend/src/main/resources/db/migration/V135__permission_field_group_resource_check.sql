-- 필드 그룹 표의 자원 종류를 권한 자원 목록으로 닫는다(`Q428`).
--
-- **왜**: `permission_field_group.resource` 는 `permission.resource` 와 같은 자원 이름을 든다 — 판정 엔진이 자원별로 필드 그룹을
-- 읽는다(`PermissionRuleLoader`). `V134` 가 `permission` 만 닫아서 이 칸은 자유 글자로 남았고, 목록 밖 자원의 그룹은 아무
-- 판정에도 안 걸린다. 목록은 `PermissionResource` 17 이고 `permission_resource_check` 와 같은 글자다 — 둘이 열거형과 같은지는
-- `EnumConstraintTest` 가 잰다.
--
-- **왜 검증함(`not valid` 아님)**: 표가 시드만 든다(`order`·`user`·`inquiry` — `V6`·`V24`·`V62`·`V112`). main 코드는 읽기만 하고
-- update 금지 트리거가 없다. 운영도 같은 마이그레이션을 지나서 기존 행이 목록 안이다 — 목록 밖이면 배포가 여기서 죽는 것이 맞다.
alter table permission_field_group add constraint permission_field_group_resource_check check (resource in (
    'audit', 'batch', 'compensation', 'coupon', 'coupon_issue', 'inquiry', 'metric', 'order', 'payment',
    'product', 'review', 'role', 'sales_stats', 'seller_member', 'settlement', 'user', 'webhook'
));
