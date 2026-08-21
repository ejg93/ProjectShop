package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Payment;
import com.projectshop.shop.order.OrderTransitions.Shipment;

/**
 * 시각이 되면 사람 없이 옮겨지는가(`D7`).
 *
 * <p><b>시각을 주입해서 본다.</b> 실제 시계를 기다리면 30분짜리 테스트가 되고,
 * 행의 시각을 과거로 밀면 `set_updated_at` 트리거와 싸우게 된다(`stack.md`).
 *
 * <p>배치가 무엇을 하는지는 안 본다 — 재고 복구와 이력은 {@code OrderStatusServiceTest} 가 본다.
 * 여기서 보는 것은 <b>누구를 고르는가</b>와 <b>두 번 돌아도 같은가</b>다.
 */
@DisplayName("주문 상태 배치")
class OrderStatusBatchTest extends PostgresTestBase {

    private static final int STOCK = 10;
    private static final int ORDERED = 2;

    /** 결제 대기 30분을 넘긴 시점 */
    private static final Duration PAST_DEADLINE = Duration.ofMinutes(31);

    @Autowired
    private OrderStatusBatch batch;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long skuId;
    private long orderId;
    private long sellerOrderId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("batch-buyer@test.local", "구매자");

        long sellerId = fixture.insertSeller("s-batch", "배치셀러");
        fixture.verifySeller(sellerId);

