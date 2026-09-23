package com.projectshop.shop.webhook;

import java.util.Arrays;

import com.projectshop.shop.support.EventType;

/**
 * 셀러가 구독할 수 있는 사건(`29`). 목록은 {@code webhook_endpoint_event_types_check} 와 짝이다(`V110`).
 *
 * <p><b>{@link EventType} 의 부분집합이다.</b> 셀러 경계를 넘는 사건을 안 연다(`D14`) — 주문 전체의 결제 사건은 셀러 여럿에
 * 걸치고, 배치 사건은 내부 것이고, 재고 원장은 바깥에 안 낸다(`D9`). 요청·응답은 이 열거형의 이름(대문자)이고
 * 봉투의 {@code type} 은 {@link #code} 다(`D12`).
 */
enum WebhookEventType {

    SELLER_ORDER_STATUS_CHANGED(EventType.SELLER_ORDER_STATUS_CHANGED),
    REFUND_STATUS_CHANGED(EventType.REFUND_STATUS_CHANGED),
    RETURN_REQUEST_STATUS_CHANGED(EventType.RETURN_REQUEST_STATUS_CHANGED),
    SETTLEMENT_PAYOUT_CHANGED(EventType.SETTLEMENT_PAYOUT_CHANGED);

    private final EventType event;

    WebhookEventType(EventType event) {
        this.event = event;
    }

    /** 저장값이자 봉투의 {@code type} 이다 */
    public String code() {
        return event.code();
    }

    public static WebhookEventType of(String storedCode) {
        return Arrays.stream(values())
                .filter(type -> type.code().equals(storedCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 웹훅 사건: " + storedCode));
    }
}
