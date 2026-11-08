-- 감사 로그의 대상 종류를 값 목록으로 닫는다(`Q381`).
--
-- **왜**: `target_type` 은 자유 글자였다. 접속기록 점검(`AuditLogReviewService`)이 `user` 로 갈리고
-- 관리자 필터가 이 칸으로 거르는데, 오타가 조용히 쌓이고 필터는 모르는 값에 0건을 줬다.
-- 분기하는 값이라 `check` + 열거형이다(`coding-rules.md` 「열거값을 어디에 두나」). 목록은 `AuditTargetType` 과 같고
-- 둘이 같은지는 `EnumConstraintTest` 가 잰다 — 행 하나를 가리키는 엔티티 12 와 권한 표의 자원(거부 기록이 그대로 쓴다)이다.
--
-- **왜 `not valid`**: `V130` 트리거 `audit_log_guard` 가 update 를 막아서 기존 행을 못 고친다. `not valid` 는
-- 새로 들어오는 행만 검사하고 있던 행은 안 본다 — 배포가 기존 값 때문에 죽지 않는다. 운영의 기존 값이 목록 밖이면
-- `Q417` 이 열거형에 더한다. `null` 은 `check` 를 지난다(대상 없는 사건 — `Target.none()`).
alter table audit_log add constraint audit_log_target_type_check check (target_type in (
    'user', 'product', 'review', 'seller', 'seller_order', 'seller_invitation', 'coupon', 'coupon_issue',
    'compensation', 'copyright_report', 'webhook_endpoint', 'webhook_delivery',
    'audit', 'batch', 'inquiry', 'metric', 'order', 'payment', 'role', 'sales_stats', 'seller_member',
    'settlement', 'webhook'
)) not valid;