        skuId = insertSku(sellerId);
        orderId = placeOrder();
        sellerOrderId = sellerOrderOf(orderId);
    }

    @Nested
    @DisplayName("결제 만료는")
    class PaymentExpiry {

        @Test
        @DisplayName("30분이 지난 주문을 잡는다")
        void catchesOverdueOrders() {
            assertThat(batch.expireUnpaidOrders(OffsetDateTime.now().plus(PAST_DEADLINE))).isEqualTo(1);

            assertThat(paymentStatus()).isEqualTo(Payment.PAYMENT_EXPIRED.code());
        }

        @Test
        @DisplayName("아직 30분이 안 된 주문은 안 건드린다")
        void leavesFreshOrders() {
            assertThat(batch.expireUnpaidOrders(OffsetDateTime.now())).isZero();

            assertThat(paymentStatus()).isEqualTo(Payment.PAYMENT_PENDING.code());
        }

        @Test
        @DisplayName("결제된 주문은 대상이 아니다")
        void skipsPaidOrders() {
            statuses.movePayment(orderId, Payment.PAID, Actor.system("결제 승인"));

            assertThat(batch.expireUnpaidOrders(OffsetDateTime.now().plus(PAST_DEADLINE))).isZero();
        }

        @Test
        @DisplayName("잡은 주문의 재고가 돌아온다")
        void restoresStock() {
            batch.expireUnpaidOrders(OffsetDateTime.now().plus(PAST_DEADLINE));

            assertThat(stock())
                    .as("배치가 상태만 바꾸고 끝내면 안 팔린 물건이 계속 잡혀 있다")
                    .isEqualTo(STOCK);
        }

        @Test
        @DisplayName("두 번 돌아도 한 번 처리된다")
        void isSafeToRunTwice() {
            OffsetDateTime baseline = OffsetDateTime.now().plus(PAST_DEADLINE);
            batch.expireUnpaidOrders(baseline);

            assertThat(batch.expireUnpaidOrders(baseline))
                    .as("고르는 조건이 곧 처리 조건이라 옮기고 나면 대상에서 빠진다")
                    .isZero();
            assertThat(stock()).isEqualTo(STOCK);
        }
    }

    @Nested
    @DisplayName("자동 구매확정은")
    class AutoConfirm {

        @Test
        @DisplayName("예정일이 지난 것을 확정한다")
        void confirmsAfterTheDeadline() {
            deliver();

            assertThat(batch.confirmDeliveredOrders(autoConfirmAt().plusSeconds(1))).isEqualTo(1);
            assertThat(shipmentStatus()).isEqualTo(Shipment.CONFIRMED.code());
        }

        @Test
        @DisplayName("회차가 이력에 남는다")
        void recordsTheRun() {
            java.time.LocalDate baselineDate = java.time.LocalDate.of(2026, 8, 21);

            batch.confirmDeliveredOrders(baselineDate);

            // 이 이력을 처음 쓰는 것이 정산 마감(청크 19)의 체인 판정이다(`36a`).
            assertThat(jdbc.sql("""
                            select status from batch_run
                             where batch_name = 'auto_confirm' and baseline_date = :baselineDate
                            """)
                    .param("baselineDate", baselineDate)
                    .query(String.class)
                    .single())
                    .isEqualTo("succeeded");
        }

        @Test
        @DisplayName("예정일 전에는 안 건드린다")
        void waitsUntilTheDeadline() {
            deliver();

            assertThat(batch.confirmDeliveredOrders(autoConfirmAt().minusSeconds(1))).isZero();
            assertThat(shipmentStatus()).isEqualTo(Shipment.DELIVERED.code());
        }

        @Test
        @DisplayName("반품이 접수된 것은 빠진다")
        void skipsReturnRequests() {
            deliver();
            OffsetDateTime afterDeadline = autoConfirmAt().plusSeconds(1);
            statuses.moveShipment(sellerOrderId, Shipment.RETURN_REQUESTED,
                    Actor.person("customer", userId));

            assertThat(batch.confirmDeliveredOrders(afterDeadline))
                    .as("확정되면 정산 대상이 된다. 반품 중인 것이 들어가면 되돌릴 것이 늘어난다(`D7`)")
                    .isZero();
        }

        @Test
        @DisplayName("배송 중인 것은 대상이 아니다")
        void skipsUndelivered() {
            statuses.moveShipment(sellerOrderId, Shipment.SHIPPING, Actor.person("seller", userId));

            assertThat(batch.confirmDeliveredOrders(OffsetDateTime.now().plusYears(1))).isZero();
        }

        @Test
        @DisplayName("확정이 거래 종료 시각을 남긴다")
        void closesTheTransaction() {
            deliver();
            batch.confirmDeliveredOrders(autoConfirmAt().plusSeconds(1));

            assertThat(timeOf("closed_at"))
                    .as("배치로 끝난 주문도 파기 기산점이 있어야 한다(`D13`)")
                    .isNotNull();
        }
    }

    private void deliver() {
        statuses.moveShipment(sellerOrderId, Shipment.SHIPPING, Actor.person("seller", userId));
        statuses.moveShipment(sellerOrderId, Shipment.DELIVERED, Actor.person("seller", userId));
    }

    private OffsetDateTime autoConfirmAt() {
        return timeOf("auto_confirm_at");
    }

    private long placeOrder() {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", userId)
                .query(Long.class)
                .single();

        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, :quantity)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .param("quantity", ORDERED)
                .query(Long.class)
                .single();

        return orderService.create(userId, new OrderService.Command(List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '배치 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, :stock from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("stock", STOCK)
                .query(Long.class)
                .single();
    }

    private long sellerOrderOf(long order) {
        return jdbc.sql("select seller_order_id from seller_order where order_id = :orderId")
                .param("orderId", order)
                .query(Long.class)
                .single();
    }

    /** 시각 컬럼은 RowMapper 로 읽는다(`stack.md`) */
    private OffsetDateTime timeOf(String column) {
        return jdbc.sql("select %s from seller_order where seller_order_id = :id".formatted(column))
                .param("id", sellerOrderId)
                .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class))
                .single();
    }

    private String paymentStatus() {
        return jdbc.sql("select status from shop_order where order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .single();
    }

    private String shipmentStatus() {
        return jdbc.sql("select status from seller_order where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(String.class)
                .single();
    }

    private int stock() {
        return jdbc.sql("select on_hand from sku_stock where sku_id = :id")
                .param("id", skuId)
                .query(Integer.class)
                .single();
    }
}
