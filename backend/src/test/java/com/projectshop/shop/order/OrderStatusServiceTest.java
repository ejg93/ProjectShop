package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderTransitions.Payment;
import com.projectshop.shop.order.OrderTransitions.Shipment;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 상태 전이가 곁가지까지 같이 하는가(`D7`).
 *
 * <p>전이 자체보다 <b>같이 일어나야 하는 것</b>이 이 청크의 본체다. 상태만 바뀌고 재고가 안 돌아오면
 * 팔 수 있는 물건이 사라지고, 기산점이 안 채워지면 5년이 지나도 파기 대상으로 안 잡힌다.
 * 셋 다 빠뜨려도 화면에는 정상으로 보인다.
 */
@DisplayName("주문 상태 전이")
class OrderStatusServiceTest extends PostgresTestBase {

    private static final int STOCK = 10;
    private static final int ORDERED = 3;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private OrderService orderService;

    @Autowired
    private BusinessCalendar calendar;

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
        userId = fixture.insertUser("status-buyer@test.local", "구매자");

        long sellerId = fixture.insertSeller("s-status", "상태셀러");
        fixture.verifySeller(sellerId);

        skuId = insertSku(sellerId);
        orderId = placeOrder();
        sellerOrderId = sellerOrderOf(orderId);
    }

    @Nested
    @DisplayName("전이표에 없는 이동은")
    class NotAllowed {

        @Test
        @DisplayName("막힌다")
        void isRejected() {
            assertThatThrownBy(() -> statuses.moveShipment(sellerOrderId, Shipment.DELIVERED,
                    Actor.person("seller", userId)))
                    .as("준비 중인 것이 배송을 건너뛰고 완료가 되면 기산점이 실제와 달라진다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_TRANSITION_NOT_ALLOWED));
        }

        @Test
        @DisplayName("배송완료 뒤로는 되돌아갈 수 없다")
        void cannotGoBackAfterDelivery() {
            deliver();

            assertThatThrownBy(() -> statuses.moveShipment(sellerOrderId, Shipment.SHIPPING,
                    Actor.person("seller", userId)))
                    .as("되돌릴 수 있으면 셀러가 청약철회 기산점을 옮긴다(`D7`)")
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("관리자는 사유를 적으면 표 밖으로도 옮긴다")
        void adminMayForceWithReason() {
            assertThatCode(() -> statuses.moveShipment(sellerOrderId, Shipment.RETURNED,
                    Actor.admin(userId, "CS-1234, 고객이 수령 후 분실 신고")))
                    .as("CS 처리에 필요하다. 대신 사유가 이력에 남는다(`D7`)")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("관리자여도 사유가 없으면 막힌다")
        void adminWithoutReasonIsRejected() {
            assertThatThrownBy(() -> statuses.moveShipment(sellerOrderId, Shipment.RETURNED,
                    Actor.person("admin", userId)))
                    .as("사유가 없으면 남는 것이 '관리자가 바꿨다' 뿐이다")
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("배송완료는")
    class Delivery {

        @Test
        @DisplayName("청약철회 만료일과 자동확정 예정일을 박제한다")
        void freezesBothDeadlines() {
            deliver();

            assertThat(timeOf("delivered_at")).isNotNull();
            assertThat(timeOf("withdrawal_expire_at"))
                    .as("나중에 계산하면 임시공휴일이 추가됐을 때 지난 기한까지 흔들린다(`D10`)")
                    .isNotNull();
            assertThat(timeOf("auto_confirm_at")).isNotNull();
        }

        @Test
        @DisplayName("자동확정이 청약철회보다 뒤다")
        void confirmsAfterWithdrawalCloses() {
            deliver();

            assertThat(timeOf("auto_confirm_at"))
                    .as("짧으면 아직 반품할 수 있는 주문이 확정돼서 정산에 들어간다(`D10`)")
                    .isAfter(timeOf("withdrawal_expire_at"));
        }

        /**
         * 오늘 날짜에 안 흔들리게 고정한다. 위 테스트들은 실행하는 날에 따라
         * 두 기한이 벌어져서, 붙는 날에만 나는 결함을 못 잡는다.
         */
        @Test
        @DisplayName("말일 보정이 두 기한을 붙여도 자동확정이 뒤로 밀린다")
        void keepsAutoConfirmAfterWithdrawalWhenShifted() {
            // 2026-08-10 배송완료 → 7일째가 8/17 인데 광복절 대체공휴일이라 8/18 로 밀린다.
            // 8일째도 8/18 이라 보정 없이 계산하면 두 기한이 같은 날이 된다.
            LocalDate deliveredOn = LocalDate.of(2026, 8, 10);
            LocalDate withdrawalLastDay = calendar.nextBusinessDay(deliveredOn.plusDays(7));

            assertThat(withdrawalLastDay).isEqualTo(LocalDate.of(2026, 8, 18));
            assertThat(statuses.autoConfirmLastDay(deliveredOn, withdrawalLastDay))
                    .as("같은 날이면 청약철회가 살아 있는 자정에 확정 배치가 돈다(`D10`)")
                    .isEqualTo(LocalDate.of(2026, 8, 19));
        }

        /**
         * <b>저장하고 다시 읽어도 날짜가 안 밀리는 것</b>을 고정한다.
         *
         * <p>「말일이 쉬는 날이면 다음 영업일」 자체는 위 고정 날짜 테스트가 본다.
         * 여기가 보는 것은 그 계산이 <b>DB 를 왕복해도 살아남나</b>다 — 그 구간에서 하루가 밀렸다.
         *
         * <p>{@code LocalTime.MAX} 를 넣으면 Postgres 가 마이크로초 아래를 올려서 다음날
         * {@code 00:00} 이 되고, 되돌린 날짜가 말일+1 이 된다. <b>말일이 금요일인 날에만
         * 깨져서</b> 여러 날 잠복했다(`D10`·`stack.md`).
         */
        @Test
        @DisplayName("박제한 기한이 저장·조회를 왕복해도 같은 날이다")
        void keepsDeadlineDateAcrossRoundTrip() {
            LocalDate deliveredOn = LocalDate.now(BusinessCalendar.ZONE);
            LocalDate expected = calendar.nextBusinessDay(deliveredOn.plusDays(7));

            deliver();

            LocalDate stored = timeOf("withdrawal_expire_at")
                    .atZoneSameInstant(BusinessCalendar.ZONE).toLocalDate();

            assertThat(stored)
                    .as("하루 밀리면 청약철회 기간이 법정 7일보다 길어진다(`D2` R3)")
                    .isEqualTo(expected);
            assertThat(calendar.isBusinessDay(stored))
                    .as("민법이 말일이 토·일·공휴일이면 다음날 만료된다고 정한다(`D10`)")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("거래가 끝나면")
    class Closing {

        @Test
        @DisplayName("구매확정이 기산점을 채운다")
        void confirmSetsClosedAt() {
            deliver();
            statuses.moveShipment(sellerOrderId, Shipment.CONFIRMED, Actor.person("customer", userId));

            assertThat(timeOf("closed_at"))
                    .as("보존 기간이 여기서부터 흐른다. 없으면 파기 대상으로 안 잡힌다(`D13`)")
                    .isNotNull();
        }

        @Test
        @DisplayName("취소도 기산점을 채운다")
        void cancelSetsClosedAt() {
            statuses.moveShipment(sellerOrderId, Shipment.CANCELLED, Actor.person("customer", userId));

            assertThat(timeOf("closed_at")).isNotNull();
        }
    }

    @Nested
    @DisplayName("배송 전 취소는")
    class Cancellation {

        @Test
        @DisplayName("재고를 되돌린다")
        void restoresStock() {
            assertThat(stock()).isEqualTo(STOCK - ORDERED);

            statuses.moveShipment(sellerOrderId, Shipment.CANCELLED, Actor.person("customer", userId));

            assertThat(stock())
                    .as("안 되돌리면 취소될 때마다 팔 수 있는 수량이 줄어서 재고가 있는데 품절로 보인다")
                    .isEqualTo(STOCK);
        }

        @Test
        @DisplayName("두 번 해도 재고가 두 번 늘지 않는다")
        void doesNotRestoreTwice() {
            statuses.moveShipment(sellerOrderId, Shipment.CANCELLED, Actor.person("customer", userId));

            assertThatThrownBy(() -> statuses.moveShipment(sellerOrderId, Shipment.CANCELLED,
                    Actor.person("customer", userId)))
                    .isInstanceOf(ShopException.class);

            assertThat(stock()).isEqualTo(STOCK);
        }
    }

    @Nested
    @DisplayName("결제가 끝내 안 되면")
    class PaymentFailure {

        @Test
        @DisplayName("셀러 주문도 같이 닫힌다")
        void closesSellerOrders() {
            statuses.movePayment(orderId, Payment.PAYMENT_EXPIRED, Actor.system("30분 초과"));

            assertThat(shipmentStatus())
                    .as("안 닫으면 영원히 안 끝나는 셀러 주문이 남고 파기 대상으로도 안 잡힌다")
                    .isEqualTo(Shipment.CANCELLED.code());
            assertThat(timeOf("closed_at")).isNotNull();
        }

        @Test
        @DisplayName("재고가 돌아온다")
        void restoresStock() {
            statuses.movePayment(orderId, Payment.PAYMENT_FAILED, Actor.system("카드사 거절"));

            assertThat(stock()).isEqualTo(STOCK);
        }

        @Test
        @DisplayName("돌아온 것도 이력에 남는다")
        void leavesARestoreMovement() {
            statuses.movePayment(orderId, Payment.PAYMENT_FAILED, Actor.system("카드사 거절"));

            // 차감을 지우고 값만 되돌리면 그 사이에 재고가 잡혀 있었다는 사실이 사라진다(`53`).
            assertThat(jdbc.sql("""
                            select count(*) from sku_stock_movement
                             where reason = 'order_cancelled' and order_id = :orderId
                            """)
                    .param("orderId", orderId)
                    .query(Integer.class)
                    .single())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("모든 전이는")
    class History {

        @Test
        @DisplayName("이력에 한 줄씩 쌓인다")
        void leavesOneRowEach() {
            statuses.movePayment(orderId, Payment.PAID, Actor.system("결제 승인"));
            statuses.moveShipment(sellerOrderId, Shipment.SHIPPING, Actor.person("seller", userId));
            deliverFromShipping();

            assertThat(historyCount())
                    .as("현재 상태만 있으면 '언제 배송됐나' 에 답할 수 없다(`ADR 0007`)")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("결제 만료가 부른 취소도 남는다")
        void recordsCascadedCancellation() {
            statuses.movePayment(orderId, Payment.PAYMENT_EXPIRED, Actor.system("30분 초과"));

            assertThat(historyCount())
                    .as("주문 쪽 만료 한 줄과 셀러 주문 쪽 취소 한 줄이다")
                    .isEqualTo(2);
        }
    }

    private void deliver() {
        statuses.moveShipment(sellerOrderId, Shipment.SHIPPING, Actor.person("seller", userId));
        deliverFromShipping();
    }

    private void deliverFromShipping() {
        statuses.moveShipment(sellerOrderId, Shipment.DELIVERED, Actor.person("seller", userId));
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

        return orderService.create(userId, new OrderService.Command(java.util.List.of(cartItemId),
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '상태 상품', 'on_sale')
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

    /** 시각 컬럼은 RowMapper 로 읽는다. `singleRow()` 는 `timestamptz` 를 Timestamp 로 준다(`stack.md`) */
    private OffsetDateTime timeOf(String column) {
        return jdbc.sql("select %s from seller_order where seller_order_id = :id".formatted(column))
                .param("id", sellerOrderId)
                .query((rs, rowNum) -> rs.getObject(1, OffsetDateTime.class))
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

    private int historyCount() {
        return jdbc.sql("""
                        select count(*) from order_status_history
                         where order_id = :orderId or seller_order_id = :sellerOrderId
                        """)
                .param("orderId", orderId)
                .param("sellerOrderId", sellerOrderId)
                .query(Integer.class)
                .single();
    }
}
