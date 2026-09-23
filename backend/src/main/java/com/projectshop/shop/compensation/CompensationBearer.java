package com.projectshop.shop.compensation;

import java.util.Arrays;

/**
 * 누가 무나(`V69` 의 {@code compensation_bearer_check}). <b>사유로 갈린다</b> — 우리 결제나 시스템이 멈춰서 늦은 건까지
 * 셀러가 물면 그 정산서가 거짓이 된다. {@code SELLER} 인 것만 정산에서 빠진다.
 */
enum CompensationBearer {

    SELLER("seller"),
    PLATFORM("platform");

    private final String code;

    CompensationBearer(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static CompensationBearer of(String storedCode) {
        return Arrays.stream(values())
                .filter(bearer -> bearer.code.equals(storedCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 배상 부담 주체: " + storedCode));
    }
}
