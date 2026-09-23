package com.projectshop.shop.review;

import java.util.Arrays;
import java.util.Locale;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 후기 신고의 상태(`Q167`, {@code review_report.status}, `D7`).
 *
 * <p><b>처리한 신고는 다시 못 연다.</b> {@code check_review_report_transition} 이 DB 에서 막는다 —
 * 되돌릴 수 있으면 「거절했다가 조용히 승인」이 가능하고, 그 사이의 판단이 기록에서 사라진다.
 * 판단이 뒤집힌 것은 신고가 아니라 <b>후기 쪽 동작(되살림)</b>으로 남긴다.
 */
enum ReviewReportStatus {

    /** 접수됐다. 관리자가 아직 안 봤다 */
    PENDING,

    /** 사유에 해당한다고 봤다. 후기가 그 사유로 내려간다 */
    ACCEPTED,

    /** 해당하지 않는다고 봤다. 후기는 그대로다 */
    REJECTED;

    /** DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 읽는다 */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ReviewReportStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 신고 상태다: " + code));
    }

    static ReviewReportStatus ofRequest(String name) {
        return Arrays.stream(values())
                .filter(status -> status.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.VALIDATION_FAILED, "모르는 상태다: " + name));
    }
}
