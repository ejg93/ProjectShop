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

/**
 * 장바구니에서 고른 것이 주문으로 굳는가.
 *
 * <p>금액 등식 자체는 {@code OrderSchemaTest} 가 제약으로 확인한다. 여기서는
 * <b>서비스가 그 등식을 맞춰서 넣는지</b>와 박제·재고·셀러 분할이 실제로 도는지를 본다.
 */
@DisplayName("주문 생성")
class OrderServiceTest extends PostgresTestBase {

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long userId;
    private long sellerA;
    private long sellerB;
    private long skuA;
    private long skuB;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("order-buyer@test.local", "구매자");

        sellerA = sellerWith("s-a", "셀러가", 1000, 3_000L);
        sellerB = sellerWith("s-b", "셀러나", 500, 5_000L);

        skuA = skuOf(sellerA, "가게상품", 10_000L, 100);
        skuB = skuOf(sellerB, "나게상품", 20_000L, 100);
    }

    @Nested
    @DisplayName("주문 시점의 값을")
    class Snapshot {

        @Test
        @DisplayName("항목에 박제한다")
        void copiesPriceAndName() {
            long cartItemId = addToCart(skuA, 2);

            OrderService.Created created = order(cartItemId);

            jdbc.sql("update sku set price_incl_vat = 99_999 where sku_id = :id").param("id", skuA).update();

            assertThat(itemOf(created.orderId(), "unit_price_incl_vat"))
                    .as("가격을 조인해 오면 셀러가 값을 바꿀 때 과거 주문 금액이 같이 바뀐다")
                    .isEqualTo(10_000L);
        }

        @Test
        @DisplayName("수수료율은 상품 것이 있으면 그것, 없으면 셀러 기본값이다")
        void snapshotsCommissionRate() {
            long cartItemId = addToCart(skuB, 1);

            OrderService.Created created = order(cartItemId);

            assertThat(itemOf(created.orderId(), "commission_bp"))
                    .as("`D3` 가 상품에서 덮어쓸 수 있게 정했다")
                    .isEqualTo(500L);
        }

        @Test
        @DisplayName("수수료는 항목마다 자르고 원 미만을 버린다")
        void truncatesCommissionPerItem() {
            long cartItemId = addToCart(skuB, 1);

            OrderService.Created created = order(cartItemId);

            // 20,000 × 5% = 1,000
            assertThat(itemOf(created.orderId(), "commission_amount")).isEqualTo(1_000L);
        }
    }

    @Nested
    @DisplayName("금액은")
    class Amounts {

        @Test
        @DisplayName("항목 합 + 배송비다")
        void sumsItemsAndShipping() {
            OrderService.Created created = order(addToCart(skuA, 2));

            // 10,000 × 2 + 배송비 3,000
            assertThat(created.payableAmount()).isEqualTo(23_000L);
        }

        @Test
        @DisplayName("배송비는 셀러마다 한 번씩 붙는다")
        void chargesShippingPerSeller() {
            long a = addToCart(skuA, 1);
            long b = addToCart(skuB, 1);

            OrderService.Created created = orderService.create(userId, command(List.of(a, b)));

            // 10,000 + 20,000 + (3,000 + 5,000)
            assertThat(created.payableAmount())
                    .as("배송은 셀러가 하므로 배송비도 셀러별이다(`D3`)")
                    .isEqualTo(38_000L);
        }
    }

    @Nested
    @DisplayName("셀러가 여럿이면")
    class MultiSeller {

        @Test
        @DisplayName("셀러 주문으로 쪼갠다")
        void splitsPerSeller() {
            long a = addToCart(skuA, 1);
            long b = addToCart(skuB, 1);

            OrderService.Created created = orderService.create(userId, command(List.of(a, b)));

            assertThat(countOf("select count(*) from seller_order where order_id = " + created.orderId()))
                    .as("결제는 주문 단위, 배송은 셀러 단위다(`D7`)")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("재고는")
    class Stock {

        @Test
        @DisplayName("주문한 만큼 줄어든다")
        void decreases() {
            order(addToCart(skuA, 3));

            assertThat(stockOf(skuA)).isEqualTo(97);
        }

        @Test
        @DisplayName("모자라면 주문이 안 된다")
        void rejectsWhenInsufficient() {
            jdbc.sql("update sku set stock_count = 1 where sku_id = :id").param("id", skuA).update();
            long cartItemId = addToCart(skuA, 2);

            assertThatThrownBy(() -> order(cartItemId))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.OUT_OF_STOCK));
        }
    }

    @Nested
    @DisplayName("장바구니는")
    class Cart {

        @Test
        @DisplayName("주문한 것만 빠진다")
        void keepsUnorderedItems() {
            long ordered = addToCart(skuA, 1);
            addToCart(skuB, 1);

            orderService.create(userId, command(List.of(ordered)));

            assertThat(countOf("select count(*) from cart_item"))
                    .as("고르지 않은 것은 담긴 채로 남는다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("살 수 없는 것이 섞이면 통째로 거절한다")
        void rejectsUnbuyableItem() {
            long cartItemId = addToCart(skuA, 1);
            jdbc.sql("update sku set status = 'suspended' where sku_id = :id").param("id", skuA).update();

            assertThatThrownBy(() -> order(cartItemId))
                    .as("조용히 빼면 사려던 것과 산 것이 달라진다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SKU_NOT_BUYABLE));
        }
    }

    @Nested
    @DisplayName("배송지는")
    class Shipping {

        @Test
        @DisplayName("주문이 아니라 따로 저장된다")
        void goesToItsOwnTable() {
            OrderService.Created created = order(addToCart(skuA, 1));

            assertThat(countOf("select count(*) from order_shipping where order_id = " + created.orderId()))
                    .as("주문에 사람 정보를 박제하지 않는다(`D13`)")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("주문번호는")
    class OrderNumber {

        @Test
        @DisplayName("날짜 + 난수 형식이다")
        void hasDateAndRandomPart() {
            OrderService.Created created = order(addToCart(skuA, 1));

            assertThat(created.orderNumber())
                    .as("순번을 노출하면 총량과 증가 속도가 샌다(`D9`)")
                    .matches("^[0-9]{8}-[2-9A-HJ-NP-Z]{6}$");
        }
    }

    private OrderService.Created order(long cartItemId) {
        return orderService.create(userId, command(List.of(cartItemId)));
    }

    private OrderService.Command command(List<Long> cartItemIds) {
        return new OrderService.Command(cartItemIds,
                new OrderService.Shipping("홍길동", "010-0000-0000", "06134", "서울시 강남구", "101호", null));
    }

    private long sellerWith(String code, String name, int commissionBp, long shippingFee) {
        long sellerId = fixture.insertSeller(code, name);
        fixture.verifySeller(sellerId);
        jdbc.sql("""
                        update seller set commission_bp = :bp, default_shipping_fee = :fee
                         where seller_id = :id
                        """)
                .param("bp", commissionBp)
                .param("fee", shippingFee)
                .param("id", sellerId)
                .update();
        return sellerId;
    }

    private long skuOf(long sellerId, String name, long priceInclVat, int stock) {
        long productId = jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name, status)
                        values (:sellerId, :userId, :name, 'on_sale')
                        returning product_id
                        """)
                .param("sellerId", sellerId)
                .param("userId", userId)
                .param("name", name)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into sku (product_id, price_incl_vat, stock_count)
                        values (:productId, :priceInclVat, :stock)
                        returning sku_id
                        """)
                .param("productId", productId)
                .param("priceInclVat", priceInclVat)
                .param("stock", stock)
                .query(Long.class)
                .single();
    }

    private long addToCart(long skuId, int quantity) {
        long cartId = jdbc.sql("""
                        insert into cart (user_id) values (:userId)
                        on conflict (user_id) where user_id is not null do update set updated_at = now()
                        returning cart_id
                        """)
                .param("userId", userId)
                .query(Long.class)
                .single();

        return jdbc.sql("""
                        insert into cart_item (cart_id, sku_id, quantity)
                        values (:cartId, :skuId, :quantity)
                        returning cart_item_id
                        """)
                .param("cartId", cartId)
                .param("skuId", skuId)
                .param("quantity", quantity)
                .query(Long.class)
                .single();
    }

    private long itemOf(long orderId, String column) {
        return jdbc.sql("""
                        select oi.%s from order_item oi
                          join seller_order so on so.seller_order_id = oi.seller_order_id
                         where so.order_id = :orderId
                        """.formatted(column))
                .param("orderId", orderId)
                .query(Long.class)
                .single();
    }

    private int stockOf(long skuId) {
        return jdbc.sql("select stock_count from sku where sku_id = :id")
                .param("id", skuId)
                .query(Integer.class)
                .single();
    }

    private int countOf(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }
}
