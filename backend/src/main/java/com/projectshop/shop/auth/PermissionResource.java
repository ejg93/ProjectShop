package com.projectshop.shop.auth;

import java.util.Locale;

import com.projectshop.shop.audit.AuditTargetType;

/**
 * 권한 판정이 묻는 자원 종류({@code permission.resource}, `Q409a`). {@link PermissionEvaluator} 의 입구가 이 타입을 받는다.
 *
 * <p><b>왜 열거형인가</b> — 글자로 받으면 권한 표에 없는 이름으로도 판정을 부를 수 있었다. 그 판정은 거부되고, 거부 기록이
 * 대상 종류를 고르다 던져서 403 대신 500 이 됐다(2026-11-08 마무리 80차 리뷰). 지금은 목록 밖 자원을 컴파일이 막는다.
 *
 * <p><b>왜 {@link AuditTargetType} 과 다른 타입인가</b> — 그쪽은 엔티티 전용 종류(주문 줄·셀러 초대·웹훅 배달 …)까지 든다.
 * 그것들은 권한 자원이 아니라서 판정 입구가 받으면 규칙 0개로 조용히 403 이 된다. 대신 상수마다 감사 대상 종류를 들어
 * 거부 기록이 고르는 자리를 없앴다({@link #auditTarget()}).
 *
 * <p><b>시드가 원천이다</b> — 권한은 데이터다. 이 목록과 시드의 {@code select distinct resource from permission} 이
 * 양방향으로 같은지 {@code AuditTargetTypeTest} 가 잰다.
 */
public enum PermissionResource {

    AUDIT(AuditTargetType.AUDIT),
    BATCH(AuditTargetType.BATCH),
    COMPENSATION(AuditTargetType.COMPENSATION),
    COUPON(AuditTargetType.COUPON),
    COUPON_ISSUE(AuditTargetType.COUPON_ISSUE),
    INQUIRY(AuditTargetType.INQUIRY),
    METRIC(AuditTargetType.METRIC),
    ORDER(AuditTargetType.ORDER),
    PAYMENT(AuditTargetType.PAYMENT),
    PRODUCT(AuditTargetType.PRODUCT),
    REVIEW(AuditTargetType.REVIEW),
    ROLE(AuditTargetType.ROLE),
    SALES_STATS(AuditTargetType.SALES_STATS),
    SELLER_MEMBER(AuditTargetType.SELLER_MEMBER),
    SETTLEMENT(AuditTargetType.SETTLEMENT),
    USER(AuditTargetType.USER),
    WEBHOOK(AuditTargetType.WEBHOOK);

    private final AuditTargetType target;

    PermissionResource(AuditTargetType target) {
        this.target = target;
    }

    /** DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 읽는다 */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 거부 기록의 대상 종류 */
    public AuditTargetType auditTarget() {
        return target;
    }
}
