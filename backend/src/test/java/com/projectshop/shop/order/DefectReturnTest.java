package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.order.OrderStatusService.Actor;
import com.projectshop.shop.order.OrderStatusService.ReturnReason;
import com.projectshop.shop.order.OrderTransitions.Shipment;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.payment.PaymentService;

/**
 * 하자 반품이 단순 변심과 다르게 다뤄지나(`D2` R3, 전자상거래법 제17조제3항).
 *
 * <p>조문 한 줄이 이 테스트 전체를 정한다.
 *
 * <blockquote>소비자는 <b>제1항 및 제2항에도 불구하고</b> 재화등의 내용이 표시·광고의 내용과
 * 다르거나 계약내용과 다르게 이행된 경우에는 그 재화등을 공급받은 날부터 3개월 이내,
 * 그 사실을 안 날 또는 알 수 있었던 날부터 30일 이내에 청약철회등을 할 수 있다.</blockquote>
 *
 * <p>「제1항 및 제2항에도 불구하고」가 <b>둘을 동시에 연다</b> — 7일 기한과 청약철회 제한이
 * 둘 다 안 걸린다. 그전까지 우리는 들어오는 반품을 전부 단순 변심으로 봐서
 * <b>8일째 하자 신고와 주문제작 상품의 하자를 거부</b>했다.
 */
@DisplayName("하자 반품")
class DefectReturnTest extends PostgresTestBase {

    private static final long PRICE = 10_000;
    private static final String GOOD_CARD = "4242-4242-4242-4242";

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private PaymentService payments;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyerId;
    private long sellerId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyerId = fixture.insertUser("defect-buyer@test.local", "산사람");
        fixture.grantGlobal(buyerId, "customer");

