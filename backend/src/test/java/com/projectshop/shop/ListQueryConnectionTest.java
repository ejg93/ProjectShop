package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.order.OrderQuery;
import com.projectshop.shop.payment.RefundQuery;
import com.projectshop.shop.product.ProductQuery;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 목록이 줄마다 연결을 하나 더 물지 않는지 본다(`Q250`, `D23` 「행 매퍼 안에서 다른 질의·판정을 안 부른다」).
 *
 * <p>행 매퍼 안에서 판정을 부르면 결과를 읽는 연결 하나를 쥔 채 규칙 조회·거부 감사(REQUIRES_NEW)가
 * <b>둘째 연결</b>을 문다. 풀 크기만큼 동시에 부르면 전부 첫째를 쥐고 둘째를 기다려 시간이 다 된다 —
 * 마무리 57차 부하 대조의 「Failed to obtain JDBC Connection」이 그것이다.
 *
 * <p><b>풀을 둘로 줄여 그 꼴을 작게 만든다.</b> 판정 캐시를 비우고 새 계정으로 부르므로 규칙 조회가 캐시를
 * 못 맞힌다. 감사자는 환불을 다 보되 승인이 거부돼서 줄마다 거부 감사가 남는다.
 *
 * <p><b>롤백이 없다.</b> 요청이 저마다 연결을 잡아야 해서 시험 트랜잭션을 끈다 — 만든 것은 직접 지운다.
 */
@DisplayName("목록 조회의 연결")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=" + ListQueryConnectionTest.POOL_SIZE,
        "spring.datasource.hikari.connection-timeout=2000"})
class ListQueryConnectionTest extends PostgresTestBase {

    static final int POOL_SIZE = 2;

    /** 이 시험이 만든 것임을 알아보는 표시. 정리가 이것만 지운다 */
    private static final String EMAIL_PREFIX = "list-conn-";
    private static final String SELLER_PREFIX = "list-conn-";

    /** 줄이 여럿이어야 행 매퍼가 판정을 여러 번 부른다 */
    private static final int ROWS = 5;

    @Autowired
    private RefundQuery refunds;

    @Autowired
    private OrderQuery orders;

    @Autowired
    private ProductQuery products;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CacheManager cacheManager;

    private long sellerId;
    private long owner;
    private long auditor;
    private long buyer;
    private String orderNumber;

    @BeforeEach
    void setUp() {
        // 앞 실행의 정리가 실패하면 재사용 컨테이너에 남는다(`D15`).
        cleanUp();
        // 주문 금액 등식이 커밋 시점에 보는 지연 트리거라 주문 축을 한 트랜잭션으로 세운다.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> seed());
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    @DisplayName("환불 목록을 풀 크기만큼 동시에 불러도 연결을 다 쥐고 서로 안 기다린다")
    void refundListReleasesTheConnectionBeforeJudging() throws Exception {
        assertConcurrentCallsFinish(() -> refunds.find(auditor, null, null, new Paging(0, 20)).items().size());
        assertConcurrentCallsFinish(() -> refunds.find(owner, null, null, new Paging(0, 20)).items().size());
    }

    @Test
    @DisplayName("주문 상세의 셀러별 묶음도 판정 전에 연결을 놓는다")
    void orderDetailReleasesTheConnectionBeforeJudging() throws Exception {
        // 셀러 대표는 주문 상세를 못 본다(못 봄 = 없음). 산 사람과 전부 보는 감사자로 부른다.
        assertConcurrentCallsFinish(() -> orders.findByNumber(buyer, orderNumber).sellerOrders().size());
        assertConcurrentCallsFinish(() -> orders.findByNumber(auditor, orderNumber).sellerOrders().size());
    }

    @Test
    @DisplayName("셀러 상품 목록도 판정 전에 연결을 놓는다")
    void sellerProductListReleasesTheConnectionBeforeJudging() throws Exception {
        assertConcurrentCallsFinish(() -> products.findForSeller(owner, sellerId, null, new Paging(0, 20))
                .items().size());
    }

    /**
     * 풀 크기만큼 한꺼번에 부른다. 옛 모양이면 전부 첫 연결을 쥐고 둘째를 기다리다
     * {@code connection-timeout} 뒤에 「Failed to obtain JDBC Connection」으로 죽는다.
     */
    private void assertConcurrentCallsFinish(Callable<Integer> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < POOL_SIZE; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS))
                        .as("목록이 줄을 낸다 — 비면 행 매퍼가 판정을 안 불러 이 시험이 아무것도 안 잰다")
                        .isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void seed() {
        AuthFixture fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller(SELLER_PREFIX + "seller", "연결 셀러");
        fixture.verifySeller(sellerId);
        owner = fixture.insertUser(EMAIL_PREFIX + "owner@test.local", "대표");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);
        auditor = fixture.insertUser(EMAIL_PREFIX + "auditor@test.local", "감사자");
        fixture.grantGlobal(auditor, "auditor");
        buyer = fixture.insertUser(EMAIL_PREFIX + "buyer@test.local", "산사람");
        fixture.grantGlobal(buyer, "customer");

