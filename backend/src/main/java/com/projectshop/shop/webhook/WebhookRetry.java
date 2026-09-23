package com.projectshop.shop.webhook;

import java.time.Duration;

/**
 * 웹훅 재시도의 수(`31`). <b>상수와 계산이 여기 한 곳이다</b> — 스위퍼가 쓰고 시험이 잰다.
 *
 * <p>30초에서 시작해 두 배씩 미루고 한 시간을 안 넘긴다. {@value #MAX_ATTEMPTS} 번을 다 쓰면 {@code exhausted} 로 멈추고
 * 셀러가 화면에서 손으로 다시 보낸다(`Q175`). 여덟 번이면 첫 실패부터 약 네 시간을 두드린다 —
 * 받는 쪽이 밤새 죽어 있으면 아침에 셀러가 보고 다시 보낸다.
 */
final class WebhookRetry {

    /** 첫 재시도까지 */
    static final Duration BASE_DELAY = Duration.ofSeconds(30);

    /** 재시도 간격의 상한 */
    static final Duration MAX_DELAY = Duration.ofHours(1);

    /** 이만큼 보내고도 안 되면 멈춘다 */
    static final int MAX_ATTEMPTS = 8;

    private WebhookRetry() {
    }

    /** {@code attempts} 번 실패한 뒤 기다릴 시간 */
    static Duration backoff(int attempts) {
        long seconds = BASE_DELAY.toSeconds() << Math.min(attempts - 1, 20);
        return Duration.ofSeconds(Math.min(seconds, MAX_DELAY.toSeconds()));
    }

    /** 이번 결과로 줄이 갈 곳. {@code attempts} 는 이번 시도까지 센 수다 */
    static WebhookDeliveryStatus next(WebhookSender.Result result, int attempts) {
        if (result.succeeded()) {
            return WebhookDeliveryStatus.SENT;
        }
        if (!result.retryable()) {
            return WebhookDeliveryStatus.FAILED;
        }
        return attempts >= MAX_ATTEMPTS ? WebhookDeliveryStatus.EXHAUSTED : WebhookDeliveryStatus.PENDING;
    }
}
