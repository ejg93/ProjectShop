package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;

/**
 * 거래 축의 보존 기간이 실제로 지켜지는가.
 *
 * <p>여기서 보는 것은 <b>두 기간이 다르게 흐른다</b>는 것이다. 배송지는 6개월에 사라지고
 * 주문은 5년을 채운다 — R6(보존)과 R9(파기)가 부딪히는 자리를 테이블을 갈라서 푼 결과라,
 * 둘이 같이 사라지면 그 설계가 무의미해진다.
 */
@DisplayName("거래기록 파기")
class TransactionPurgeServiceTest extends PostgresTestBase {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 8, 9, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    private TransactionPurgeService purgeService;

    @Autowired
    private JdbcClient jdbc;

    private long userId;
    private long sellerId;
    private long skuId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("purge-buyer@test.local", "구매자");
        sellerId = fixture.insertSeller("purge-seller", "파기셀러");
        skuId = insertSku();
    }

    @Nested
    @DisplayName("배송지는")
    class ShippingAddress {

        @Test
        @DisplayName("거래가 끝나고 6개월이 지나면 사라진다")
        void isErasedAfterSixMonths() {
            long orderId = orderClosedAt(NOW.minusMonths(7));

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("제6조 제2항이 보존을 권리로만 주므로 쓸 일이 끝나면 버린다")
                    .isFalse();
        }

        @Test
        @DisplayName("아직 6개월이 안 됐으면 남는다")
        void survivesWithinSixMonths() {
            long orderId = orderClosedAt(NOW.minusMonths(5));

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("반품·교환이 늦게 오는 것까지 덮는 기간이다")
                    .isTrue();
        }

        @Test
        @DisplayName("지워져도 주문은 남는다")
        void leavesTheOrderBehind() {
            long orderId = orderClosedAt(NOW.minusMonths(7));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId))
                    .as("거래 사실은 5년을 채운다. 테이블을 가른 이유가 이것이다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("주문은")
    class Orders {

        @Test
        @DisplayName("5년이 지나면 사라진다")
        void isErasedAfterFiveYears() {
            long orderId = orderClosedAt(NOW.minusYears(6));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId)).isFalse();
        }

        @Test
        @DisplayName("항목과 셀러 주문도 같이 사라진다")
        void takesItsChildrenWithIt() {
            orderClosedAt(NOW.minusYears(6));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from order_item"))
                    .as("참조가 restrict 라 자식부터 지운다. cascade 를 파기 수단으로 안 쓴다(`D23`)")
                    .isZero();
            assertThat(countOf("select count(*) from seller_order")).isZero();
        }

        @Test
        @DisplayName("상태 이력도 같이 사라진다")
        void takesItsHistoryWithIt() {
            long orderId = orderClosedAt(NOW.minusYears(6));
            insertHistory(orderId);

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from order_status_history"))
                    .as("이력이 주문을 restrict 로 잡는다. 안 지우면 5년 파기가 통째로 실패한다(`V18`)")
                    .isZero();
            assertThat(orderExists(orderId)).isFalse();
        }

        @Test
        @DisplayName("아직 5년이 안 됐으면 남는다")
        void survivesWithinFiveYears() {
            long orderId = orderClosedAt(NOW.minusYears(4));

            purgeService.purge(NOW);

            assertThat(orderExists(orderId))
                    .as("계약·청약철회 기록은 5년이다(시행령 제6조)")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("안 끝난 거래는")
    class OpenOrders {

        @Test
        @DisplayName("아무리 오래돼도 대상이 아니다")
        void areNeverPurged() {
            long orderId = insertOrder("20260809-7QX4P2");
            insertSellerOrder(orderId, null);
            insertShipping(orderId);

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("`closed_at` 이 비어 있으면 기산점이 없다. 청크 11 이 채운다")
                    .isTrue();
        }

        @Test
        @DisplayName("셀러 하나만 안 끝나도 대상이 아니다")
        void needEverySellerOrderClosed() {
            long orderId = insertOrder("20260809-7QX4P3");
            insertSellerOrder(orderId, NOW.minusYears(6));
            insertSellerOrder(orderId, null, secondSeller());
            insertShipping(orderId);

            purgeService.purge(NOW);

            assertThat(shippingExists(orderId))
                    .as("하나가 반품 중인데 배송지를 지우면 그 반품을 처리할 수 없다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("감사 로그는")
    class AuditLogs {

        @Test
        @DisplayName("3년이 지나면 사라진다")
        void isErasedAfterThreeYears() {
            insertAuditLog(NOW.minusYears(4));
            insertAuditLog(NOW.minusYears(2));

            purgeService.purge(NOW);

            assertThat(countOf("select count(*) from audit_log"))
                    .as("분쟁이 늦게 터져도 닿는 기간이다(`D13`)")
                    .isEqualTo(1);
        }
    }

    /** 끝난 주문 하나. 배송지와 항목까지 갖춘다 */
    private long orderClosedAt(OffsetDateTime closedAt) {
        long orderId = insertOrder("20260809-7QX4P" + (char) ('4' + counter++));
        insertSellerOrder(orderId, closedAt);
        insertShipping(orderId);
        return orderId;
    }

    private int counter;

    private long insertOrder(String number) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 0, 10000)
                        returning order_id
                        """)
                .param("number", number)
                .param("userId", userId)
                .query(Long.class)
                .single();

        // `V31` 이 서면 없는 주문을 막는다. 여기는 서면이 관심사가 아니라 껍데기만 채운다.
        OrderFixture.attachContractDocuments(jdbc, orderId);
        return orderId;
    }

    private void insertSellerOrder(long orderId, OffsetDateTime closedAt) {
        insertSellerOrder(orderId, closedAt, sellerId);
    }

    private void insertSellerOrder(long orderId, OffsetDateTime closedAt, long seller) {
        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee, closed_at)
                        values (:number, :orderId, :sellerId, 0, :closedAt)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", seller)
                .param("closedAt", closedAt)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '파기 상품', 10000, 1, 10000, 1000, 1000)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .update();
    }

    /** 두 층 모두 이력을 남긴다. 파기는 둘 다 걷어야 한다 */
    private void insertHistory(long orderId) {
        jdbc.sql("""
                        insert into order_status_history (order_id, from_status, to_status, actor_type)
                        values (:orderId, 'payment_pending', 'paid', 'system')
                        """)
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                        insert into order_status_history (seller_order_id, from_status, to_status, actor_type)
                        select seller_order_id, 'shipping', 'delivered', 'system'
                          from seller_order where order_id = :orderId
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void insertShipping(long orderId) {
        jdbc.sql("""
                        insert into order_shipping (order_id, receiver_name, receiver_phone,
                                                    postal_code, address1)
                        values (:orderId, '홍길동', '010-0000-0000', '06134', '서울시 강남구')
                        """)
                .param("orderId", orderId)
                .update();
    }

    private void insertAuditLog(OffsetDateTime createdAt) {
        jdbc.sql("""
                        insert into audit_log (event_type, actor_user_id, detail, created_at)
                        values ('test.event', :userId, '{}'::jsonb, :createdAt)
                        """)
                .param("userId", userId)
                .param("createdAt", createdAt)
                .update();
    }

    private long secondSeller() {
        return new AuthFixture(jdbc).insertSeller("purge-seller-2", "둘째셀러");
    }

    private long insertSku() {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '파기 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat, stock_count)
                        values (:productId, 10000, 10)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }

    private boolean shippingExists(long orderId) {
        return exists("select 1 from order_shipping where order_id = " + orderId);
    }

    private boolean orderExists(long orderId) {
        return exists("select 1 from shop_order where order_id = " + orderId);
    }

    private boolean exists(String sql) {
        return Boolean.TRUE.equals(
                jdbc.sql("select exists(" + sql + ")").query(Boolean.class).single());
    }

    private int countOf(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
