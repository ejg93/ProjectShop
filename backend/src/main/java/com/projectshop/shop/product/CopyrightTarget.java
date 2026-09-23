package com.projectshop.shop.product;

import java.util.Arrays;

/**
 * 무엇을 신고했나(`Q196`, `V109` 의 {@code copyright_report_target_check}).
 *
 * <p>사진이 지워지면 가리키는 칸은 비지만 이 값은 남는다 — 판정 이력이 「어느 종류의 사진이었나」를 잃지 않게.
 */
enum CopyrightTarget {

    PRODUCT_IMAGE("product_image"),
    REVIEW_IMAGE("review_image");

    private final String code;

    CopyrightTarget(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CopyrightTarget of(String storedCode) {
        return Arrays.stream(values())
                .filter(target -> target.code.equals(storedCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 신고 대상: " + storedCode));
    }
}
