package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.order.OrderQuery;
import com.projectshop.shop.order.SellerOrderQuery;
import com.projectshop.shop.payment.RefundQuery;
import com.projectshop.shop.product.ProductQuery;
import com.projectshop.shop.review.ReviewQuery;
import com.projectshop.shop.settlement.SettlementQuery;
import com.projectshop.shop.support.ListQuery.Paging;
import com.projectshop.shop.support.QueryCounter;

/**
 * <b>쿼리 예산</b>(`Q204`, `D26`). 속도가 아니라 모양을 잰다 — 목록은 쪽 크기 1 과 50 에서, 상세는 묶음 1 개와 3 개에서
 * 나간 문장 수가 같아야 한다. 다르면 줄마다 질의가 나가는 것이다(N+1). 목록은 거기에 더해 {@value #BUDGET} 이하다.
 *
 * <p><b>입구가 아니라 조회기를 잰다.</b> 목록의 SQL 은 전부 조회기 안에 있고, 입구의 필터(세션·생존 확인)가 내는 문장은 쪽 크기와
 * 무관하다. 권한 규칙은 캐시라서 먼저 한 번 불러 데운다.
 *
 * <p><b>아직 안 잰 입구 둘</b>(문의·매출)은 `Q204b` 가 더한다 — 시험 데이터를 세울 도구가 없다.
 */
@DisplayName("쿼리 예산")
class QueryBudgetTest extends PostgresTestBase {

    /** 목록 한 번에 나가도 되는 문장 수 — 판정·목록·총수 */
    static final int BUDGET = 3;

    /** {@link #BUDGET} 을 넘는 목록과 그 근거. <b>근거 없이 이름만 넣지 않는다</b> */
    private static final Map<String, Integer> OVER_BUDGET = Map.of();

    @TestConfiguration
    static class Counting {

        @Bean
        static BeanPostProcessor countingDataSource() {
            return QueryCounter.wrappingDataSource();
        }
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OrderQuery orders;

    @Autowired
    private SellerOrderQuery sellerOrders;

    @Autowired
    private ProductQuery products;

    @Autowired
    private ReviewQuery reviews;

    @Autowired
    private RefundQuery refunds;

    @Autowired
    private SettlementQuery settlements;

    private AuthFixture fixture;
    private long[] sellers;
    private long owner;
    private long buyer;
    private long admin;
    private long[] productOf;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        sellers = new long[3];
        productOf = new long[3];
        for (int i = 0; i < 3; i++) {
            sellers[i] = fixture.insertSeller("s-budget-" + i, "예산셀러" + i);
            fixture.verifySeller(sellers[i]);
        }
        owner = fixture.insertUser("budget-owner@test.local", "대표");
        fixture.joinSeller(sellers[0], owner);
        fixture.grantOrg(owner, "seller_owner", sellers[0]);
        buyer = fixture.insertUser("budget-buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");
        admin = fixture.insertUser("budget-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");
        for (int i = 0; i < 3; i++) {
            productOf[i] = insertProduct(sellers[i], "예산 상품 " + i);
        }
    }

    @Test
    @DisplayName("내 주문 목록")
    void myOrders() {
        for (int i = 0; i < 3; i++) {
            order(1);
        }
        listStaysFlat("내 주문 목록", size -> orders.findMine(buyer, null, new Paging(0, size)).items());
    }

    @Test
    @DisplayName("관리자 주문 목록")
    void allOrders() {
        for (int i = 0; i < 3; i++) {
            order(1);
        }
        listStaysFlat("관리자 주문 목록",
                size -> orders.findAll(admin, null, null, null, null, new Paging(0, size)).items());
    }

    @Test
    @DisplayName("셀러 주문 목록")
    void sellerOrderList() {
        for (int i = 0; i < 3; i++) {
            order(1);
        }
        long denials = denials();

        listStaysFlat("셀러 주문 목록", size -> sellerOrders.find(owner, sellers[0], null, new Paging(0, size)).items());

        // 「전체 범위냐」는 갈래를 고르는 물음이라 거부가 정상이다 — 감사에 쌓이면 진짜 시도가 묻힌다(`covers`).
        assertThat(denials()).as("셀러가 제 주문을 본 것은 막힌 시도가 아니다").isEqualTo(denials);
    }

