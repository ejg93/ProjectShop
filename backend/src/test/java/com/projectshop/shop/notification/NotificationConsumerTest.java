package com.projectshop.shop.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.KafkaTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;
import com.projectshop.shop.support.OutboxPublisher;

/**
 * 사건으로 온 거래 통지가 스위퍼와 <b>같은 결과</b>를 내나(`33a`).
 *
 * <h2>여기서 보는 것</h2>
 *
 * <p>즉시 경로(소비자)와 안전망 경로(스위퍼)가 <b>같은 한 행</b>을 남긴다는 것이다.
 * 둘 중 하나만 도는 날이 있고(소비자가 죽거나, 켜져 있지 않거나) 둘 다 도는 날도 있는데,
 * <b>세 경우의 결과가 같아야</b> 스위퍼를 안전망으로 남겨 둔 결정이 성립한다.
 *
 * <h2>롤백이 없다</h2>
 *
 * <p>소비자는 <b>다른 스레드</b>에서 돈다({@code @Transactional(NOT_SUPPORTED)}, `D15`).
 * 테스트가 트랜잭션을 열고 있으면 그 안에서 만든 주문을 소비자가 <b>아예 못 본다</b> —
 * 커밋 전이라 다른 연결에는 없는 행이다. 그래서 롤백을 끄고 만든 것을 직접 지운다.
 */
