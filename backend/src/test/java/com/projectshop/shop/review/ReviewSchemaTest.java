package com.projectshop.shop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.order.OrderFixture;

/**
 * 후기 표가 무엇을 막나(`46`).
 *
 * <p>입구가 아직 없다 — 쓰는 자격은 `47`, 셀러 답글·신고는 `48` 이다. 그래서 이 시험이
 * 재는 것은 <b>표 스스로 막는 것</b>뿐이고, 그것이 이 청크의 닫힘이다.
 *
 * <p>막는 것이 셋이다 — <b>안 산 줄에 못 붙고</b>(외래키), <b>주문 줄과 어긋난 상품·사람이
 * 못 들어오고</b>(트리거), <b>별이 1~5 밖으로 못 나간다</b>(check).
 * 가운데가 제일 값이 크다: 어긋나도 화면에서는 그럴듯해 보인다.
 */
@DisplayName("후기 표가 막는 것")
class ReviewSchemaTest extends PostgresTestBase {

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture auth;
    private long sellerId;
    private long orderItemId;
    private long productId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        auth = new AuthFixture(jdbc);
        sellerId = auth.insertSeller("review-seller", "후기셀러");
        buyerId = auth.insertUser("buyer@example.com", "산사람");

        productId = insertProduct("후기 상품");
        orderItemId = placeOrder(productId);
    }

    /** 주문 사슬을 손으로 세운다. 여기서 관심사는 후기 표뿐이라 껍데기면 된다 */
    private long placeOrder(long product) {
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
                        insert into seller_order (seller_order_number, order_id, seller_id, shipping_fee)
                        values (:number, :orderId, :sellerId, 0)
                        returning seller_order_id
                        """)
                .param("number", OrderFixture.sellerOrderNumber())
                .param("orderId", orderId)
                .param("sellerId", sellerId)
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
                .param("productId", product)
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

    private long insertProduct(String name) {
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

    @Nested
    @DisplayName("들어가는 것")
    class Accepted {

        @Test
        @DisplayName("산 줄에 붙은 후기는 들어간다")
        void 산_줄에_붙은_후기는_들어간다() {
            insert(orderItemId, productId, buyerId, 5, "잘 받았다");

            assertThat(count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Rejected {

        @Test
        @DisplayName("주문 줄과 다른 상품에는 못 붙는다")
        void 주문_줄과_다른_상품에는_못_붙는다() {
            long other = insertProduct("다른 상품");

            assertThatThrownBy(() -> insert(orderItemId, other, buyerId, 5, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("주문 줄과 다르다");
        }

        @Test
        @DisplayName("주문자가 아닌 사람은 못 쓴다")
        void 주문자가_아닌_사람은_못_쓴다() {
            long stranger = auth.insertUser("stranger@example.com", "남");

            assertThatThrownBy(() -> insert(orderItemId, productId, stranger, 5, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageContaining("주문자가 아니다");
        }

        @Test
        @DisplayName("별은 1~5 밖으로 못 나간다")
        void 별은_1에서_5_밖으로_못_나간다() {
            assertThatThrownBy(() -> insert(orderItemId, productId, buyerId, 6, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> insert(orderItemId, productId, buyerId, 0, "잘 받았다"))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("빈 본문은 못 들어간다")
        void 빈_본문은_못_들어간다() {
            assertThatThrownBy(() -> insert(orderItemId, productId, buyerId, 5, ""))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    private void insert(long orderItem, long product, long user, int rating, String body) {
        jdbc.sql("""
                        insert into review (order_item_id, product_id, user_id, rating, body)
                        values (:orderItem, :product, :user, :rating, :body)
                        """)
                .param("orderItem", orderItem)
                .param("product", product)
                .param("user", user)
                .param("rating", rating)
                .param("body", body)
                .update();
    }

    private int count() {
        return jdbc.sql("select count(*) from review where deleted_at is null")
                .query(Integer.class)
                .single();
    }
}
