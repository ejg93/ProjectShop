package com.projectshop.shop.order;

import java.util.Arrays;

/**
 * 택배사(`57`). {@code seller_order.carrier_code} 의 값이다({@code seller_order_carrier_code_check}).
 *
 * <p><b>국내 택배사 다섯만 둔다.</b> 직접 전달·화물처럼 송장이 없는 발송은 지금 흐름에 없다 — 생기면 여기에 값을 더하고
 * 송장 짝 제약을 같이 고친다.
 */
enum Carrier {

    CJ("cj"),
    HANJIN("hanjin"),
    LOTTE("lotte"),
    EPOST("epost"),
    LOGEN("logen");

    private final String code;

    Carrier(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** 저장값을 열거값으로 바꾼다. 발송 전이라 없으면 {@code null} 이다 */
    public static Carrier of(String storedCode) {
        if (storedCode == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(carrier -> carrier.code.equals(storedCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 택배사: " + storedCode));
    }
}
