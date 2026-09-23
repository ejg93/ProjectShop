package com.projectshop.shop.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDate;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

    /**
     * 셀러 경계(`D14`). <b>구독한 종류는 같게 두고 셀러만 다르게</b> 낸다 — 종류로 걸러지는 사건으로 재면 셀러 조건을 지워도
     * 초록이다(마무리 47차 독립 리뷰가 옛 시험에서 짚었다). 사건마다 {@code subject} 에서 셀러로 가는 길이 달라서 넷을 다 낸다.
     */
    @ParameterizedTest
    @EnumSource(WebhookEventType.class)
    @DisplayName("같은 종류를 구독해도 남의 셀러 사건은 안 간다")
    void staysWithinTheSeller(WebhookEventType type) {
        register(Set.of(type));
        long other = fixture.insertSeller("s-deliver-other", "남의셀러");
        String theirs = emit(type, other);
        String ours = emit(type, sellerId);

        sweeper.fanOut();

        assertThat(deliveredSubjects()).contains(ours).doesNotContain(theirs);
    }

    @Test
    @DisplayName("구독 안 한 종류는 안 간다")
    void onlySubscribedTypes() {
        register(Set.of(WebhookEventType.REFUND_STATUS_CHANGED));
        emitSellerOrderShipped(sellerId);

        assertThat(sweeper.fanOut()).isZero();
    }

    /**
     * 한 줄의 시크릿을 못 풀어도 회차가 안 끊긴다(마무리 47차 독립 리뷰). 끊기면 그 줄이 다음 회차 맨 앞에 다시 서서 뒤의 줄을 전부
     * 막는다 — 키 판을 올리는 날 모든 발송이 조용히 선다.
     */
    @Test
    @DisplayName("시크릿을 못 푼 줄은 실패로 적고 다음 줄을 보낸다")
    void undecryptableRowDoesNotStallTheRound() {
        long broken = endpoints.register(owner, sellerId,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/hook?broken",
                Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED)).webhookEndpointId();
        register(Set.of(WebhookEventType.SELLER_ORDER_STATUS_CHANGED));
        jdbc.sql("update webhook_endpoint set secret_key_version = 2 where webhook_endpoint_id = :id")
                .param("id", broken).update();
        emitSellerOrderShipped(sellerId);
        sweeper.fanOut();

        assertThat(sweeper.deliverDue(OffsetDateTime.now().plusSeconds(1))).isEqualTo(1);

        assertThat(statusOfDeliveries()).containsExactlyInAnyOrder("failed", "sent");
        assertThat(jdbc.sql("select last_error from webhook_delivery where webhook_endpoint_id = :id")
                .param("id", broken).query(String.class).single())
                .startsWith("시크릿을 못 풀었다");
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

    /**
     * 머리를 준 뒤 본문을 조금씩 흘리는 서버(마무리 47차 독립 리뷰). 요청 타임아웃은 머리까지만 재서, 본문을 끝까지 기다리면 이 서버
     * 하나가 스위퍼를 붙잡는다.
     */
    @Test
    @DisplayName("본문을 흘리는 서버도 발송기를 못 붙잡는다")
    void tricklingBodyDoesNotHoldTheSender() throws IOException {
        HttpServer trickle = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        trickle.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write('{');
                out.flush();
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        trickle.start();
        try {
            WebhookSender quick = new WebhookSender(Duration.ofMillis(500), Duration.ofMillis(500));
            long started = System.nanoTime();

            WebhookSender.Result result = quick.send(
                    URI.create("http://127.0.0.1:" + trickle.getAddress().getPort() + "/hook"),
                    new byte[] {1, 2, 3}, "1", 0, "{}");

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(result.succeeded()).as("머리의 2xx 가 곧 결과다").isTrue();
        } finally {
            trickle.stop(0);
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

    /** 이 종류의 사건을 그 셀러 것으로 하나 낸다. {@code subject} 를 돌려준다 */
    private String emit(WebhookEventType type, long seller) {
        SellerOrderRow order = newSellerOrder(seller);
        String subject = switch (type) {
            case SELLER_ORDER_STATUS_CHANGED, RETURN_REQUEST_STATUS_CHANGED -> order.number();
            case REFUND_STATUS_CHANGED -> newRefund(order);
            case SETTLEMENT_PAYOUT_CHANGED -> newSettlement(seller);
        };
        jdbc.sql("select emit_outbox_event(:type, :subject, now(), '{}'::jsonb)")
                .param("type", type.code()).param("subject", subject).query().singleRow();
        return subject;
    }

    private record SellerOrderRow(long orderId, long sellerOrderId, long buyerId, String number) {
    }

    private SellerOrderRow newSellerOrder(long seller) {
        long buyer = fixture.insertUser("deliver-buyer-" + seller + "-" + System.nanoTime() + "@test.local", "산사람");
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount, commission_total,
                                                shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 3000, 13000)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyer).query(Long.class).single();
        String number = OrderFixture.sellerOrderNumber();
        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee, status)
                        values (:number, :orderId, :sellerId, 3000, 'shipping')
                        returning seller_order_id
                        """)
                .param("number", number).param("orderId", orderId).param("sellerId", seller)
                .query(Long.class).single();
        return new SellerOrderRow(orderId, sellerOrderId, buyer, number);
    }

    /** 결제된 묶음의 환불 하나. 환불 번호를 돌려준다 */
    private String newRefund(SellerOrderRow order) {
        String number = "R-" + OrderFixture.sellerOrderNumber().substring(2);
        jdbc.sql("""
                        insert into payment (order_id, method, amount, status, approval_number)
                        values (:orderId, 'card', 13000, 'approved', :approval)
                        """)
                .param("orderId", order.orderId()).param("approval", "AP-" + number).update();
        jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, amount, shipping_fee_refund, reason_code,
                                            requested_by_type, requested_by_user_id, status, decided_at, due_at,
                                            approved_by_type, approved_by_user_id, gateway_refund_number)
                        values (:number, :sellerOrderId, 4000, 0, 'withdrawal', 'customer', :buyer, 'approved', now(),
                                now() + interval '3 days', 'admin', :approver, :gateway)
                        """)
                .param("number", number).param("sellerOrderId", order.sellerOrderId())
                // 요청자와 승인자가 같으면 refund_self_approval_check 가 막는다.
                .param("buyer", order.buyerId()).param("approver", owner).param("gateway", "GW-" + number).update();
        return number;
    }

    /** 그 셀러의 정산서 하나. 정산서 번호를 돌려준다 */
    private String newSettlement(long seller) {
        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (date '2019-01-01', date '2019-01-31', date '2019-02-10')
                        on conflict do nothing
                        returning settlement_cycle_id
                        """)
                .query(Long.class).optional()
                .orElseGet(() -> jdbc.sql("""
                                select settlement_cycle_id from settlement_cycle
                                 where period_start = date '2019-01-01' and period_end = date '2019-01-31'
                                """).query(Long.class).single());
        String number = "T-" + OrderFixture.sellerOrderNumber().substring(2);
        jdbc.sql("""
                        insert into settlement (settlement_number, settlement_cycle_id, seller_id, payout_amount)
                        values (:number, :cycleId, :sellerId, 10000)
                        """)
                .param("number", number).param("cycleId", cycleId).param("sellerId", seller).update();
        return number;
    }

    private List<String> deliveredSubjects() {
        return jdbc.sql("""
                        select e.subject from webhook_delivery d
                          join outbox_event e on e.outbox_event_id = d.outbox_event_id
                        """)
                .query(String.class).list();
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
