package com.projectshop.shop.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.support.ListQuery.Paging;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 웹훅 발송(`30`). 로컬 HTTP 서버로 받아서 <b>받는 쪽이 할 일</b>을 그대로 해 본다 — 같은 시크릿으로 서명을 검증하고,
 * 본문의 열거값이 API 와 같은 대문자인지 본다.
 *
 * <p>지키는 것: <b>서명이 맞고</b>, <b>셀러 경계를 안 넘고</b>, <b>안쪽으로 바뀐 주소에는 발송 때도 안 보내고</b>,
 * <b>늦은 서버는 타임아웃으로 끝난다</b>.
 */
@DisplayName("웹훅 발송")
class WebhookDeliveryTest extends PostgresTestBase {

    @Autowired
    private WebhookEndpointService endpoints;

    @Autowired
    private WebhookSweeper sweeper;

    @Autowired
    private WebhookDeliveryQuery deliveries;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcClient jdbc;

    private record Received(Map<String, String> headers, String body) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int responseCode = 200;

    private AuthFixture fixture;
    private long sellerId;
    private long owner;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                received.add(new Received(Map.of(
                        "webhook-id", exchange.getRequestHeaders().getFirst("webhook-id"),
                        "webhook-timestamp", exchange.getRequestHeaders().getFirst("webhook-timestamp"),
                        "webhook-signature", exchange.getRequestHeaders().getFirst("webhook-signature")),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            exchange.sendResponseHeaders(responseCode, -1);
            exchange.close();
        });
        server.start();

        fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("s-deliver", "발송셀러");
        fixture.verifySeller(sellerId);
        owner = fixture.insertUser("deliver-owner@test.local", "대표");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("받는 쪽이 같은 시크릿으로 서명을 검증할 수 있고 본문 열거값은 대문자다")
    void signedAndApiCased() {
        String secret = register(Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED));
        long eventId = emitSellerOrderShipped(sellerId);

        assertThat(sweeper.fanOut()).isEqualTo(1);
        assertThat(sweeper.deliverDue(OffsetDateTime.now().plusSeconds(1))).isEqualTo(1);

        Received request = received.getFirst();
        assertThat(request.headers().get("webhook-id")).isEqualTo(String.valueOf(eventId));
        byte[] key = Base64.getDecoder().decode(secret.substring("whsec_".length()));
        assertThat(request.headers().get("webhook-signature")).isEqualTo(WebhookSender.signature(key,
                request.headers().get("webhook-id"), Long.parseLong(request.headers().get("webhook-timestamp")),
                request.body()));

