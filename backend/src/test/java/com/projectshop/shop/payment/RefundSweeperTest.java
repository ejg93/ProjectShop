package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderService;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 사람이 요청을 안 내도 환불이 시작되나.
 *
 * <p><b>이 테스트가 지키는 것은 법 요건이다</b>(`D2` R5). 전자상거래법 제18조제2항이
 * 청약철회만으로 환급 의무를 발생시키므로 <b>별도 요청을 요구할 근거가 없다</b> —
 * 취소·반품을 했는데 아무도 요청을 안 내면 3영업일이 그냥 흐르고 지연배상금이 붙는다.
 *
 * <p><b>사유 판정도 여기서 본다.</b> 같은 「취소」인데 누가 끝냈느냐로 조문이 갈리고
 * (고객이면 제18조제2항 3호, 셀러면 제15조제2항) 그것이 기한을 정한다.
 * 스위퍼는 그 답을 {@code order_status_history.actor_type} 에서 읽는다.
 *
 * <p><b>상태와 이력을 SQL 로 민다.</b> 전이 자체는 `11-2` 가 보는 것이고 여기서 필요한 것은
 * 스위퍼가 읽는 값뿐이다 — {@code OrderTransitions} 가 {@code order} 패키지 밖으로 안 나온다.
 */
@DisplayName("환불 요청 스위퍼")
class RefundSweeperTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final int ORDERED = 2;
    private static final int STOCK = 10;
    private static final long SHIPPING_FEE = 3_000;

    private static final String GOOD_CARD = "4242-4242-4242-4242";

    /** 결제일과 취소일이 갈리게 미는 날수. 어느 기산점을 썼는지가 드러난다 */
    private static final int CLOSED_LATER_DAYS = 5;

    @Autowired
    private RefundSweeper sweeper;

    @Autowired
    private PaymentService payments;

    @Autowired
    private OrderService orderService;

    @Autowired
    private BusinessCalendar calendar;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long sellerId;
    private long skuId;
    private long orderId;
    private long sellerOrderId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("sweeper-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");

        sellerId = fixture.insertSeller("s-sweeper", "스위퍼셀러");
        fixture.verifySeller(sellerId);

        skuId = insertSku();
        orderId = placeAndPay();

        sellerOrderId = jdbc.sql("select seller_order_id from seller_order where order_id = :id")
                .param("id", orderId)
                .query(Long.class)
                .single();
    }

    @Nested
    @DisplayName("대상을 고를 때")
    class Selecting {

        @Test
        @DisplayName("닫혔는데 요청이 없는 묶음을 집는다")
        void picksClosedBundlesWithoutRefund() {
            close("cancelled", "customer");

            assertThat(sweeper.sweepClosedBundles()).isEqualTo(1);
            assertThat(refundCount())
                    .as("법이 청약철회만으로 환급 의무를 발생시킨다 — 사람이 안 내도 시작돼야 한다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("두 번 돌아도 하나만 만든다")
        void isIdempotentAcrossRuns() {
            close("cancelled", "customer");

            sweeper.sweepClosedBundles();
            assertThat(sweeper.sweepClosedBundles())
                    .as("고르는 조건이 곧 처리 조건이다. 만들고 나면 다음 회차의 대상에서 빠진다")
                    .isZero();
            assertThat(refundCount()).isEqualTo(1);
        }

        /**
         * {@code closed_at} 은 구매확정에서도 찬다({@code OrderStatusService.closeTransaction}).
         *
         * <p>확정을 집으면 <b>정상적으로 끝난 거래의 돈을 돌려주게 된다.</b>
         * 조건을 상태까지 보게 한 것이 그 자리고, 부분 인덱스의 조건이 이것과 같다(`V25`).
         */
        @Test
        @DisplayName("구매확정은 안 집는다")
        void skipsConfirmedBundles() {
            close("confirmed", "customer");

            assertThat(sweeper.sweepClosedBundles()).isZero();
            assertThat(refundCount()).isZero();
        }

        @Test
        @DisplayName("결제 승인이 없으면 안 집는다")
        void skipsUnpaidBundles() {
            close("cancelled", "system");
            jdbc.sql("delete from payment where order_id = :id").param("id", orderId).update();

            assertThat(sweeper.sweepClosedBundles())
                    .as("낸 적 없는 돈은 돌려줄 것이 없다. 결제 만료로 자동 취소된 묶음이 여기서 걸러진다")
                    .isZero();
        }

        @Test
        @DisplayName("사람이 이미 냈으면 안 만든다")
        void skipsWhenSomeoneAlreadyRequested() {
            close("cancelled", "customer");
            insertRefundBy("customer", buyerId);

            assertThat(sweeper.sweepClosedBundles()).isZero();
            assertThat(refundCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("반려만 있으면 다시 만든다")
        void createsAgainWhenOnlyRejectedRemains() {
            close("cancelled", "customer");
            long refundId = insertRefundBy("customer", buyerId);
            reject(refundId);

            assertThat(sweeper.sweepClosedBundles())
                    .as("반려된 요청은 돈이 안 나간 것이라 환급 의무가 그대로 남아 있다")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("사유를 정할 때")
    class Reasoning {

        @Test
        @DisplayName("고객이 취소했으면 청약철회로 보고 취소일에서 센다")
        void treatsCustomerCancelAsWithdrawal() {
            close("cancelled", "customer");
            sweeper.sweepClosedBundles();

            assertThat(reasonCode()).isEqualTo("cancelled");
            assertThat(dueDate())
                    .as("제18조제2항 3호 — 청약철회를 한 날부터 센다")
                    .isEqualTo(businessDaysAfter(today().plusDays(CLOSED_LATER_DAYS)));
        }

        /**
         * <b>이 줄이 이번 청크의 이유다.</b> 셀러가 못 보내서 취소한 것은 청약철회가 아니라
         * 제15조제2항의 공급 곤란이고, 기산점이 <b>대금을 지급한 날</b>이다.
         *
         * <p>취소 시각에서 세면 결제일보다 뒤로 밀려서 법보다 늦게 잡는다.
         */
        @Test
        @DisplayName("셀러가 취소했으면 공급 불능으로 보고 결제일에서 센다")
        void treatsSellerCancelAsSupplyFailure() {
            close("cancelled", "seller");
            sweeper.sweepClosedBundles();

            assertThat(reasonCode()).isEqualTo("supply_failed");
            assertThat(dueDate())
                    .as("제15조제2항 — 대금을 지급한 날부터 3영업일이다")
                    .isEqualTo(businessDaysAfter(today()));
        }

        @Test
        @DisplayName("관리자가 취소했으면 이른 쪽인 결제일에서 센다")
        void fallsBackToPaymentDateForAdmin() {
            close("cancelled", "admin");
            sweeper.sweepClosedBundles();

            assertThat(reasonCode()).isEqualTo("admin_cancelled");
            assertThat(dueDate())
                    .as("사유가 자유 텍스트라 조문을 못 고른다. 늦게 잡으면 위반이고 일찍 잡으면 우리 손해다")
                    .isEqualTo(businessDaysAfter(today()));
        }

        /**
         * 반품은 누가 눌렀든 청약철회다.
         *
         * <p>셀러가 반품완료를 누르는 것은 <b>재화를 반환받았다는 사실</b>이지
         * 셀러가 일으킨 사건이 아니다(제18조제2항 1호).
         */
        @Test
        @DisplayName("반품은 셀러가 눌러도 청약철회다")
        void treatsReturnAsWithdrawalEvenWhenSellerClosesIt() {
            close("returned", "seller");
            sweeper.sweepClosedBundles();

            assertThat(reasonCode()).isEqualTo("withdrawal");
            assertThat(dueDate()).isEqualTo(businessDaysAfter(today().plusDays(CLOSED_LATER_DAYS)));
        }
    }

    @Nested
    @DisplayName("만든 요청은")
    class Created {

        @Test
        @DisplayName("요청자가 시스템이고 사람이 없다")
        void hasNoHumanRequester() {
            close("cancelled", "customer");
            sweeper.sweepClosedBundles();

            assertThat(column("requested_by_type")).isEqualTo("system");
            assertThat(requesterUserIsNull())
                    .as("배치는 사람이 아니다. 전이를 일으킨 사람 이름을 박으면 사실과 다르다")
                    .isTrue();
        }

        @Test
        @DisplayName("남은 것 전부와 배송비를 잡는다")
        void takesEverythingLeft() {
            close("cancelled", "customer");
            sweeper.sweepClosedBundles();

            assertThat(Long.parseLong(column("amount")))
                    .isEqualTo(PRICE * ORDERED + SHIPPING_FEE);
        }

        @Test
        @DisplayName("승인 대기로 남는다")
        void waitsForApproval() {
            close("cancelled", "customer");
            sweeper.sweepClosedBundles();

            assertThat(column("status"))
                    .as("스위퍼는 요청까지만 한다. 돈이 나가는 결정은 사람이 한다(`V24`)")
                    .isEqualTo("requested");
        }
    }

    /**
     * 묶음을 닫고 누가 닫았는지를 이력에 남긴다.
     *
     * <p><b>결제보다 늦게 닫는다.</b> 두 기산점이 같은 날이면 어느 쪽을 썼든 통과해서
     * 규칙이 있으나 없으나 초록이 된다.
     */
    private void close(String status, String actorType) {
        jdbc.sql("""
                        update seller_order
                           set status = :status, closed_at = now() + make_interval(days => :days)
                         where seller_order_id = :id
                        """)
                .param("status", status)
                .param("days", CLOSED_LATER_DAYS)
                .param("id", sellerOrderId)
                .update();

        jdbc.sql("""
                        insert into order_status_history (seller_order_id, from_status, to_status,
                                                          actor_type, actor_user_id, reason)
                        values (:id, 'preparing', :status, :actorType, :userId, :reason)
                        """)
                .param("id", sellerOrderId)
                .param("status", status)
                .param("actorType", actorType)
                // 시스템 전이에는 남길 사람이 없다(`V18` order_status_history_actor_user_check).
                .param("userId", "system".equals(actorType) ? null : buyerId)
                // 관리자 강제 전이는 사유가 필수다(`D7`).
                .param("reason", "admin".equals(actorType) ? "고객센터 요청" : null)
                .update();
    }

    private long insertRefundBy(String type, long userId) {
        return jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, status, reason_code,
                                            amount, requested_by_type, requested_by_user_id, due_at)
                        values (:number, :id, 'requested', 'cancelled', :amount,
                                :type, :userId, now())
                        returning refund_id
                        """)
                .param("number", "R-20260101-AAAAAB")
                .param("id", sellerOrderId)
                .param("amount", PRICE)
                .param("type", type)
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /**
     * 항목을 붙이고 반려로 옮긴다.
     *
     * <p>항목이 없으면 커밋할 때 {@code assert_refund_totals} 가 터진다(`V23`).
     * 반려도 그 검사를 지나므로 손으로 넣은 행이라도 등식이 맞아야 한다.
     */
    private void reject(long refundId) {
        long orderItemId = jdbc.sql(
                        "select order_item_id from order_item where seller_order_id = :id limit 1")
                .param("id", sellerOrderId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into refund_item (refund_id, order_item_id, quantity,
                                                 amount, commission_refund)
                        values (:refundId, :orderItemId, 1, :amount, 0)
                        """)
                .param("refundId", refundId)
                .param("orderItemId", orderItemId)
                .param("amount", PRICE)
                .update();

        jdbc.sql("""
                        update refund
                           set status = 'rejected', approved_by_user_id = :userId,
                               decision_reason = '증빙이 없다', decided_at = now()
                         where refund_id = :refundId
                        """)
                .param("userId", fixture.insertUser("sweeper-admin@test.local", "관리자"))
                .param("refundId", refundId)
                .update();
    }

    private int refundCount() {
        return jdbc.sql("select count(*) from refund where seller_order_id = :id")
                .param("id", sellerOrderId)
                .query(Integer.class)
                .single();
    }

    /** 스위퍼가 만든 것 하나를 읽는다. 시스템 요청만 고른다 — 손으로 넣은 행과 안 섞인다 */
    private String column(String name) {
        return jdbc.sql("""
                        select %s::text from refund
                         where seller_order_id = :id and requested_by_type = 'system'
                        """.formatted(name))
                .param("id", sellerOrderId)
                .query(String.class)
                .single();
    }

    /** {@code query(String.class)} 는 null 을 못 받는다. 비어 있는지는 SQL 로 묻는다 */
    private boolean requesterUserIsNull() {
        return jdbc.sql("""
                        select requested_by_user_id is null from refund
                         where seller_order_id = :id and requested_by_type = 'system'
                        """)
                .param("id", sellerOrderId)
                .query(Boolean.class)
                .single();
    }

    private String reasonCode() {
        return column("reason_code");
    }

    private LocalDate dueDate() {
        return java.time.OffsetDateTime.parse(column("due_at").replace(" ", "T"))
                .atZoneSameInstant(BusinessCalendar.ZONE)
                .toLocalDate();
    }

    private LocalDate businessDaysAfter(LocalDate from) {
        return calendar.plusBusinessDays(from, 3);
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessCalendar.ZONE);
    }

    private long placeAndPay() {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyerId)
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

        OrderService.Created created = orderService.create(buyerId,
                new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)));

        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), "card", GOOD_CARD));

        return created.orderId();
    }

    private long insertSku() {
        long ownerId = fixture.insertUser("sweeper-owner@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '스위퍼 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat, stock_count)
                        values (:productId, :price, :stock)
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .param("stock", STOCK)
                .query(Long.class)
                .single();
    }
}