        long productId = 0;
        for (int i = 0; i < ROWS; i++) {
            productId = jdbc.sql("""
                            insert into product (seller_id, created_by_user_id, name)
                            values (:sellerId, :userId, :name)
                            returning product_id
                            """)
                    .param("sellerId", sellerId).param("userId", owner).param("name", "연결 상품 " + i)
                    .query(Long.class).single();
        }
        jdbc.sql("update product set status = 'on_sale' where product_id = :id").param("id", productId).update();

        for (int i = 0; i < ROWS; i++) {
            long orderId = jdbc.sql("""
                            insert into shop_order (order_number, user_id, total_amount, commission_total,
                                                    shipping_fee_total, payable_amount, status)
                            values (:number, :userId, 10000, 1000, 0, 10000, 'paid')
                            returning order_id
                            """)
                    .param("number", OrderFixture.sellerOrderNumber().substring(2))
                    .param("userId", buyer).query(Long.class).single();
            OrderFixture.attachContractDocuments(jdbc, orderId);
            jdbc.sql("""
                            insert into payment (order_id, method, amount, status, approval_number)
                            values (:orderId, 'card', 10000, 'approved', :approval)
                            """)
                    .param("orderId", orderId)
                    .param("approval", "AP-" + OrderFixture.sellerOrderNumber().substring(2))
                    .update();
            long sellerOrderId = jdbc.sql("""
                            insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee, status)
                            values (:number, :orderId, :sellerId, 0, 'confirmed')
                            returning seller_order_id
                            """)
                    .param("number", OrderFixture.sellerOrderNumber()).param("orderId", orderId)
                    .param("sellerId", sellerId).query(Long.class).single();
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
            long itemId = jdbc.sql("""
                            insert into order_item (seller_order_id, sku_id, product_name, unit_price_incl_vat,
                                                    quantity, line_amount, commission_bp, commission_amount)
                            values (:sellerOrderId, :skuId, '연결 상품', 10000, 1, 10000, 1000, 1000)
                            returning order_item_id
                            """)
                    .param("sellerOrderId", sellerOrderId).param("skuId", skuId).query(Long.class).single();
            long refundId = jdbc.sql("""
                            insert into refund (refund_number, seller_order_id, amount, shipping_fee_refund,
                                                reason_code, requested_by_type, requested_by_user_id, status, due_at)
                            values (:number, :sellerOrderId, 4000, 0, 'withdrawal', 'customer', :buyer, 'requested',
                                    now() + interval '3 days')
                            returning refund_id
                            """)
                    .param("number", "R-" + OrderFixture.sellerOrderNumber().substring(2))
                    .param("sellerOrderId", sellerOrderId).param("buyer", buyer)
                    .query(Long.class).single();
            // 항목 없는 환불은 커밋 시점에 막힌다(지연 트리거).
            jdbc.sql("""
                            insert into refund_item (refund_id, order_item_id, quantity,
                                                     amount, commission_refund, discount_refund)
                            values (:refundId, :itemId, 1, 4000, 0, 0)
                            """)
                    .param("refundId", refundId).param("itemId", itemId).update();
            orderNumber = jdbc.sql("select order_number from shop_order where order_id = :id")
                    .param("id", orderId).query(String.class).single();
        }
    }

    /**
     * 자식부터 지운다. {@code restrict} 인 참조가 섞여 있어 순서가 곧 외래키 순서다({@code HttpTestBase} 와 같다) —
     * {@code refund}·{@code payment}·{@code order_contract_document} 는 cascade 지만 명시적으로 지운다.
     * 한 트랜잭션으로 묶는다 — 나눠 지우면 금액 등식 지연 트리거가 중간에 터진다.
     */
    @AfterEach
    void cleanUp() {
        String users = "select user_id from app_user where email like '" + EMAIL_PREFIX + "%'";
        String sellers = "select seller_id from seller where code like '" + SELLER_PREFIX + "%'";
        String orderIds = "select order_id from shop_order where user_id in (" + users + ")";
        String sellerOrders = "select seller_order_id from seller_order where order_id in (" + orderIds + ")";
        String productIds = "select product_id from product where seller_id in (" + sellers + ")";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            execute("delete from refund where seller_order_id in (" + sellerOrders + ")");
            execute("delete from order_status_history where order_id in (" + orderIds + ")");
            execute("delete from order_item where seller_order_id in (" + sellerOrders + ")");
            execute("delete from seller_order where order_id in (" + orderIds + ")");
            execute("delete from payment where order_id in (" + orderIds + ")");
            execute("delete from order_contract_document where order_id in (" + orderIds + ")");
            execute("delete from shop_order where user_id in (" + users + ")");
            execute("delete from sku where product_id in (" + productIds + ")");
            execute("delete from product where seller_id in (" + sellers + ")");
            execute("delete from seller where code like '" + SELLER_PREFIX + "%'");
            execute("delete from audit_log where actor_user_id in (" + users + ")");
            execute("delete from app_user where email like '" + EMAIL_PREFIX + "%'");
        });
    }

    private void execute(String sql) {
        jdbc.sql(sql).update();
    }
}
