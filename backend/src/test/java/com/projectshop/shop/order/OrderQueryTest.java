package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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

/**
 * 산 사람이 자기 주문을 보는 경로.
 *
 * <p>여기서 지키는 것 셋이다 — <b>남의 것이 안 섞이고</b>, <b>못 보는 주문이 없는 주문과 같은 답을 주고</b>,
 * <b>거래기록 열람(`D2` R6)이 상세 하나로 끝난다.</b> 셋 다 틀려도 화면은 정상으로 보인다.
 */
@DisplayName("주문 조회")
class OrderQueryTest extends PostgresTestBase {

    @Autowired
    private OrderQuery orders;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long other;
    private long skuId;
    private String orderNumber;
    private long orderId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyer = fixture.insertUser("query-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        other = fixture.insertUser("query-other@test.local", "남");
        fixture.grantGlobal(other, "customer");

        long sellerId = fixture.insertSeller("s-query", "조회셀러");
        fixture.verifySeller(sellerId);
        skuId = insertSku(sellerId);

        orderId = placeOrder(buyer);
        orderNumber = numberOf(orderId);
    }

    @Nested
    @DisplayName("목록")
    class ListMine {

        @Test
        @DisplayName("내가 산 것만 나온다")
        void onlyMine() {
            placeOrder(other);

            List<OrderQuery.Summary> mine = orders.findMine(buyer, null, 0, 20).items();

            assertThat(mine)
                    .as("남의 주문이 한 건이라도 섞이면 목록 조건이 스코프를 안 자른 것이다")
                    .extracting(OrderQuery.Summary::orderNumber)
                    .containsExactly(orderNumber);
        }

        @Test
        @DisplayName("상태는 대문자로 나간다")
        void statusIsUpperCase() {
            OrderQuery.Summary summary = orders.findMine(buyer, null, 0, 20).items().getFirst();

            assertThat(summary.status()).isEqualTo("PAYMENT_PENDING");
        }

        @Test
        @DisplayName("산 것의 수가 같이 온다")
        void itemCountComesAlong() {
            OrderQuery.Summary summary = orders.findMine(buyer, null, 0, 20).items().getFirst();

            assertThat(summary.itemCount()).isEqualTo(1);
        }

        /**
         * 볼 수 있는 것이 없으면 답은 0건이다. 오류로 만들면 역할이 없는 계정이 목록 화면을
         * 열 때마다 오류를 보게 되는데, 그건 "아직 아무것도 안 샀다" 와 같은 상태다.
         */
        @Test
        @DisplayName("권한이 없으면 빈 페이지다")
        void noPermissionGivesEmptyPage() {
            long stranger = fixture.insertUser("query-stranger@test.local", "역할없음");

            OrderQuery.Page page = orders.findMine(stranger, null, 0, 20);

            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isZero();
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("셀러 묶음과 그 안의 항목이 같이 온다")
        void sellerOrdersCarryItems() {
            OrderQuery.Detail detail = orders.findByNumber(buyer, orderNumber);

            assertThat(detail.sellerOrders()).hasSize(1);
            OrderQuery.SellerOrder sellerOrder = detail.sellerOrders().getFirst();

            assertThat(sellerOrder.sellerName()).isEqualTo("조회셀러");
            assertThat(sellerOrder.status()).isEqualTo("PREPARING");
            assertThat(sellerOrder.items())
                    .extracting(OrderQuery.Item::productName)
                    .containsExactly("조회 상품");
        }

        /**
         * 전자상거래법 제6조 제3항이 소비자에게 거래기록 열람 방법을 요구한다(`D2` R6).
         * 두 층을 나눠 내리면 화면이 매번 합쳐야 하고, 한쪽을 빠뜨려도 오류가 안 난다.
         */
        @Test
        @DisplayName("결제 층과 배송 층 이력이 한 줄로 선다")
        void historyMergesBothLayers() {
            statuses.movePayment(orderId, Payment.PAID, Actor.system("테스트 결제"));
            statuses.moveShipment(sellerOrderOf(orderId), Shipment.SHIPPING, Actor.system("테스트 발송"));

            List<OrderQuery.HistoryEntry> history = orders.findByNumber(buyer, orderNumber).history();

            assertThat(history)
                    .as("두 층이 한 줄로 안 서면 소비자가 주문 하나의 생애를 못 본다")
                    .extracting(OrderQuery.HistoryEntry::toStatus)
                    .contains("PAID", "SHIPPING");

            assertThat(history)
                    .isSortedAccordingTo(
                            java.util.Comparator.comparing(OrderQuery.HistoryEntry::occurredAt));
        }

        @Test
        @DisplayName("고객은 배송지까지 본다")
        void buyerSeesShipping() {
            OrderQuery.Detail detail = orders.findByNumber(buyer, orderNumber);

            assertThat(detail.shipping()).isNotNull();
            assertThat(detail.shipping().receiverName()).isEqualTo("홍길동");
        }

        /**
         * 감사자는 조회 범위가 전체지만 결제 수단까지 볼 이유는 없다(`V6`).
         * 빠진 것이 권한 때문임은 {@code _visible_field_groups} 가 알린다(`D5`).
         */
        @Test
        @DisplayName("감사자에게는 payment 그룹이 안 열린다")
        void auditorSeesNoPaymentGroup() {
            long auditor = fixture.insertUser("query-auditor@test.local", "감사자");
            fixture.grantGlobal(auditor, "auditor");

            OrderQuery.Detail detail = orders.findByNumber(auditor, orderNumber);

            assertThat(detail.visibleFieldGroups()).containsExactly("basic", "shipping");
            assertThat(detail.shipping()).isNotNull();
        }

        /**
         * 403 을 주면 번호를 훑어서 실재하는 주문의 지도를 그릴 수 있고, 그게 곧 주문 수와
         * 증가 속도다(`D5` 「권한 실패」). 그래서 없는 것과 못 보는 것의 답이 같아야 한다.
         */
        @Test
        @DisplayName("남의 주문은 없는 주문과 같은 답을 준다")
        void othersOrderLooksMissing() {
            assertThatThrownBy(() -> orders.findByNumber(other, orderNumber))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("없는 번호도 같은 답이다")
        void unknownNumberIsSameAnswer() {
            assertThatThrownBy(() -> orders.findByNumber(buyer, "20260811-ZZZZZZ"))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }
    }

    private long placeOrder(long userId) {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", userId)
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

        return orderService.create(userId, new OrderService.Command(List.of(cartItemId),
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long sellerId) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, '조회 상품', 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyer)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price, stock_count)
                        values (:productId, 10000, 10)
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }

    private String numberOf(long order) {
        return jdbc.sql("select order_number from shop_order where order_id = :orderId")
                .param("orderId", order)
                .query(String.class)
                .single();
    }

    private long sellerOrderOf(long order) {
        return jdbc.sql("select seller_order_id from seller_order where order_id = :orderId")
                .param("orderId", order)
                .query(Long.class)
                .single();
    }
}
