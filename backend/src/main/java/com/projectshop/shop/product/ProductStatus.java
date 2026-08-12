package com.projectshop.shop.product;

import java.util.Arrays;

/**
 * 상품의 업무 상태(`D7`). 저장값은 {@code product.status} 고 목록은 {@code product_status_check} 다.
 *
 * <p><b>{@link SkuStatus} 와 타입이 갈린다.</b> 둘 다 {@code on_sale} 이라는 같은 글자를 쓰는데
 * 뜻이 다르다 — 상품이 팔리는 중인 것과 조합 하나가 팔리는 중인 것은 다른 사실이다.
 * 문자열로 두면 검색으로도 안 갈리고, 한쪽 값을 다른 쪽 자리에 넣어도 컴파일이 통과한다.
 *
 * <p><b>{@code SOLD_OUT} 은 사람이 옮기는 상태가 아니다.</b> 재고에서 파생된다 —
 * 그래서 {@link ProductTransitions} 의 표에 안 나온다.
 */
enum ProductStatus {

    DRAFT,
    PENDING_REVIEW,
    ON_SALE,
    SOLD_OUT,
    SUSPENDED,
    BLOCKED;

    /** 저장값. DB 는 소문자고 응답은 대문자다(`D5` 「형식」) */
    String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 상태로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> 조용히 통과시키면 그 뒤의 전이·권한 판정이 전부
     * 엉뚱한 답을 내고, 어디서 틀렸는지 남는 것이 없다. {@code product_status_check} 가
     * 이미 막고 있으므로 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    static ProductStatus of(String code) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 상품 상태다: " + code));
    }
}
