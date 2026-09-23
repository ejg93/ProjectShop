package com.projectshop.shop.webhook;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * 웹훅 한 건을 서명해서 보낸다(`30`). <b>바깥 시스템이다</b> — 트랜잭션 안에서 부르면 {@code ArchitectureTest} 가 막는다.
 *
 * <p><b>서명은 Standard Webhooks 다</b>(2026-09-23 사용자 결정). 헤더 셋 — {@code webhook-id}(사건 id)·
 * {@code webhook-timestamp}(epoch 초)·{@code webhook-signature: v1,<base64(HMAC-SHA256(secret, "id.ts.body"))>}.
 * 받는 쪽이 타임스탬프로 재전송을 거르고 id 로 중복을 거른다.
 *
 * <p><b>리다이렉트를 안 따라간다</b> — 바깥 주소가 안쪽으로 되돌려 보내는 길을 막는다(`D14` SSRF).
 * <b>타임아웃은 여기 한 곳이다</b> — 받는 쪽이 늦으면 스위퍼 한 회차가 통째로 늦는다.
 *
 * <p><b>응답 본문을 안 읽는다.</b> 요청 타임아웃은 응답 머리까지만 잰다 — 본문을 끝까지 기다리면 머리를 준 뒤 본문을
 * 조금씩 흘리는 서버 하나가 스위퍼를 타임아웃 없이 붙잡고, 그동안 모든 셀러의 발송이 선다(마무리 47차 독립 리뷰).
 * 우리가 보는 것은 상태 코드뿐이라 머리가 오면 끝이다.
 */
@Component
class WebhookSender {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** 한 건이 걸리는 가장 긴 시간. 스위퍼가 집은 표시를 이것으로 잰다 */
    static final Duration MAX_EXCHANGE = CONNECT_TIMEOUT.plus(REQUEST_TIMEOUT);

    /**
     * 보낸 결과. 응답을 받았으면 코드가, 못 받았으면 오류가 있다.
     *
     * @param blocked 우리가 안 보냈다 — 주소가 안쪽을 가리키거나(`WebhookUrlPolicy`) 시크릿을 못 푼다. 자동으로는 다시 안 보낸다
     */
    record Result(Integer statusCode, String error, boolean blocked) {

        Result(Integer statusCode, String error) {
            this(statusCode, error, false);
        }

        boolean succeeded() {
            return statusCode != null && statusCode >= 200 && statusCode < 300;
        }

        /**
         * 다시 보낼 만한가(`31`). <b>4xx 는 영구다</b> — 받는 쪽이 요청을 거절했으니 같은 것을 다시 보내도 같다. 5xx·타임아웃·연결 실패는
         * 일시다. <b>408·429 는 4xx 인데 일시다</b>(RFC 9110 §15.5.9, RFC 6585 §4) — 기다리면 받는다고 받는 쪽이 말한 것이다.
         */
        boolean retryable() {
            if (blocked || succeeded()) {
                return false;
            }
            return statusCode == null || statusCode >= 500 || statusCode == 408 || statusCode == 429;
        }
    }

    private final HttpClient client;
    private final Duration requestTimeout;

    WebhookSender() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    /** 시험이 느린 서버를 짧게 기다리려고 쓴다 */
    WebhookSender(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
    }

    Result send(URI url, byte[] secret, String eventId, long timestamp, String body) {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(requestTimeout)
                .header("content-type", "application/json")
                .header("webhook-id", eventId)
                .header("webhook-timestamp", String.valueOf(timestamp))
                .header("webhook-signature", signature(secret, eventId, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            // 머리가 오면 끝이다 — 본문을 읽지 않고 닫는다(위 「응답 본문을 안 읽는다」).
            response.body().close();
            return new Result(response.statusCode(), null);
        } catch (HttpTimeoutException e) {
            return new Result(null, "타임아웃");
        } catch (IOException e) {
            return new Result(null, "연결 실패: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(null, "보내다 끊겼다");
        }
    }

    /** {@code v1,} 뒤에 base64(HMAC-SHA256(secret, "id.ts.body")) */
    static String signature(byte[] secret, String eventId, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal((eventId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(digest);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 서명을 못 만들었다", e);
        }
    }
}
