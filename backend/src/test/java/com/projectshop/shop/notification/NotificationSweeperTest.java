package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 법이 요구하는 통지 넷이 실제로 나가는가(청크 56, `D2` R20).
 *
 * <p><b>이 요건은 코드를 훑어서는 안 보인다.</b> 네 시점의 화면은 다 있었고 컬럼도 다 있었는데
 * <b>보내는 자리가 없었다</b> — 어느 파일을 열어도 빠진 것이 안 드러난다(`D23` 「지켜지는지는
 * 위에서 내려가며 확인한다」). 그래서 여기서는 요건표에서 내려와 <b>네 시점을 이름으로 하나씩</b> 밟는다.
 */
@DisplayName("거래 통지 스위퍼")
class NotificationSweeperTest extends PostgresTestBase {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 22, 12, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private NotificationSweeper sweeper;

    @Autowired
    private JdbcClient jdbc;

    private long userId;
    private long approverId;
    private long sellerId;
    private long orderId;
    private long sellerOrderId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("sweep-buyer@test.local", "받는이");
        approverId = fixture.insertUser("sweep-admin@test.local", "승인자");
        sellerId = fixture.insertSeller("sweep-seller", "통지셀러");
        orderId = insertOrder();
        sellerOrderId = insertSellerOrder();

