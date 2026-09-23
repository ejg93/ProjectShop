package com.projectshop.shop.review;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 후기 시험이 쓰는 주문 사슬.
 *
 * <p><b>파일마다 이 SQL 을 베끼지 않는다.</b> 사슬이 넷이라(`shop_order` → `seller_order` →
 * `sku` → `order_item`) 베낀 수만큼 고쳐야 하고, 하나를 빠뜨리면 그 시험만 원인과 무관해
 * 보이는 곳에서 깨진다({@code OrderFixture.attachContractDocuments} 와 같은 판단).
 */
class ReviewFixture {

    private final JdbcClient jdbc;
    private final AuthFixture auth;

    private final long sellerId;
    private final long buyerId;
    private final long sellerMemberId;

    ReviewFixture(JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.auth = new AuthFixture(jdbc);
        this.sellerId = auth.insertSeller("review-seller", "후기셀러");
        this.buyerId = auth.insertUser("buyer@example.com", "산사람");
        // 손으로 넣은 계정에는 기본 역할이 없다 — `SignupService` 가 주는 것이라,
        // 안 주면 후기 입구의 판정이 거부한다(`Q160`).
        auth.grantGlobal(buyerId, "customer");

        this.sellerMemberId = auth.insertUser("seller@example.com", "파는이");
        auth.joinSeller(sellerId, sellerMemberId);
    }

    long buyerId() {
        return buyerId;
    }

    /** 후기가 달린 상품을 파는 셀러 */
    long sellerId() {
        return sellerId;
    }

    /** 이 상품을 파는 셀러에 속한 계정. 답글을 달 수 있는 쪽이다(`48`) */
    long sellerMemberId() {
        return sellerMemberId;
    }

    long insertUser(String email) {
        long userId = auth.insertUser(email, "남");
        auth.grantGlobal(userId, "customer");
        return userId;
    }

    long insertProduct(String name) {
        return jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, :name)
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", buyerId)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    /**
     * 주문 사슬을 손으로 세운다. 여기서 관심사는 후기 표뿐이라 껍데기면 된다.
     *
     * <p><b>배송 상태를 넣을 때 정한다.</b> 전이로 밀지 않는다 — {@code OrderTransitions} 가
     * 무엇을 허용하나는 이 시험의 관심사가 아니고, 밀면 그 규칙이 바뀔 때 여기가 같이 깨진다.
     *
     * @return {@code order_item_id}
     */
    long placeOrder(long productId, String shipmentStatus) {
        long orderId = jdbc.sql("""
                        insert into shop_order (order_number, user_id, total_amount,
                                                commission_total, shipping_fee_total, payable_amount)
                        values (:number, :userId, 10000, 1000, 0, 10000)
                        returning order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber().substring(2))
                .param("userId", buyerId)
                .query(Long.class)
                .single();
        // `V31` 이 서면 없는 주문을 막는다(전자상거래법 제13조제2항 후단).
        OrderFixture.attachContractDocuments(jdbc, orderId);

        long sellerOrderId = jdbc.sql("""
                        insert into seller_order (seller_order_number, order_id, seller_id,
                                                  shipping_fee, status)
                        values (:number, :orderId, :sellerId, 0, :status)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
                .param("status", shipmentStatus)
                .query(Long.class)
                .single();

        // 재고 행이 없는 sku 를 커밋이 막는다(`V41`).
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

        return jdbc.sql("""
                        insert into order_item (seller_order_id, sku_id, product_name,
                                                unit_price_incl_vat, quantity, line_amount,
                                                commission_bp, commission_amount)
                        values (:sellerOrderId, :skuId, '후기 상품', 10000, 1, 10000, 1000, 1000)
                        returning order_item_id
                        """)
                .param("sellerOrderId", sellerOrderId)
                .param("skuId", skuId)
                .query(Long.class)
                .single();
    }

    void insertReview(long orderItemId, long productId, long userId, int rating, String body) {
        jdbc.sql("""
                        insert into review (order_item_id, product_id, user_id, rating, body)
                        values (:orderItem, :product, :user, :rating, :body)
                        """)
                .param("orderItem", orderItemId)
                .param("product", productId)
                .param("user", userId)
                .param("rating", rating)
                .param("body", body)
                .update();
    }
}