@DisplayName("거래 통지 소비자")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationConsumerTest extends KafkaTestBase {

    /** 이 테스트가 만든 것임을 알아보는 표시. 정리가 이것만 지운다 */
    private static final String EMAIL_PREFIX = "consumer-notice-";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private NotificationSweeper sweeper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 셀러도 같은 표시를 단다. 정리가 이것으로 찾는다 */
    private static final String SELLER_PREFIX = "consumer-notice-";

    private long userId;
    private long sellerId;

    @BeforeEach
    void setUp() {
        cleanUp();
        AuthFixture fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser(EMAIL_PREFIX + "buyer@test.local", "받는이");
        sellerId = fixture.insertSeller(SELLER_PREFIX + "seller", "통지셀러");
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    @Test
    @DisplayName("발행기가 보낸 청약 접수를 소비자가 통지로 남긴다")
    void consumerLeavesNotice() {
        String orderNumber = insertOrderWithCreatedHistory();

        assertThat(publisher.publishOnce())
                .as("생성 이력 행 하나가 사건 하나를 낳는다")
                .isGreaterThanOrEqualTo(1);

        assertThat(awaitNotices(orderNumber))
                .as("소비자가 사건을 받아 그 자리에서 남긴다 — 스위퍼를 안 기다린다")
                .containsExactly("order_placed");

        // **안전망이 같은 건을 또 잡아도 한 행이다.** 막는 것은 `notification` 의 부분 유니크고(`54a`)
        // 그래서 못 보낸 편지를 모으는 큐를 안 둔다(`event-catalog.md` 「지금 안 하는 것」).
        sweeper.sweepAll(OffsetDateTime.now());
        assertThat(noticesFor(orderNumber)).containsExactly("order_placed");
    }

    @Test
    @DisplayName("소비자가 못 받아도 스위퍼가 같은 결과를 낸다")
    void sweeperLeavesTheSameNotice() {
        // 발행기를 안 부른다. 사건은 표에 남아 있고 아무도 안 가져가는 상태다 —
        // 소비자가 죽어 있는 동안이 이 모습이다(회차는 `KafkaTestBase` 가 한 시간으로 밀어 뒀다).
        String orderNumber = insertOrderWithCreatedHistory();

        sweeper.sweepAll(OffsetDateTime.now());

        assertThat(noticesFor(orderNumber))
                .as("두 경로가 같은 결과를 낸다 — 그래서 스위퍼를 안전망으로 남긴다")
                .containsExactly("order_placed");
    }

    /** 소비자는 다른 스레드라 바로 안 보인다. 나타날 때까지 본다 */
    private List<String> awaitNotices(String orderNumber) {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        List<String> notices = noticesFor(orderNumber);
        while (notices.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            notices = noticesFor(orderNumber);
        }
        return notices;
    }

    private List<String> noticesFor(String orderNumber) {
        return jdbc.sql("""
                        select n.event_type
                          from notification n
                          join shop_order o on o.order_id = n.order_id
                         where o.order_number = :number
                         order by n.notification_id
                        """)
                .param("number", orderNumber)
                .query(String.class)
                .list();
    }

    /**
     * 주문과 <b>생성 이력 행</b>을 넣는다. 그 행이 사건을 낳는다(`33a` 가 {@code OrderService} 에 더한 것).
     *
     * <p>{@code OrderService.create} 를 안 부르는 이유는 장바구니·재고까지 딸려 와서
     * 이 시험이 보려는 것(사건 → 통지)에서 멀어져서다.
     *
     * <p><b>셀러 주문과 항목까지 채운다.</b> 「셀러 주문이 없는 주문」을 막는 트리거가 <b>커밋할 때</b>
     * 본다 — 롤백하는 시험에서는 한 번도 안 도는 자리라 여기서 처음 걸렸다.
     */
    private String insertOrderWithCreatedHistory() {
        String orderNumber = OrderFixture.sellerOrderNumber().substring(2);
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> insertOrder(orderNumber));
        return orderNumber;
    }

    /**
     * <b>한 트랜잭션으로 묶는다.</b> 롤백이 없어서 문장마다 커밋되는데, 「셀러 주문이 없는 주문」과
     * 금액 등식은 <b>커밋할 때</b> 보는 지연 트리거라 주문 머리만 넣은 상태에서 먼저 터진다.
     */
    private void insertOrder(String orderNumber) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 0, 10000)
                        returning order_id
                        """)
                .param("number", orderNumber)
                .param("userId", userId)
                .query(Long.class)
                .single();
        OrderFixture.attachContractDocuments(jdbc, orderId);
        insertSellerOrder(orderId);

        jdbc.sql("""
                        insert into order_status_history (order_id, from_status, to_status,
                                                          actor_type, actor_user_id)
                        values (:orderId, null, 'payment_pending', 'customer', :userId)
                        """)
                .param("orderId", orderId)
                .param("userId", userId)
                .update();
    }

    /** 금액 등식과 「셀러 주문이 없는 주문」을 커밋이 본다. 껍데기라도 채워야 커밋이 된다 */
    private void insertSellerOrder(long orderId) {
        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .query(Long.class)
                .single();

        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, '통지 상품')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .query(Long.class)
                .single();
        // 재고 행이 없는 sku 를 커밋이 막는다(`V41`). 여기서 파는 것이 관심사가 아니라 껍데기다.
        long skuId = jdbc.sql("""
                        with new_sku as (
                            insert into sku (product_id, price_incl_vat) values (:productId, 10000)
                            returning sku_id
                        )
                        insert into sku_stock (sku_id, on_hand)
                        select sku_id, 10 from new_sku
                        returning sku_id
                        """)
                .param("productId", productId)
                .query(Long.class)
                .single();

        jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '통지 상품', 10000, 1, 10000, 1000, 1000)
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .update();
    }

    /**
     * 만든 것을 자식부터 지운다. 참조가 {@code restrict} 라 순서가 틀리면 정리가 실패한다.
     *
     * <p><b>아웃박스 행도 지운다.</b> 남겨 두면 다음 회차가 남의 시험 사건을 집어서
     * 그쪽 단언이 남의 것을 센다(`Q57` 이력 — 롤백을 안 하는 시험이 이 표에 흔적을 남긴다).
     */
    private void cleanUp() {
        String buyers = "select user_id from app_user where email like '" + EMAIL_PREFIX + "%'";
        String orders = "select order_id from shop_order where user_id in (" + buyers + ")";
        String numbers = "select order_number from shop_order where user_id in (" + buyers + ")";
        String sellerOrders = "select seller_order_id from seller_order where order_id in (" + orders + ")";
        String sellers = "select seller_id from seller where code like '" + SELLER_PREFIX + "%'";
        String products = "select product_id from product where seller_id in (" + sellers + ")";

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            execute("delete from outbox_event where subject in (" + numbers + ")");
            execute("delete from notification where order_id in (" + orders + ")");
            execute("delete from order_status_history where order_id in (" + orders + ")");
            execute("delete from order_item where seller_order_id in (" + sellerOrders + ")");
            execute("delete from seller_order where order_id in (" + orders + ")");
            execute("delete from shop_order where user_id in (" + buyers + ")");
            execute("delete from sku_stock where sku_id in (select sku_id from sku where product_id in ("
                    + products + "))");
            execute("delete from sku_stock_movement where sku_id in (select sku_id from sku"
                    + " where product_id in (" + products + "))");
            execute("delete from sku where product_id in (" + products + ")");
            execute("delete from sku where product_id in (" + products + ")");
            execute("delete from product where seller_id in (" + sellers + ")");
            execute("delete from seller where code like '" + SELLER_PREFIX + "%'");
            execute("delete from app_user where email like '" + EMAIL_PREFIX + "%'");
        });
    }

    private void execute(String sql) {
        jdbc.sql(sql).update();
    }
}
