package com.projectshop.shop.notification;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * <b>진짜로 커밋되는</b> 주문을 만들고 뒤에 지운다(`33a`·`34`).
 *
 * <h2>왜 따로 있나</h2>
 *
 * <p>사건을 소비하는 쪽은 <b>다른 스레드</b>라 테스트 트랜잭션 안의 행을 못 본다. 그래서 이 계열의
 * 시험은 롤백을 끄고({@code NOT_SUPPORTED}) 만든 것을 직접 지운다 — 그 준비와 뒷정리가
 * <b>시험마다 같은 모양</b>이라 여기 모았다. 세 벌째가 생기기 전에 갈랐다.
 *
 * <h2>껍데기까지 채우는 이유</h2>
 *
 * <p>주문 머리만 넣으면 <b>커밋이 안 된다.</b> 「셀러 주문이 없는 주문」과 금액 등식은 커밋할 때
 * 보는 지연 트리거라(`D8`), 롤백하는 시험에서는 한 번도 안 도는 자리다. 그래서 셀러 주문·항목·재고까지
 * 채우고 <b>한 트랜잭션으로 묶는다</b> — 문장마다 커밋되면 주문 머리만 있는 상태에서 먼저 터진다.
 */
class CommittedOrderFixture {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final String prefix;

    private long userId;
    private long sellerId;

    /**
     * @param prefix 이 시험이 만든 것임을 알아보는 표시. 뒷정리가 이것만 지운다
     */
    CommittedOrderFixture(JdbcClient jdbc, PlatformTransactionManager transactionManager, String prefix) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.prefix = prefix;
    }

    /** 사는 사람과 셀러를 만든다. 앞 회차가 남긴 것이 있으면 먼저 걷어낸다 */
    void prepare() {
        cleanUp();
        AuthFixture auth = new AuthFixture(jdbc);
        userId = auth.insertUser(prefix + "buyer@test.local", "받는이");
        sellerId = auth.insertSeller(prefix + "seller", "통지셀러");
    }

    /**
     * 주문과 <b>생성 이력 행</b>을 넣는다. 그 행이 청약 접수 사건을 낳는다(`33a`).
     *
     * <p>{@code OrderService.create} 를 안 부르는 이유는 장바구니·재고까지 딸려 와서
     * 보려는 것(사건 → 통지)에서 멀어져서다.
     *
     * @return 노출 번호. 사건의 {@code subject} 가 이 값이다(`D9`)
     */
    String placeOrder() {
        String orderNumber = OrderFixture.sellerOrderNumber().substring(2);
        transactions.executeWithoutResult(status -> insertOrder(orderNumber));
        return orderNumber;
    }

    /** 이력에 전이 한 줄을 더한다. 주문 층은 결제 상태만 받는다(`V18`) */
    void moveOrder(String orderNumber, String from, String to) {
        jdbc.sql("""
                        insert into order_status_history (order_id, from_status, to_status,
                                                          actor_type, actor_user_id)
                        select order_id, :from, :to, 'customer', :userId
                          from shop_order where order_number = :number
                        """)
                .param("number", orderNumber)
                .param("from", from)
                .param("to", to)
                .param("userId", userId)
                .update();
    }

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
     * <p><b>정리가 실패하면 엉뚱한 시험이 빨개진다</b>(`33a` 실측). 남은 주문을 전체를 세는 시험들이
     * 같이 세서, 원인 클래스는 초록인 채로 무관한 셋이 깨졌다 — 단독 실행으로는 재현이 안 되는 모양이다.
     *
     * <p><b>아웃박스 행도 지운다.</b> 남겨 두면 다음 회차가 남의 시험 사건을 집는다(`Q57` 이력).
     */
    void cleanUp() {
        String buyers = "select user_id from app_user where email like '" + prefix + "%'";
        String orders = "select order_id from shop_order where user_id in (" + buyers + ")";
        String numbers = "select order_number from shop_order where user_id in (" + buyers + ")";
        String sellerOrders = "select seller_order_id from seller_order where order_id in (" + orders + ")";
        String sellers = "select seller_id from seller where code like '" + prefix + "%'";
        String products = "select product_id from product where seller_id in (" + sellers + ")";
        String skus = "select sku_id from sku where product_id in (" + products + ")";

        transactions.executeWithoutResult(status -> {
            // **주문 번호만으로는 모자란다**(마무리 20차 독립 리뷰). 재고 행을 넣으면 `V41` 의 트리거가
            // 이동을 적고 그것이 `shop.sku.stock_moved` 사건을 낳는데, 그 사건의 `subject` 는 `sku_id` 다 —
            // 아래에서 원천(`sku_stock_movement`)을 지우므로 **원천 없는 미발행 사건**이 남는다.
            execute("delete from outbox_event where subject in (" + numbers + ")"
                    + " or subject in (select sku_id::text from sku where product_id in (" + products + "))");
            execute("delete from notification where order_id in (" + orders + ")");
            execute("delete from order_status_history where order_id in (" + orders + ")");
            execute("delete from order_item where seller_order_id in (" + sellerOrders + ")");
            execute("delete from seller_order where order_id in (" + orders + ")");
            execute("delete from shop_order where user_id in (" + buyers + ")");
            execute("delete from sku_stock where sku_id in (" + skus + ")");
            execute("delete from sku_stock_movement where sku_id in (" + skus + ")");
            execute("delete from sku where product_id in (" + products + ")");
            execute("delete from product where seller_id in (" + sellers + ")");
            execute("delete from seller where code like '" + prefix + "%'");
            execute("delete from app_user where email like '" + prefix + "%'");
        });
    }

    private void execute(String sql) {
        jdbc.sql(sql).update();
    }
}