        // 스위퍼의 바닥은 「통지 기능이 선 시각」이다(`56a`). `V44` 가 시드한 시각은 지금이라
        // 이 시험의 가짜 시간축(NOW)보다 뒤에 있다 2014 판을 뒤로 밀어 두 축을 맞춘다.
        jdbc.sql("update notification_template set created_at = :began")
                .param("began", NOW.minusYears(1))
                .update();
    }

    @Nested
    @DisplayName("법이 정한 네 시점")
    class FourTimings {

        @Test
        @DisplayName("청약 접수 — 제14조제1항")
        void sendsOrderPlaced() {
            sweeper.sweepAll(NOW);

            assertThat(eventsFor(orderId)).contains("order_placed");
        }

        @Test
        @DisplayName("대금 지급 — 제8조제3항")
        void sendsPaymentCompleted() {
            insertApprovedPayment();

            sweeper.sweepAll(NOW);

            // 「알리고, 언제든지 열람할 수 있게」로 둘을 나란히 적었다. 화면만으로는 안 채워진다.
            assertThat(eventsFor(orderId)).contains("payment_completed");
        }

        @Test
        @DisplayName("공급 곤란 — 제15조제2항")
        void sendsSupplyDelayed() {
            makeShipOverdue();

            sweeper.sweepAll(NOW);

            assertThat(sellerOrderEvents()).contains("supply_delayed");
        }

        @Test
        @DisplayName("환급 — 제18조제3항 단서")
        void sendsRefundCompleted() {
            long refundId = insertApprovedRefund();

            sweeper.sweepAll(NOW);

            assertThat(refundEvents(refundId)).contains("refund_completed");
        }
    }

    @Nested
    @DisplayName("동의 처리 결과")
    class ConsentResult {

        @Test
        @DisplayName("광고 항목에 의사를 표시하면 결과를 알린다")
        void notifiesConsentResult() {
            long consentId = consent("marketing_email", true);

            sweeper.sweepAll(NOW);

            // 시행령 제62조의2 — 의사를 표시한 날부터 14일 이내에 처리 결과를 알린다.
            assertThat(consentEvents(consentId)).contains("consent_result");
        }

        @Test
        @DisplayName("필수 약관 동의에는 안 나간다")
        void ignoresNonMarketingConsent() {
            long consentId = consent("terms_of_service", true);

            sweeper.sweepAll(NOW);

            // 제50조는 영리목적 광고성 정보에 걸린 의무다. 전부 보내면 가입할 때마다 여러 통 나간다.
            assertThat(consentEvents(consentId)).isEmpty();
        }

        @Test
        @DisplayName("철회에도 나가고 본문이 갈린다")
        void notifiesRevocation() {
            long consentId = consent("marketing_email", false);

            sweeper.sweepAll(NOW);

            assertThat(consentEvents(consentId)).contains("consent_result");
            assertThat(bodyOf("consent_result")).contains("철회");
        }

        @Test
        @DisplayName("같은 의사표시에 두 번 안 나간다")
        void notifiesOncePerAct() {
            long consentId = consent("marketing_email", true);

            sweeper.sweepAll(NOW);
            sweeper.sweepAll(NOW);

            assertThat(consentEvents(consentId)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("본문에는")
    class Body {

        @Test
        @DisplayName("환급 금액이 들어간다")
        void putsRefundAmountInBody() {
            insertApprovedRefund();

            sweeper.sweepAll(NOW);

            // 제18조제3항 단서가 「환급에 필요한 조치를 하였음」을 알리라고 해서
            // 얼마를 돌려주는지가 본문에 있어야 한다(`D18` 「템플릿」).
            assertThat(bodyOf("refund_completed")).contains("4000");
        }

        @Test
        @DisplayName("발송 기한이 한국 시각으로 들어간다")
        void putsShipDueAtInLocalTime() {
            makeShipOverdue();

            sweeper.sweepAll(NOW);

            // 저장은 UTC 지만 사용자가 읽는 것은 KST 다(`D10`).
            assertThat(bodyOf("supply_delayed")).contains("2026년 8월 20일");
        }
    }

    @Nested
    @DisplayName("두 번 돌아도")
    class Idempotent {

        @Test
        @DisplayName("같은 통지가 두 번 안 나간다")
        void sendsEachEventOnce() {
            insertApprovedPayment();

            sweeper.sweepAll(NOW);
            sweeper.sweepAll(NOW);

            assertThat(eventsFor(orderId))
                    .describedAs("막는 것은 스위퍼 조건이 아니라 `54a` 의 부분 유니크다")
                    .containsExactlyInAnyOrder("order_placed", "payment_completed");
        }

        @Test
        @DisplayName("기능이 서기 전 주문에는 안 보낸다")
        void ignoresRowsFromBeforeNotificationsExisted() {
            jdbc.sql("update shop_order set created_at = :old where order_id = :id")
                    .param("old", NOW.minusYears(2))
                    .param("id", orderId)
                    .update();

            sweeper.sweepAll(NOW);

            // 보내는 자리가 없던 동안 쌓인 주문에 지금 와서 보내면 통지가 아니라 무더기 발송이다.
            assertThat(eventsFor(orderId)).isEmpty();
        }

        @Test
        @DisplayName("배치가 오래 멈춰 있었어도 다 잡는다")
        void catchesUpAfterALongOutage() {
            // 「이레 안에 생긴 것」으로 자르면 여기가 빈다 2014 법정 통지가 영영 안 나가고
            // 아무 데도 안 남는 구간이었다(`56a`).
            sweeper.sweepAll(NOW.plusDays(90));

            assertThat(eventsFor(orderId)).contains("order_placed");
        }
    }

    private long consent(String code, boolean granted) {
        return jdbc.sql("""
                        insert into user_consent (user_id, consent_item_id, granted, source, acted_at)
                        select :id, consent_item_id, :granted, 'signup', :actedAt from consent_item
                         where code = :code order by version desc limit 1
                        returning user_consent_id
                        """)
                .param("id", userId)
                .param("code", code)
                .param("granted", granted)
                .param("actedAt", NOW.minusHours(1))
                .query(Long.class)
                .single();
    }

    private List<String> consentEvents(long consentId) {
        return jdbc.sql("select event_type from notification where user_consent_id = :id")
                .param("id", consentId)
                .query(String.class)
                .list();
    }

    private List<String> eventsFor(long order) {
        return jdbc.sql("select event_type from notification where order_id = :id")
                .param("id", order)
                .query(String.class)
                .list();
    }

    private List<String> sellerOrderEvents() {
        return jdbc.sql("select event_type from notification where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(String.class)
                .list();
    }

    private List<String> refundEvents(long refundId) {
        return jdbc.sql("select event_type from notification where refund_id = :id")
                .param("id", refundId)
                .query(String.class)
                .list();
    }

    private String bodyOf(String eventType) {
        return jdbc.sql("""
                        select b.body from notification_body b
                          join notification n on n.notification_id = b.notification_id
                         where n.event_type = :event and n.user_id = :userId
                        """)
                .param("event", eventType)
                .param("userId", userId)
                .query(String.class)
                .single();
    }

    private void insertApprovedPayment() {
        jdbc.sql("""
                        insert into payment (order_id, method, amount, status, approval_number)
                        values (:orderId, 'card', 10000, 'approved', 'AP-1')
                        """)
                .param("orderId", orderId)
                .update();
    }

    private long insertApprovedRefund() {
        return jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, amount,
                                            shipping_fee_refund, reason_code, requested_by_type,
                                            requested_by_user_id, status, decided_at, due_at,
                                            approved_by_user_id, gateway_refund_number)
                        values (:number, :sellerOrderId, 4000, 0, 'withdrawal', 'customer',
                                :requester, 'approved', :now, :due, :approver, 'GW-1')
                        returning refund_id
                        """)
                .param("number", "R-" + OrderFixture.sellerOrderNumber().substring(2))
                .param("sellerOrderId", sellerOrderId)
                .param("now", NOW.minusHours(1))
                .param("due", NOW.plusDays(3))
                .param("requester", userId)
                .param("approver", approverId)
                .query(Long.class)
                .single();
    }

    /**
     * 기한이 이틀 지났고 아직 안 보냈다. 판정은 {@code seller_order_visible.is_ship_overdue} 가 한다.
     *
     * <p>주문을 {@code paid} 로 올린다 — 그 뷰가 결제된 주문만 든다(`11c-2`).
     * <b>안 낸 주문의 발송 기한을 따질 이유가 없어서</b>고, 그래서 통지도 안 나가는 것이 맞다.
     */
    private void makeShipOverdue() {
        jdbc.sql("update shop_order set status = 'paid' where order_id = :id")
                .param("id", orderId)
                .update();
        jdbc.sql("""
                        update seller_order set ship_due_at = :due, shipped_at = null
                         where seller_order_id = :id
                        """)
                .param("due", NOW.minusDays(2))
                .param("id", sellerOrderId)
                .update();
    }

    private long insertOrder() {
        long id = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 0, 10000)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", userId)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, id);
        return id;
    }

    private long insertSellerOrder() {
        long id = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '통지 상품', 10000, 1, 10000, 1000, 1000)
                        """)
                .param("sellerOrderId", id)
                .param("skuId", insertSku())
                .update();

        return id;
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '통지 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat)
                        values (:productId, 10000)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