    @Test
    @DisplayName("상품 공개 목록")
    void publicProducts() {
        listStaysFlat("상품 공개 목록", size -> products.findPublic(null, null, new Paging(0, size)).items());
    }

    @Test
    @DisplayName("상품 후기 목록")
    void productReviews() {
        for (int i = 0; i < 3; i++) {
            Placed placed = order(1);
            jdbc.sql("""
                            insert into review (order_item_id, product_id, user_id, rating, body)
                            values (:item, :product, :user, 5, '좋다')
                            """)
                    .param("item", placed.orderItemIds()[0]).param("product", productOf[0]).param("user", buyer)
                    .update();
        }
        listStaysFlat("상품 후기 목록", size -> reviews.findByProduct(null, productOf[0], new Paging(0, size)).items());
    }

    @Test
    @DisplayName("환불 대기열")
    void refundQueue() {
        for (int i = 0; i < 3; i++) {
            insertRefund(order(1));
        }
        listStaysFlat("환불 대기열", size -> refunds.find(admin, null, null, new Paging(0, size)).items());
    }

    @Test
    @DisplayName("정산서 목록")
    void settlementList() {
        for (int month = 1; month <= 3; month++) {
            insertSettlement(sellers[0], month);
        }
        listStaysFlat("정산서 목록", size -> settlements.find(owner, new Paging(0, size)).items());
    }

    /** 묶음마다 판정을 부르는 자리(`allowedActions`·`forcibleStatuses`·반품 진행)가 묶음 수만큼 질의를 내면 안 된다 */
    @Test
    @DisplayName("주문 상세 — 묶음 하나와 셋에서 문장 수가 같다")
    void orderDetailIsFlatInBundles() {
        String single = order(1).orderNumber();
        String triple = order(3).orderNumber();

        for (long viewer : new long[] {buyer, admin}) {
            orders.findByNumber(viewer, single);
            int one = QueryCounter.count(() -> orders.findByNumber(viewer, single));
            int three = QueryCounter.count(() -> orders.findByNumber(viewer, triple));

            assertThat(three).as("보는 사람 %d — 묶음 1 에서 %d, 3 에서 %d", viewer, one, three).isEqualTo(one);
        }
    }

    @Test
    @DisplayName("셀러 주문 상세 — 품목 하나와 셋에서 문장 수가 같다")
    void sellerOrderDetailIsFlatInItems() {
        String single = order(1).sellerOrderNumbers()[0];
        String triple = orderWithItems(3);

        sellerOrders.findByNumber(owner, single);
        int one = QueryCounter.count(() -> sellerOrders.findByNumber(owner, single));
        int three = QueryCounter.count(() -> sellerOrders.findByNumber(owner, triple));

        assertThat(three).as("품목 1 에서 %d, 3 에서 %d", one, three).isEqualTo(one);
    }

    private void listStaysFlat(String name, IntFunction<List<?>> page) {
        // 줄이 없으면 평평함이 공짜로 참이다 — 셋 이상이 실제로 나와야 잰 것이다.
        assertThat(page.apply(50)).as("%s — 잴 줄이 없다", name).hasSizeGreaterThanOrEqualTo(3);
        int one = QueryCounter.count(call(() -> page.apply(1)));
        int fifty = QueryCounter.count(call(() -> page.apply(50)));

        assertThat(fifty).as("%s — 쪽 크기 1 에서 %d, 50 에서 %d. 늘면 줄마다 질의가 나간다", name, one, fifty)
                .isEqualTo(one);
        assertThat(one).as("%s — 예산 %d 를 넘으면 한 번에 읽거나 OVER_BUDGET 에 근거를 적는다", name, BUDGET)
                .isLessThanOrEqualTo(OVER_BUDGET.getOrDefault(name, BUDGET));
    }

    private static Callable<Object> call(Callable<Object> work) {
        return work;
    }

    private long denials() {
        return jdbc.sql("select count(*) from audit_log where event_type = 'permission.denied'").query(Long.class).single();
    }

    private record Placed(String orderNumber, String[] sellerOrderNumbers, long[] sellerOrderIds, long[] orderItemIds,
            long orderId) {
    }

