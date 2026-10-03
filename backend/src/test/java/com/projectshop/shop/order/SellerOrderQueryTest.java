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
import com.projectshop.shop.support.ListQuery.Paging;

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

            List<SellerOrderQuery.Summary> items = sellerOrders.find(alphaOwner, null, null, new Paging(0, 20))
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

            SellerOrderQuery.Page page = sellerOrders.find(alphaOwner, null, null, new Paging(0, 20));

            assertThat(page.items()).isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        @DisplayName("상태는 대문자로 나간다")
        void statusIsUpperCase() {
            payFor(placeOrder(List.of(alphaSku)));

            assertThat(sellerOrders.find(alphaOwner, null, null, new Paging(0, 20)).items().getFirst().status())
                    .isEqualTo("PREPARING");
        }

        /**
         * 0건과 못 봄이 갈려야 개수로 정보가 새지 않는다. 소속이 있어도 주문 권한이 없으면
         * 빈 목록이 아니라 거부다.
         */
        @Test
        @DisplayName("범위 밖 셀러를 고르면 빈 쪽이다")
        void otherSellerIsEmpty() {
            payFor(placeOrder(List.of(alphaSku, betaSku)));

            SellerOrderQuery.Page page = sellerOrders.find(alphaOwner, beta, null, new Paging(0, 20));

            assertThat(page.items()).as("고른 셀러가 범위 밖이면 남의 묶음이 나오면 안 된다").isEmpty();
            assertThat(page.total()).isZero();
        }

        @Test
        @DisplayName("두 셀러에 속하면 셀러를 안 고를 때 둘 다, 고르면 그 하나만 나온다")
        void memberOfBothSeesBoth() {
            payFor(placeOrder(List.of(alphaSku, betaSku)));
            long both = fixture.insertUser("so-both@test.local", "양쪽");
            fixture.joinSeller(alpha, both);
            fixture.grantOrg(both, "seller_owner", alpha);
            fixture.joinSeller(beta, both);
            fixture.grantOrg(both, "seller_owner", beta);

            assertThat(sellerOrders.find(both, null, null, new Paging(0, 20)).total()).isEqualTo(2);
            assertThat(sellerOrders.find(both, beta, null, new Paging(0, 20)).total()).isEqualTo(1);
        }

        @Test
        @DisplayName("관리자는 셀러를 안 고르면 전부, 고르면 그 셀러만 본다")
        void adminSeesAllOrOne() {
            payFor(placeOrder(List.of(alphaSku, betaSku)));
            long admin = fixture.insertUser("so-admin@test.local", "관리자");
            fixture.grantGlobal(admin, "admin");

            assertThat(sellerOrders.find(admin, null, null, new Paging(0, 20)).total()).isGreaterThanOrEqualTo(2);
            assertThat(sellerOrders.find(admin, alpha, null, new Paging(0, 20)).items())
                    .singleElement()
                    .satisfies(summary -> assertThat(summary.itemCount()).isEqualTo(1));
        }

        /**
         * 셀러 하나의 목록이 정렬 인덱스를 끝까지 탄다(`Q243a`). 묶음 5,000 을 열 가지 시각에 몰아 깐다 —
         * 같은 시각이 많을 때 동점 키 없는 인덱스는 {@code Incremental Sort} 로 그 묶음들을 다 읽는다(로컬 10만 건의 모양).
         * 조건을 {@code = any(배열)} 로 되돌리거나 인덱스에서 동점 키가 빠지면 정렬 노드가 서서 빨갛다.
         */
        @Test
        @DisplayName("셀러 하나의 목록은 정렬 없이 셀러 인덱스로 읽는다")
        void oneSellerReadsIndexInOrder() {
            String plan = planOf(SellerOrderQuery.Branch.ONE);

            assertThat(plan)
                    .as("실행 계획:%n%s", plan)
                    .contains("seller_order_seller_idx")
                    .doesNotContain("Seq Scan on seller_order")
                    .doesNotContain("Sort");
        }

        /** 셀러 조건이 없는 갈래(관리자)는 시각 인덱스(`V124`)를 탄다. 없으면 묶음 전부를 순차로 읽어 정렬한다 */
        @Test
        @DisplayName("전부 보는 목록은 시각 인덱스로 읽는다")
        void everythingReadsCreatedAtIndex() {
            String plan = planOf(SellerOrderQuery.Branch.ALL);

            assertThat(plan)
                    .as("실행 계획:%n%s", plan)
                    .contains("seller_order_created_at_idx")
                    .doesNotContain("Seq Scan on seller_order");
        }

        /** 묶음 5,000 을 열 가지 시각에 몰아 한 번 깔고 그 갈래의 목록 계획을 낸다 */
        private String planOf(SellerOrderQuery.Branch branch) {
            if (!filled) {
                jdbc.sql("""
                                with o as (
                                    insert into shop_order (order_number, user_id, status, created_at)
                                    select '20260101-' || translate(lpad(n::text, 6, '0'), '0123456789', 'ABCDEFGHJK'),
                                           :buyer, 'paid', now() - make_interval(hours => n % 10)
                                      from generate_series(1, 5000) as n
                                    returning order_id, order_number, created_at
                                )
                                insert into seller_order (seller_order_number, order_id, seller_id, status, created_at)
                                select 'S-' || order_number, order_id, :seller, 'preparing', created_at from o
                                """)
                        .param("buyer", buyer).param("seller", alpha)
                        .update();
                jdbc.sql("analyze shop_order, seller_order").update();
                filled = true;
            }

            return String.join("\n", jdbc.sql("explain " + SellerOrderQuery.listSql(
                            branch, SellerOrderQuery.defaultOrder()))
                    .param("sellerId", alpha)
                    .param("sellers", new Long[0])
                    .param("size", 20)
                    .param("offset", 0L)
                    .query(String.class)
                    .list());
        }

        private boolean filled;

        @Test
        @DisplayName("주문 권한이 없으면 거부다")
        void noPermissionIsRefused() {
            long clerk = fixture.insertUser("so-clerk@test.local", "권한없음");
            fixture.joinSeller(alpha, clerk);

            assertThatThrownBy(() -> sellerOrders.find(clerk, null, null, new Paging(0, 20)))
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
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat)
                            values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();
    }
}
