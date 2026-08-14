package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * 셀러 묶음의 노출 번호와, 셀러에게 보이는 경계.
 *
 * <p>둘 다 <b>빠뜨려도 오류가 안 나는</b> 것이다. 번호가 없으면 전이 대상을 가리킬 방법이 없고,
 * 경계가 없으면 결제도 안 된 주문을 셀러가 준비한다.
 */
@DisplayName("셀러 주문의 번호와 노출 경계")
class SellerOrderVisibilityTest extends PostgresTestBase {

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long alphaSku;
    private long betaSku;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        buyer = fixture.insertUser("visible-buyer@test.local", "산사람");

        long alpha = fixture.insertSeller("s-visible-a", "알파");
        fixture.verifySeller(alpha);
        alphaSku = insertSku(alpha, "알파 상품");

        long beta = fixture.insertSeller("s-visible-b", "베타");
        fixture.verifySeller(beta);
        betaSku = insertSku(beta, "베타 상품");
    }

    @Nested
    @DisplayName("노출 번호")
    class Number {

        /**
         * 형식이 주문번호와 같으면 전화로 번호를 받는 자리에서 어느 쪽인지 못 가른다.
         * 접두어가 그것을 가른다(`D9`).
         */
        @Test
        @DisplayName("S- 로 시작하는 번호가 발급된다")
        void hasPrefixedNumber() {
            long orderId = placeOrder(List.of(alphaSku));

            assertThat(numbersOf(orderId))
                    .singleElement()
                    .asString()
                    .matches("^S-[0-9]{8}-[2-9A-HJ-NP-Z]{6}$");
        }

        @Test
        @DisplayName("셀러가 둘이면 번호도 둘이고 서로 다르다")
        void oneNumberPerSeller() {
            long orderId = placeOrder(List.of(alphaSku, betaSku));

            assertThat(numbersOf(orderId))
                    .as("한 주문 안에서 번호가 겹치면 전이 대상을 가리킬 수 없다")
                    .hasSize(2)
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("셀러에게 보이는 경계")
    class Visibility {

        /**
         * 셀러가 할 일이 생기는 시점은 결제 완료다. 결제 대기 건이 보이면
         * 아직 안 팔린 것을 준비하게 된다.
         */
        @Test
        @DisplayName("결제 전 주문은 뷰에 안 뜬다")
        void unpaidIsHidden() {
            long orderId = placeOrder(List.of(alphaSku));

            assertThat(visibleCountOf(orderId))
                    .as("미결제 건이 셀러 목록에 새면 화면에 오류로 안 드러난다")
                    .isZero();
        }

        @Test
        @DisplayName("결제되면 뜬다")
        void paidIsVisible() {
            long orderId = placeOrder(List.of(alphaSku));

            statuses.movePayment(orderId, Payment.PAID, Actor.system("테스트 결제"));

            assertThat(visibleCountOf(orderId)).isEqualTo(1);
        }

        @Test
        @DisplayName("결제가 만료된 주문도 안 뜬다")
        void expiredStaysHidden() {
            long orderId = placeOrder(List.of(alphaSku));

            statuses.movePayment(orderId, Payment.PAYMENT_EXPIRED, Actor.system("테스트 만료"));

            assertThat(visibleCountOf(orderId)).isZero();
        }
    }

    private List<String> numbersOf(long orderId) {
        return jdbc.sql("""
                        select seller_order_number from seller_order
                         where order_id = :orderId
                         order by seller_order_id
                        """)
                .param("orderId", orderId)
                .query(String.class)
                .list();
    }

    private long visibleCountOf(long orderId) {
        return jdbc.sql("select count(*) from seller_order_visible where order_id = :orderId")
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private long placeOrder(List<Long> skuIds) {
        long cartId = jdbc.sql("insert into cart (user_id) values (:userId) returning cart_id")
                .param("userId", buyer)
                .query(Long.class)
                .single();

        List<Long> cartItemIds = skuIds.stream()
                .map(skuId -> jdbc.sql("""
                                insert into cart_item (cart_id, sku_id, quantity)
                                values (:cartId, :skuId, 1)
                                returning cart_item_id
                                """)
                        .param("cartId", cartId)
                        .param("skuId", skuId)
                        .query(Long.class)
                        .single())
                .toList();

        return orderService.create(buyer, new OrderService.Command(cartItemIds,
                        new OrderService.Shipping("홍길동", "010-0000-0000", "06134",
                                "서울시 강남구", "101호", null)))
                .orderId();
    }

    private long insertSku(long sellerId, String productName) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, :name, 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyer)
                .param("name", productName)
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
}
