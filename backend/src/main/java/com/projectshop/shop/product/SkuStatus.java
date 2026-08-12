package com.projectshop.shop.product;

import java.util.Arrays;

/**
 * 조합 하나의 판매 상태. 저장값은 {@code sku.status} 고 목록은 {@code sku_status_check} 다.
 *
 * <p><b>{@link ProductStatus} 와 목록이 다르다.</b> 조합은 검수를 따로 받지 않아서
 * {@code draft}·{@code pending_review} 가 없고, 제재도 상품 단위라 {@code blocked} 가 없다.
 *
 * <p>{@code SUSPENDED} 로 내려가는 길이 둘이다 — 셀러가 조합을 지울 때(`7`)와
 * 주문에 쓰인 조합이라 못 지울 때(`10-2`)다. 뒤쪽은 지나간 주문의 옵션 라벨이
 * 가리키던 것을 남겨 두려는 것이다.
 */
enum SkuStatus {

    ON_SALE,
    SUSPENDED;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /** 모르는 값이면 터진다. 이유는 {@link ProductStatus#of} 와 같다 */
    static SkuStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 조합 상태다: " + code));
    }
}