    /** 손님의 주문 하나. 셀러 {@code bundles} 곳에서 산다(첫째가 {@code sellers[0]}) */
    private Placed order(int bundles) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount, commission_total,
                                                shipping_fee_total, payable_amount, status)
                        values (:number, :userId, 10000, 1000, 0, 10000, 'paid')
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyer).query(Long.class).single();
        OrderFixture.attachContractDocuments(jdbc, orderId);
        // 셀러에게는 결제된 주문만 보인다(`seller_order_visible`).
        jdbc.sql("""
                        insert into payment (order_id, method, amount, status, approval_number)
                        values (:orderId, 'card', 10000, 'approved', :approval)
                        """)
                .param("orderId", orderId).param("approval", "AP-" + OrderFixture.sellerOrderNumber().substring(2))
                .update();

        String[] numbers = new String[bundles];
        long[] sellerOrderIds = new long[bundles];
        long[] items = new long[bundles];
        for (int i = 0; i < bundles; i++) {
            numbers[i] = OrderFixture.sellerOrderNumber();
            sellerOrderIds[i] = insertSellerOrder(orderId, sellers[i], numbers[i]);
            items[i] = insertItem(sellerOrderIds[i], productOf[i]);
        }
        String orderNumber = jdbc.sql("select order_number from shop_order where order_id = :id")
                .param("id", orderId).query(String.class).single();
        return new Placed(orderNumber, numbers, sellerOrderIds, items, orderId);
    }

    /** 셀러 {@code sellers[0]} 한 곳에서 품목 {@code count} 개를 산 주문. 셀러 주문 번호를 돌려준다 */
    private String orderWithItems(int count) {
        Placed placed = order(1);
        for (int i = 1; i < count; i++) {
            insertItem(placed.sellerOrderIds()[0], productOf[0]);
        }
        return placed.sellerOrderNumbers()[0];
    }

    private long insertSellerOrder(long orderId, long sellerId, String number) {
        return jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee, status)
                        values (:number, :orderId, :sellerId, 0, 'confirmed')
                        returning seller_order_id
                        """)
                .param("number", number).param("orderId", orderId).param("sellerId", sellerId)
                .query(Long.class).single();
    }

    private long insertItem(long sellerOrderId, long productId) {
        long skuId = jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat) values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId).query(Long.class).single();
        return jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name, unit_price_incl_vat, quantity,
                                                line_amount, commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '예산 상품', 10000, 1, 10000, 1000, 1000)
                        returning order_item_id
                        """)
                .param("sellerOrderId", sellerOrderId).param("skuId", skuId).query(Long.class).single();
    }

    private long insertProduct(long sellerId, String name) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, :name)
                        returning product_id
                        """)
                .param("sellerId", sellerId).param("userId", owner).param("name", name)
                .query(Long.class).single();
        jdbc.sql("update product set status = 'on_sale' where product_id = :id").param("id", productId).update();
        return productId;
    }

    private void insertRefund(Placed placed) {
        String number = "R-" + OrderFixture.sellerOrderNumber().substring(2);
        jdbc.sql("""
                        insert into refund (refund_number, seller_order_id, amount, shipping_fee_refund, reason_code,
                                            requested_by_type, requested_by_user_id, status, due_at)
                        values (:number, :sellerOrderId, 4000, 0, 'withdrawal', 'customer', :buyer, 'requested',
                                now() + interval '3 days')
                        """)
                .param("number", number).param("sellerOrderId", placed.sellerOrderIds()[0]).param("buyer", buyer)
                .update();
    }

    private void insertSettlement(long sellerId, int month) {
        java.time.LocalDate start = java.time.LocalDate.of(2019, month, 1);
        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (:start, :end, :payout)
                        returning settlement_cycle_id
                        """)
                .param("start", start).param("end", start.plusMonths(1).minusDays(1))
                .param("payout", start.plusMonths(1).withDayOfMonth(10))
                .query(Long.class).single();
        jdbc.sql("""
                        insert into settlement (settlement_number, settlement_cycle_id, seller_id, payout_amount)
                        values (:number, :cycleId, :sellerId, 10000)
                        """)
                .param("number", "T-" + OrderFixture.sellerOrderNumber().substring(2))
                .param("cycleId", cycleId).param("sellerId", sellerId).update();
    }
}