        JsonNode data = objectMapper.readTree(request.body()).get("data");
        assertThat(data.get("to_status").asString()).as("API 와 같은 표기(`D5`)").isEqualTo("SHIPPING");
        assertThat(statusOfDeliveries()).containsExactly("sent");
    }

    @Test
    @DisplayName("남의 셀러 사건과 구독 안 한 사건은 안 간다")
    void staysWithinTheSeller() {
        register(Set.of(WebhookEventType.REFUND_STATUS_CHANGED));
        long other = fixture.insertSeller("s-deliver-other", "남의셀러");
        emitSellerOrderShipped(other);
        emitSellerOrderShipped(sellerId);

        assertThat(sweeper.fanOut()).as("구독은 환불뿐이고, 남의 셀러 사건은 셀러가 다르다").isZero();
    }

    /** 등록 뒤에 주소가 안쪽을 가리키게 될 수 있다 — 발송 때 다시 본다(`D14`) */
    @Test
    @DisplayName("안쪽으로 바뀐 주소에는 발송 때도 안 보낸다")
    void rechecksTheUrlAtDelivery() {
        register(Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED));
        emitSellerOrderShipped(sellerId);
        jdbc.sql("update webhook_endpoint set url = 'https://10.0.0.1/hook' where seller_id = :id")
                .param("id", sellerId).update();

        sweeper.fanOut();
        assertThat(sweeper.deliverDue(OffsetDateTime.now().plusSeconds(1))).isZero();

        assertThat(received).isEmpty();
        assertThat(statusOfDeliveries()).containsExactly("failed");
        assertThat(jdbc.sql("select last_error from webhook_delivery").query(String.class).single())
                .as("연결 실패가 아니라 검사가 막았다 — 검사를 꺼도 10.0.0.1 은 안 닿아서 상태만으로는 못 가른다")
                .isEqualTo("안쪽 주소로 바뀌었다");
    }

    @Test
    @DisplayName("늦는 서버는 타임아웃으로 끝난다")
    void slowServerTimesOut() throws IOException {
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/hook", exchange -> {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.start();
        try {
            WebhookSender quick = new WebhookSender(Duration.ofMillis(200), Duration.ofMillis(200));
            WebhookSender.Result result = quick.send(
                    URI.create("http://127.0.0.1:" + slow.getAddress().getPort() + "/hook"),
                    new byte[] {1, 2, 3}, "1", 0, "{}");

            assertThat(result.succeeded()).isFalse();
            assertThat(result.error()).isEqualTo("타임아웃");
        } finally {
            slow.stop(0);
        }
    }

    /** 5xx 는 일시다 — 30초에서 시작해 두 배씩 뒤로 미룬다(`31`). 시도 수와 다음 시각이 칸이라 재기동해도 남는다 */
    @Test
    @DisplayName("일시 실패는 지수로 미뤄 다시 보낸다")
    void transientFailureBacksOff() {
        responseCode = 503;
        register(Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED));
        emitSellerOrderShipped(sellerId);
        sweeper.fanOut();
        // 표는 마이크로초까지 담는다 — 나노초가 남으면 같은 시각이 다르게 보인다.
        OffsetDateTime first = OffsetDateTime.now().plusSeconds(1).truncatedTo(ChronoUnit.MICROS);

        sweeper.deliverDue(first);
        assertThat(delivery()).isEqualTo(new DeliveryState("pending", 1, first.plusSeconds(30)));

        OffsetDateTime second = first.plusSeconds(31);
        sweeper.deliverDue(second);
        assertThat(delivery()).isEqualTo(new DeliveryState("pending", 2, second.plusSeconds(60)));
    }

    @Test
    @DisplayName("4xx 는 영구라 한 번에 닫는다 — 408·429 는 빼고")
    void clientErrorFailsAtOnce() {
        responseCode = 400;
        register(Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED));
        emitSellerOrderShipped(sellerId);
        sweeper.fanOut();

        sweeper.deliverDue(OffsetDateTime.now().plusSeconds(1));

        assertThat(delivery().status()).isEqualTo("failed");
        assertThat(new WebhookSender.Result(429, null).retryable()).isTrue();
        assertThat(new WebhookSender.Result(408, null).retryable()).isTrue();
    }

    /** 다 쓰면 멈추고, 셀러가 다시 보내면 시도 수를 이어 센다 */
    @Test
    @DisplayName("횟수를 다 쓰면 소진이고, 다시 보내면 이어 센다")
    void exhaustsThenResends() {
        responseCode = 500;
        register(Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED));
        emitSellerOrderShipped(sellerId);
        sweeper.fanOut();
        OffsetDateTime at = OffsetDateTime.now().plusSeconds(1);
        for (int attempt = 0; attempt < WebhookRetry.MAX_ATTEMPTS; attempt++) {
            sweeper.deliverDue(at);
            at = at.plusHours(2);
        }
        assertThat(delivery().status()).isEqualTo("exhausted");
        assertThat(delivery().attempts()).isEqualTo(WebhookRetry.MAX_ATTEMPTS);
        long deliveryId = jdbc.sql("select webhook_delivery_id from webhook_delivery").query(Long.class).single();
        assertThat(deliveries.find(owner, endpointId(), null, new Paging(0, 20)).items().getFirst().allowedActions())
                .containsExactly("RESEND");

        endpoints.resend(owner, deliveryId);
        responseCode = 200;
        sweeper.deliverDue(at);

        assertThat(delivery().status()).isEqualTo("sent");
        assertThat(delivery().attempts()).isEqualTo(WebhookRetry.MAX_ATTEMPTS + 1);
        assertThatThrownBy(() -> endpoints.resend(owner, deliveryId))
                .isInstanceOfSatisfying(ShopException.class, e ->
                        assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_DELIVERY_NOT_RESENDABLE));
    }

    private record DeliveryState(String status, int attempts, OffsetDateTime nextAttemptAt) {
    }

    private DeliveryState delivery() {
        return jdbc.sql("select status, attempt_count, next_attempt_at from webhook_delivery")
                .query((rs, rowNum) -> new DeliveryState(rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getObject("next_attempt_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("next_attempt_at", OffsetDateTime.class)
                                        .withOffsetSameInstant(OffsetDateTime.now().getOffset())))
                .single();
    }

    private long endpointId() {
        return jdbc.sql("select webhook_endpoint_id from webhook_endpoint where seller_id = :id")
                .param("id", sellerId).query(Long.class).single();
    }

    private String register(Set<WebhookEventType> types) {
        return endpoints.register(owner, sellerId,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook", types).secret();
    }

    /** 셀러 묶음 하나를 만들고 그 발송 사건을 아웃박스에 넣는다. 사건 id 를 돌려준다 */
    private long emitSellerOrderShipped(long seller) {
        long buyer = fixture.insertUser("deliver-buyer-" + seller + "@test.local", "산사람");
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount, commission_total,
                                                shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 3000, 13000)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyer).query(Long.class).single();
        String number = OrderFixture.sellerOrderNumber();
        jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee, status)
                        values (:number, :orderId, :sellerId, 3000, 'shipping')
                        """)
                .param("number", number).param("orderId", orderId).param("sellerId", seller).update();

        jdbc.sql("""
                        select emit_outbox_event('shop.seller_order.status_changed', :number, now(),
                               jsonb_build_object('seller_order_number', :number, 'from_status', 'preparing',
                                                  'to_status', 'shipping', 'actor_type', 'seller'))
                        """)
                .param("number", number).query().singleRow();
        return jdbc.sql("select max(outbox_event_id) from outbox_event where subject = :number")
                .param("number", number).query(Long.class).single();
    }

    private List<String> statusOfDeliveries() {
        return jdbc.sql("""
                        select d.status from webhook_delivery d
                          join webhook_endpoint w on w.webhook_endpoint_id = d.webhook_endpoint_id
                         where w.seller_id = :id order by d.webhook_delivery_id
                        """)
                .param("id", sellerId).query(String.class).list();
    }
}
