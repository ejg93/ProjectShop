package com.projectshop.shop.product;

import java.util.Arrays;

/**
 * 재고가 움직인 사유. 저장값은 {@code sku_stock_movement.reason} 이고
 * 목록은 {@code sku_stock_movement_reason_check} 다(`43a-24`).
 *
 * <p><b>값이 마이그레이션 둘을 지나며 늘었다.</b> {@code V41} 이 넷으로 열었고
 * {@code V64} 가 {@link #RETURN_RESTOCKED} 를 더해 다섯이 됐는데, 그때마다 코드가 따라왔는지
 * <b>아무도 안 봤다</b> — 문자열이라 볼 자리가 없었다.
 *
 * <p><b>{@code public} 인 근거는 재고를 옮기는 쪽이 다른 패키지여서다.</b> 표는 상품 자원인데
 * {@code OrderService}·{@code OrderStatusService} 가 주문·취소·반품에서 {@code move_stock()} 을 부른다.
 * 이 패키지 밖에서 안 부르게 되면 좁힌다(`43a-27` 의 규칙).
 *
 * <p><b>둘은 Java 가 안 만든다.</b> {@link #INITIAL} 은 재고를 처음 심을 때, {@link #ADJUSTMENT} 는
 * 사람이 손으로 맞출 때 쓰는 값이라 지금은 SQL·시드에만 있다. 목록에서 빼면 그 자리가 막히므로 같이 든다.
 */
public enum StockReason {

    /** 재고를 처음 심는다 */
    INITIAL,

    /** 주문이 들어와 뺀다 */
    ORDER_PLACED,

    /** 주문이 취소돼 되돌린다 */
    ORDER_CANCELLED,

    /** 반품이 인정돼 되돌린다(`V64`) */
    RETURN_RESTOCKED,

    /** 사람이 손으로 맞춘다 */
    ADJUSTMENT;

    /** 저장값. DB 는 소문자다 */
    public String code() {
        return name().toLowerCase();
    }

    /**
     * 저장값을 사유로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code sku_stock_movement_reason_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    public static StockReason of(String code) {
        return Arrays.stream(values())
                .filter(reason -> reason.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 재고 이동 사유다: " + code));
    }
}