        sellerId = fixture.insertSeller("s-defect", "하자셀러");
        fixture.verifySeller(sellerId);
    }

    @Nested
    @DisplayName("기한은")
    class Period {

        @Test
        @DisplayName("단순 변심이면 7일이 지나 막힌다")
        void changeOfMindExpiresAfterSevenDays() {
            long bundleId = deliveredBundle(false);
            expireWithdrawalPeriod(bundleId);

            assertThatThrownBy(() -> requestReturn(bundleId, ReturnReason.CHANGE_OF_MIND))
                    .as("제17조제1항 — 배송완료 때 박제한 기한을 읽는다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED));
        }

        /**
         * <b>이 줄이 이 청크의 이유다.</b> 8일째 하자 신고가 거부되고 있었다.
         *
         * <p>7일 기한을 지나게 만들어도 하자 반품은 열려야 한다 — 제17조제3항이
         * 「제1항에도 불구하고」라고 해서 그 기한을 비켜 간다.
         */
        @Test
        @DisplayName("하자면 7일이 지나도 접수된다")
        void defectIsAcceptedAfterSevenDays() {
            long bundleId = deliveredBundle(false);
            expireWithdrawalPeriod(bundleId);

            requestReturn(bundleId, ReturnReason.DEFECT);

            assertThat(statusOf(bundleId))
                    .as("제17조제3항 — 공급받은 날부터 3개월이다")
                    .isEqualTo("return_requested");
        }

        @Test
        @DisplayName("하자여도 3개월이 지나면 막힌다")
        void defectExpiresAfterThreeMonths() {
            long bundleId = deliveredBundle(false);
            deliverBackDated(bundleId, 4);

            assertThatThrownBy(() -> requestReturn(bundleId, ReturnReason.DEFECT))
                    .as("3개월은 역일이라 delivered_at 에서 세면 언제 계산해도 같다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_PERIOD_EXPIRED));
        }

        @Test
        @DisplayName("3개월 안이면 접수된다")
        void defectIsAcceptedWithinThreeMonths() {
            long bundleId = deliveredBundle(false);
            deliverBackDated(bundleId, 2);

            requestReturn(bundleId, ReturnReason.DEFECT);

            assertThat(statusOf(bundleId)).isEqualTo("return_requested");
        }
    }

    @Nested
    @DisplayName("청약철회 제한은")
    class Restriction {

        @Test
        @DisplayName("단순 변심을 막는다")
        void blocksChangeOfMind() {
            long bundleId = deliveredBundle(true);

            assertThatThrownBy(() -> requestReturn(bundleId, ReturnReason.CHANGE_OF_MIND))
                    .as("제17조제2항 — 멀쩡한 물건을 무르는 것을 막는 규정이다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WITHDRAWAL_RESTRICTED));
        }

        /**
         * 제17조제3항이 <b>「제2항에도 불구하고」</b>라고 한다.
         *
         * <p>제2항은 멀쩡한 물건을 무르는 것을 막는 규정이라, 물건이 약속과 다른 경우에는
         * 애초에 적용될 자리가 아니다. <b>주문제작 상품이어도 하자면 반품된다.</b>
         */
        @Test
        @DisplayName("하자는 안 막는다")
        void doesNotBlockDefect() {
            long bundleId = deliveredBundle(true);

            requestReturn(bundleId, ReturnReason.DEFECT);

            assertThat(statusOf(bundleId))
                    .as("제한 상품이어도 약속과 다르면 청약철회할 수 있다")
                    .isEqualTo("return_requested");
        }
    }

    @Nested
    @DisplayName("사유는")
    class Reason {

        @Test
        @DisplayName("묶음에 남는다")
        void isRecordedOnTheBundle() {
            long bundleId = deliveredBundle(false);
            requestReturn(bundleId, ReturnReason.DEFECT);

            assertThat(returnReasonOf(bundleId))
                    .as("상태만 남기면 어느 조항으로 받았는지가 사라진다 — 반품 비용 부담이 갈린다")
                    .isEqualTo("defect");
        }

        /**
         * <b>기본값이 단순 변심이다.</b> 하자로 두면 7일과 제한 검사가 아무에게도 안 걸린다 —
         * 안 주는 요청이 가장 넓은 권리를 받는 모양이 된다.
         */
        @Test
        @DisplayName("안 주면 단순 변심이다")
        void defaultsToChangeOfMind() {
            long bundleId = deliveredBundle(false);
            statuses.moveShipment(bundleId, Shipment.RETURN_REQUESTED,
                    Actor.customer(buyerId));

            assertThat(returnReasonOf(bundleId)).isEqualTo("change_of_mind");
        }

        @Test
        @DisplayName("반품이 끝나도 남는다")
        void survivesCompletion() {
            long bundleId = deliveredBundle(false);
            requestReturn(bundleId, ReturnReason.DEFECT);

            statuses.moveShipment(bundleId, Shipment.RETURNED, Actor.seller(buyerId));

            assertThat(returnReasonOf(bundleId))
                    .as("정산과 셀러 평가가 하자율을 읽는다")
                    .isEqualTo("defect");
        }
    }

    private void requestReturn(long bundleId, ReturnReason reason) {
        statuses.moveShipment(bundleId, Shipment.RETURN_REQUESTED,
                Actor.customer(buyerId), reason);
    }

    /** 7일 기한을 지난 것으로 민다. 하루 전으로 당기면 지금이 이미 넘긴 시점이다 */
    private void expireWithdrawalPeriod(long bundleId) {
        jdbc.sql("""
                        update seller_order set withdrawal_expire_at = now() - interval '1 day'
                         where seller_order_id = :id
                        """)
                .param("id", bundleId)
                .update();
    }

    /**
     * 배송완료 시각을 과거로 민다.
     *
     * <p>청약철회 기한도 같이 민다 — 안 밀면 단순 변심 기한이 미래로 남아서
     * 하자 기한만 보는 것인지 둘 다 보는 것인지가 안 갈린다.
     */
    private void deliverBackDated(long bundleId, int monthsAgo) {
        jdbc.sql("""
                        update seller_order
                           set delivered_at = now() - make_interval(months => :months),
                               withdrawal_expire_at = now() - make_interval(months => :months)
                         where seller_order_id = :id
                        """)
                .param("months", monthsAgo)
                .param("id", bundleId)
                .update();
    }

    private String statusOf(long bundleId) {
        return jdbc.sql("select status from seller_order where seller_order_id = :id")
                .param("id", bundleId)
                .query(String.class)
                .single();
    }

    private String returnReasonOf(long bundleId) {
        return jdbc.sql("select return_reason from seller_order where seller_order_id = :id")
                .param("id", bundleId)
                .query(String.class)
                .single();
    }

    /** 결제·발송·배송완료까지 지난 묶음 하나 */
    private long deliveredBundle(boolean withdrawalRestricted) {
        long skuId = insertSku(withdrawalRestricted);

        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyerId)
                .query(Long.class)
                .single();

        long cartItemId = jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, 1)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();

        OrderService.Created created = orderService.create(buyerId,
                new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)));

        payments.pay(buyerId, UUID.randomUUID().toString(),
                new PaymentService.Command(created.orderNumber(), PaymentMethod.CARD, GOOD_CARD));

        long bundleId = jdbc.sql("select seller_order_id from seller_order where order_id = :id")
                .param("id", created.orderId())
                .query(Long.class)
                .single();

        statuses.moveShipment(bundleId, Shipment.SHIPPING, Actor.seller(buyerId));
        statuses.moveShipment(bundleId, Shipment.DELIVERED, Actor.seller(buyerId));

        return bundleId;
    }

    /** @param restricted 청약철회 제한 상품인가(`D2` R4) */
    private long insertSku(boolean restricted) {
        long ownerId = fixture.insertUser(
                "defect-owner-" + UUID.randomUUID() + "@test.local", "대표");
        fixture.joinSeller(sellerId, ownerId);

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status,
                                             is_withdrawal_restricted, withdrawal_restriction_reason)
                        values (:sellerId, :userId, '하자 반품 상품', 'on_sale',
                                :restricted, :reason)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", ownerId)
                .param("restricted", restricted)
                .param("reason", restricted ? "digital_content" : null)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, :price)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("price", PRICE)
                .query(Long.class)
                .single();
    }
}
