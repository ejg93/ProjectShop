package com.projectshop.shop.support;

import java.util.Arrays;

/**
 * 바깥에 알리는 사건의 종류. 저장값은 {@code outbox_event.type} 이고
 * 목록은 {@code outbox_event_type_check} 다(`Q57`, `D12`).
 *
 * <p><b>이름이 {@code shop.<자원>.<사건>} 이다.</b> 자원은 표 이름(단수, `D22`),
 * 사건은 과거형이다. 그래서 {@code name()} 을 소문자로 바꾸는 것으로는 저장값이 안 나오고
 * {@link #code} 를 따로 든다 — 다른 열거형과 다른 자리라 여기 적어 둔다.
 *
 * <p><b>이 값은 대외 계약이다.</b> 한 번 내보내면 소비자가 그 문자열로 분기한다 —
 * 바꾸는 것은 새 이름을 더하고 옛 이름을 한동안 같이 내보내는 일이지 고치는 일이 아니다(`D12` 「버전」).
 *
 * <p><b>패키지를 안 판다</b>(`D23` 「자원이 아닌데 패키지를 파는 경우」). 사건은 자원이 아니고,
 * 열거형 하나라 {@code support} 에 둬도 순환이 안 난다.
 *
 * <p><b>{@code public} 인 근거는 소비자다</b>(`Q76`, `D23` 「`public` 에는 근거가 있어야 한다」).
 * {@code notification} 패키지의 소비자가 이 값으로 분기한다 — 열지 않으면 그쪽이 같은 문자열을
 * 다시 적게 되고, 실제로 그랬다. 표를 채우는 것은 여전히 트리거라 <b>쓰는 곳이 발행 쪽에는 없다.</b>
 *
 * <p><b>{@code EnumConstraintTest} 가 SQL 의 닫힌 목록과 대조한다.</b> 목록이 갈리면 그 테스트가 빨개진다.
 */
public enum EventType {

    ORDER_STATUS_CHANGED("shop.order.status_changed"),
    SELLER_ORDER_STATUS_CHANGED("shop.seller_order.status_changed"),
    SKU_STOCK_MOVED("shop.sku.stock_moved"),
    REFUND_STATUS_CHANGED("shop.refund.status_changed"),
    RETURN_REQUEST_STATUS_CHANGED("shop.return_request.status_changed"),
    SETTLEMENT_PAYOUT_CHANGED("shop.settlement.payout_changed"),
    BATCH_RUN_FINISHED("shop.batch_run.finished");

    private final String code;

    EventType(String code) {
        this.code = code;
    }

    /** 저장값이자 봉투의 {@code type} 이다 */
    public String code() {
        return code;
    }

    /**
     * 저장값을 종류로 되돌린다.
     *
     * <p><b>모르는 값이면 터진다.</b> {@code outbox_event_type_check} 가 이미 막고 있으므로
     * 여기 오는 모르는 값은 <b>마이그레이션과 이 enum 이 어긋났다</b>는 뜻이다.
     */
    public static EventType of(String code) {
        return Arrays.stream(values())
                .filter(type -> type.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 사건 종류다: " + code));
    }
}
