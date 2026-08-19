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

/**
 * 셀러가 보는 주문.
 *
 * <p>여기서 지키는 것 셋이다 — <b>남의 셀러 것이 안 섞이고</b>, <b>결제 전 건이 안 보이고</b>,
 * <b>셀러에게 결제 수단이 안 열린다.</b> 셋 다 틀려도 화면은 정상으로 보인다.
 */
@DisplayName("셀러 주문 조회")
class SellerOrderQueryTest extends PostgresTestBase {

    @Autowired
    private SellerOrderQuery sellerOrders;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderStatusService statuses;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long buyer;
    private long alphaOwner;
    private long alpha;
    private long beta;
    private long alphaSku;
    private long betaSku;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        buyer = fixture.insertUser("so-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        alpha = fixture.insertSeller("s-so-alpha", "알파");
        fixture.verifySeller(alpha);
        alphaSku = insertSku(alpha, "알파 상품");

        beta = fixture.insertSeller("s-so-beta", "베타");
        fixture.verifySeller(beta);
        betaSku = insertSku(beta, "베타 상품");

        alphaOwner = fixture.insertUser("so-alpha-owner@test.local", "알파대표");
        fixture.joinSeller(alpha, alphaOwner);
        fixture.grantOrg(alphaOwner, "seller_owner", alpha);
    }

    @Nested
    @DisplayName("목록")
    class ListMine {

        @Test
        @DisplayName("내 셀러로 넘어온 것만 나온다")
        void onlyMySeller() {
            payFor(placeOrder(List.of(alphaSku, betaSku)));

            List<SellerOrderQuery.Summary> items = sellerOrders.find(alphaOwner, null, null, 0, 20)
                    .items();

            assertThat(items)
                    .as("남의 셀러 묶음이 섞이면 조건이 스코프를 안 자른 것이다")
                    .singleElement()
                    .satisfies(summary -> assertThat(summary.itemCount()).isEqualTo(1));
        }

        /**
         * 뷰가 이미 거르므로 여기 조건이 없다. 그 사실을 API 층에서 한 번 더 고정한다 —
         * 나중에 누가 뷰 대신 실테이블을 읽도록 바꾸면 이 테스트가 깨진다.
         */
        @Test
        @DisplayName("결제 전 주문은 안 나온다")
        void unpaidIsHidden() {
            placeOrder(List.of(alphaSku));

            SellerOrderQuery.Page page = sellerOrders.find(alphaOwner, null, null, 0, 20);

            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        @DisplayName("상태는 대문자로 나간다")
        void statusIsUpperCase() {
            payFor(placeOrder(List.of(alphaSku)));

            assertThat(sellerOrders.find(alphaOwner, null, null, 0, 20).items().getFirst().status())
                    .isEqualTo("PREPARING");
        }

        /**
         * 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다. 소속이 있어도 주문 권한이 없으면
         * 빈 목록이 아니라 거부다.
         */
        @Test
        @DisplayName("주문 권한이 없으면 거부다")
        void noPermissionIsRefused() {
            long clerk = fixture.insertUser("so-clerk@test.local", "권한없음");
            fixture.joinSeller(alpha, clerk);

            assertThatThrownBy(() -> sellerOrders.find(clerk, null, null, 0, 20))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("상세")
    class Detail {

        @Test
        @DisplayName("보낼 것과 받는 사람이 같이 온다")
        void carriesItemsAndShipping() {
            payFor(placeOrder(List.of(alphaSku)));
            String number = numberOf(alpha);

            SellerOrderQuery.Detail detail = sellerOrders.findByNumber(alphaOwner, number);

            assertThat(detail.items())
                    .extracting(OrderQuery.Item::productName)
                    .containsExactly("알파 상품");
            assertThat(detail.shipping().receiverName()).isEqualTo("홍길동");
        }

        /**
         * 판매자는 배송에 필요한 것까지만 본다(`V6`). 빠진 것이 권한 때문임은
         * {@code _visible_field_groups} 가 알린다(`D5`).
         */
        @Test
        @DisplayName("셀러에게는 payment 그룹이 안 열린다")
        void sellerSeesNoPaymentGroup() {
            payFor(placeOrder(List.of(alphaSku)));

            SellerOrderQuery.Detail detail = sellerOrders.findByNumber(alphaOwner, numberOf(alpha));

            assertThat(detail.visibleFieldGroups())
                    .as("refund 는 열려 있다 — 셀러 정산에서 차감되는 돈이라 봐야 한다(`V24`)")
                    .containsExactly("basic", "refund", "shipping");
        }

        @Test
        @DisplayName("남의 셀러 묶음은 없는 것과 같은 답을 준다")
        void othersSellerOrderLooksMissing() {
            payFor(placeOrder(List.of(betaSku)));
            String betaNumber = numberOf(beta);

            assertThatThrownBy(() -> sellerOrders.findByNumber(alphaOwner, betaNumber))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }

        /** 결제 전 묶음은 뷰에 없다. 셀러에게는 아직 존재하지 않는 것과 같다 */
        @Test
        @DisplayName("결제 전 묶음도 없는 것과 같은 답이다")
        void unpaidLooksMissing() {
            placeOrder(List.of(alphaSku));
            String number = numberOf(alpha);

            assertThatThrownBy(() -> sellerOrders.findByNumber(alphaOwner, number))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_ORDER_NOT_FOUND));
        }
    }

    private void payFor(long orderId) {
        statuses.movePayment(orderId, Payment.PAID, Actor.system("테스트 결제"));
    }

    private String numberOf(long sellerId) {
        return jdbc.sql("""
                        select seller_order_number from seller_order
                         where seller_id = :sellerId
                         order by seller_order_id desc
                         limit 1
                        """)
                .param("sellerId", sellerId)
                .query(String.class)
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
