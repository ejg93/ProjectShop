package com.projectshop.shop.webhook;

import java.util.Arrays;
import java.util.Locale;

/**
 * 발송 한 줄이 어디까지 왔나(`30`·`31`). 목록은 {@code webhook_delivery_status_check} 다(`V111`).
 *
 * <p><b>실패가 둘이다.</b> {@link #FAILED} 는 다시 보내도 안 될 것(4xx·안쪽 주소)이고 {@link #EXHAUSTED} 는 다시 보낼 수
 * 있었는데 횟수를 다 쓴 것이다. 재발송은 둘 다 받는다 — 셀러가 받는 쪽을 고친 뒤 4xx 였던 것도 다시 보낼 길이 필요하다(`31`).
 */
enum WebhookDeliveryStatus {

    PENDING, SENT, FAILED, EXHAUSTED;

    /** 다시 보낼 수 있나 — 실패로 닫힌 둘. 가는 중이거나 간 것을 다시 보내면 받는 쪽에 두 번 간다(`31`) */
    boolean resendable() {
        return this == FAILED || this == EXHAUSTED;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WebhookDeliveryStatus of(String storedCode) {
        return Arrays.stream(values())
                .filter(status -> status.code().equals(storedCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 웹훅 발송 상태: " + storedCode));
    }
}
