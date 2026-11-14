package com.projectshop.shop.audit;

import java.util.Locale;

/**
 * 감사 로그가 「무엇에 대한 사건인가」를 적는 대상 종류({@code audit_log.target_type}, `Q381`).
 *
 * <p><b>값 목록을 닫는다</b>({@code coding-rules.md} 「열거값을 어디에 두나」). 접속기록 점검이
 * {@code user} 로 갈리고(`AuditLogReviewService`) 관리자 필터가 이 칸으로 거른다 — 분기하는 값이라
 * {@code check} + 열거형이다({@code audit_log_target_type_check}, `V133`). 그전에는 자유 글자라
 * 오타가 조용히 쌓이고 필터는 모르는 값에 0건 200 을 줬다.
 *
 * <p><b>{@code permission.resource} 도 여기 든다.</b> 권한 거부 기록의 대상이 그 자원이라서다. 두 목록을 가르면 같은 칸의
 * {@code check} 가 둘이 된다. {@code PermissionResource} 가 상수마다 이 종류를 든다 — 목록 밖 자원은 컴파일이 막는다(`Q409a`).
 */
public enum AuditTargetType {

    // 행 하나를 가리키는 엔티티(2026-11-08 `.record(` 호출에서 셌다)
    USER,
    PRODUCT,
    REVIEW,
    SELLER,
    SELLER_ORDER,
    SELLER_INVITATION,
    COUPON,
    COUPON_ISSUE,
    COMPENSATION,
    COPYRIGHT_REPORT,
    WEBHOOK_ENDPOINT,
    WEBHOOK_DELIVERY,

    // 권한 표의 자원 중 위에 없는 것(거부 기록, 2026-11-08 마이그레이션의 permission insert 에서 셌다)
    AUDIT,
    BATCH,
    /** 판매·결제 중개 명세 분기(`Q456`). 내려받기·제출 기록과 권한 거부가 이 대상으로 남는다 */
    BROKERAGE_STATEMENT,
    INQUIRY,
    METRIC,
    ORDER,
    PAYMENT,
    ROLE,
    SALES_STATS,
    SELLER_MEMBER,
    SETTLEMENT,
    WEBHOOK;

    /** DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 읽는다 */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
