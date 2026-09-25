package com.projectshop.shop.webhook;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * 웹훅 한 건을 서명해서 보낸다(`30`). <b>바깥 시스템이다</b> — 트랜잭션 안에서 부르면 {@code ArchitectureTest} 가 막는다.
 *
 * <p><b>서명은 Standard Webhooks 다</b>(2026-09-23 사용자 결정). 헤더 셋 — {@code webhook-id}(사건 id)·
 * {@code webhook-timestamp}(epoch 초)·{@code webhook-signature: v1,<base64(HMAC-SHA256(secret, "id.ts.body"))>}.
 * 받는 쪽이 타임스탬프로 재전송을 거르고 id 로 중복을 거른다.
 *
 * <p><b>검사한 주소에만 연결한다</b>(`Q210`). {@link WebhookUrlPolicy.Target} 을 받아 그 주소 목록을 연결 관리자의 이름 풀이로 준다 —
 * 연결이 이름을 다시 풀지 않아서 검사와 연결 사이에 DNS 가 바뀌어도(리바인딩) 검사한 곳으로 간다. JDK {@code HttpClient} 에는 이름
 * 풀이 훅이 없어서 Apache HttpClient 5 로 옮겼다. 호스트명은 그대로라 TLS 의 SNI·인증서 검증은 안 깨진다.
 * <b>검사 안 된 이름은 안 푼다</b> — 이 발송기로는 어떤 이름도 시스템 풀이로 새지 않는다.
 *
 * <p><b>리다이렉트를 안 따라간다</b> — 바깥 주소가 안쪽으로 되돌려 보내는 길을 막는다(`D14` SSRF). 자동 재시도도 끈다.
 * <b>타임아웃은 여기 한 곳이다</b> — 받는 쪽이 늦으면 스위퍼 한 회차가 통째로 늦는다.
 *
 * <p><b>응답 본문을 안 읽는다.</b> 우리가 보는 것은 상태 코드뿐이라 머리가 오면 끝이다 — 본문을 끝까지 기다리면 머리를 준 뒤 본문을
 * 조금씩 흘리는 서버 하나가 스위퍼를 붙잡는다(마무리 47차 독립 리뷰). 머리를 받으면 요청을 끊어 연결을 버린다.
 *
 * <p><b>한 건의 상한이 따로 있다.</b> Apache 의 응답 타임아웃은 읽기 사이의 무활동이라 JDK 의 「머리까지」 상한과 다르다 —
 * 머리를 한 바이트씩 흘리는 서버는 그것만으로 못 끊는다. {@link #MAX_EXCHANGE} 가 지나면 요청을 끊는다.
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

    /** 지금 이 스레드가 보내는 건의 검사 결과. 발송기는 스레드마다 한 요청이라 이것으로 이름 풀이에 넘긴다 */
    private static final ThreadLocal<WebhookUrlPolicy.Target> PINNED = new ThreadLocal<>();

    /** 검사한 이름이면 검사한 주소를, 아니면 거절한다 — 시스템 풀이로 넘기지 않는다 */
    static final DnsResolver PINNED_RESOLVER = new DnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            WebhookUrlPolicy.Target target = PINNED.get();
            if (target == null || !host.equalsIgnoreCase(target.uri().getHost())) {
                throw new UnknownHostException("검사 안 된 이름이다: " + host);
            }
            return target.addresses().toArray(InetAddress[]::new);
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

    private final CloseableHttpClient client;
    private final Duration maxExchange;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webhook-sender-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    WebhookSender() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    /** 시험이 느린 서버를 짧게 기다리려고 쓴다 */
    WebhookSender(Duration connectTimeout, Duration requestTimeout) {
        this(connectTimeout, requestTimeout, PINNED_RESOLVER);
    }

    WebhookSender(Duration connectTimeout, Duration requestTimeout, DnsResolver resolver) {
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(connectTimeout))
                                .setSocketTimeout(Timeout.of(requestTimeout))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(requestTimeout))
                        .setRedirectsEnabled(false)
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .build();
        this.maxExchange = connectTimeout.plus(requestTimeout);
    }

    Result send(WebhookUrlPolicy.Target target, byte[] secret, String eventId, long timestamp, String body) {
        HttpPost post = new HttpPost(target.uri());
        post.setHeader("webhook-id", eventId);
        post.setHeader("webhook-timestamp", String.valueOf(timestamp));
        post.setHeader("webhook-signature", signature(secret, eventId, timestamp, body));
        post.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8)));

        ScheduledFuture<?> deadline = watchdog.schedule(post::abort, maxExchange.toMillis(), TimeUnit.MILLISECONDS);
        PINNED.set(target);
        try {
            ClassicHttpResponse response = client.executeOpen(null, post, null);
            int status = response.getCode();
            // 머리가 오면 끝이다 — 본문을 읽지 않고 요청을 끊어 연결을 버린다(위 「응답 본문을 안 읽는다」).
            // 끊은 뒤 닫기가 던지는 것은 결과가 아니다 — try-with-resources 로 두면 그것이 받은 상태 코드를 덮는다.
            post.abort();
            closeQuietly(response);
            return new Result(status, null);
        } catch (InterruptedIOException e) {
            if (Thread.currentThread().isInterrupted()) {
                return new Result(null, "보내다 끊겼다");
            }
            return new Result(null, "타임아웃");
        } catch (IOException e) {
            return new Result(null, "연결 실패: " + e.getClass().getSimpleName());
        } finally {
            PINNED.remove();
            deadline.cancel(false);
        }
    }

    private static void closeQuietly(ClassicHttpResponse response) {
        try {
            response.close();
        } catch (IOException ignored) {
            // 끊은 연결을 닫다 난 오류다. 결과는 이미 받았다
        }
    }

    @PreDestroy
    void close() throws IOException {
        watchdog.shutdownNow();
        client.close();
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
