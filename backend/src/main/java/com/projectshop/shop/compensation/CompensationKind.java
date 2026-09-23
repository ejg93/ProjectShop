package com.projectshop.shop.compensation;

import java.util.Arrays;

/**
 * 무엇에 대한 배상인가(`V69` 의 {@code compensation_kind_check}, `D2` R38·R39).
 *
 * <p>소비자분쟁해결기준 별표2 「인터넷쇼핑몰업」의 갈래를 따른다 — 안 갔으면 2)·6), 늦게 갔으면 3).
 */
enum CompensationKind {

    NON_DELIVERY("non_delivery"),
    LATE_DELIVERY("late_delivery");

    private final String code;

    CompensationKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CompensationKind of(String storedCode) {
        return Arrays.stream(values())
                .filter(kind -> kind.code.equals(storedCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 배상 종류: " + storedCode));
    }
}
