package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 상품 등록·수정(`7`).
 *
 * <p><b>{@code scope=seller} 가 실제 자원에 처음 걸리는 자리다.</b>
 * 지금까지 판정은 계정(`own`)과 감사 로그(`all`)에만 쓰였고 셀러 축은 대상이 없어서 안 밟혔다.
 */
@DisplayName("상품 등록·수정")
class ProductServiceTest extends PostgresTestBase {

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long sellerA;
    private long sellerB;
    private long ownerA;
    private long ownerB;
    private long customer;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        sellerA = fixture.insertSeller("p-a", "A셀러");
        sellerB = fixture.insertSeller("p-b", "B셀러");

        ownerA = fixture.insertUser("p-owner-a@test.local", "A사장");
        fixture.joinSeller(sellerA, ownerA);
        fixture.grantOrg(ownerA, "seller_owner", sellerA);

        ownerB = fixture.insertUser("p-owner-b@test.local", "B사장");
        fixture.joinSeller(sellerB, ownerB);
        fixture.grantOrg(ownerB, "seller_owner", sellerB);

        customer = fixture.insertUser("p-customer@test.local", "고객");
        fixture.grantGlobal(customer, "customer");
    }

    @Nested
    @DisplayName("등록하면")
    class Creating {

        @Test
        @DisplayName("상품·옵션·SKU 가 한 번에 들어간다")
        void writesEverythingAtOnce() {
            ProductService.Created created = productService.create(ownerA, tshirt(sellerA));

            assertThat(created.skuIds()).hasSize(2);
            assertThat(count("product_option", created.productId())).isEqualTo(1);
            assertThat(countValues(created.productId())).isEqualTo(2);
        }

        @Test
        @DisplayName("등록자가 남는다 — 소유자가 아니라 담당자다")
        void recordsWhoCreatedIt() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();

            assertThat(jdbc.sql("select created_by_user_id from product where product_id = :id")
                            .param("id", productId).query(Long.class).single())
                    .as("scope=own 이 이 값을 본다. 없으면 담당자 축을 표현할 자리가 없다")
                    .isEqualTo(ownerA);
        }

        @Test
        @DisplayName("항상 draft 다 — 노출은 검수가 정한다")
        void startsAsDraft() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();

            assertThat(statusOf(productId)).isEqualTo("draft");
        }

        @Test
        @DisplayName("심사 대기 중인 셀러도 등록할 수 있다")
        void pendingSellerCanRegister() {
            // 법이 막는 것은 청약이지 준비가 아니다(전자상거래법 제20조②).
            // draft 는 공개 목록에 안 나와서 청약이 일어날 수 없다.
            assertThat(sellerStatusOf(sellerA)).isEqualTo("pending");

            assertThat(productService.create(ownerA, tshirt(sellerA)).productId()).isPositive();
        }
    }

    @Nested
    @DisplayName("셀러 경계")
    class SellerBoundary {

        @Test
        @DisplayName("남의 셀러로는 등록할 수 없다")
        void cannotCreateForAnotherSeller() {
            assertThatThrownBy(() -> productService.create(ownerA, tshirt(sellerB)))
                    .as("조직 역할은 받은 그 셀러만 덮는다. 안 그러면 A 사장이 B 상품을 만든다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_FORBIDDEN));
        }

        @Test
        @DisplayName("남의 상품은 고칠 수 없다")
        void cannotUpdateAnothersProduct() {
            long productId = productService.create(ownerB, tshirt(sellerB)).productId();

            assertThatThrownBy(() -> productService.replace(ownerA, productId, tshirt(sellerB)))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("남의 상품은 내릴 수 없다")
        void cannotDeleteAnothersProduct() {
            long productId = productService.create(ownerB, tshirt(sellerB)).productId();

            assertThatThrownBy(() -> productService.delete(ownerA, productId))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("고객은 등록할 수 없다")
        void customerCannotCreate() {
            assertThatThrownBy(() -> productService.create(customer, tshirt(sellerA)))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("거부가 403 이다 — 404 로 감추지 않는다")
        void forbiddenNotNotFound() {
            assertThatThrownBy(() -> productService.create(customer, tshirt(sellerA)))
                    .as("상품은 공개 목록에 있어서 존재를 숨길 이유가 없다(`D5`)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code().status()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    @Nested
    @DisplayName("조합 검증")
    class SkuValidation {

        @Test
        @DisplayName("SKU 가 없으면 못 만든다")
        void rejectsProductWithoutSku() {
            ProductService.Command command = new ProductService.Command(
                    sellerA, "빈 상품", null, null, false, null, null,
                    List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                    List.of());

            assertThatThrownBy(() -> productService.create(ownerA, command))
                    .as("옵션만 있고 조합이 없으면 팔 수 없는 반쪽이다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_WITHOUT_SKU));
        }

        @Test
        @DisplayName("선언 안 한 옵션값으로 조합을 만들 수 없다")
        void rejectsUndeclaredOptionValue() {
            ProductService.Command command = new ProductService.Command(
                    sellerA, "티셔츠", null, null, false, null, null,
                    List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                    List.of(new ProductService.SkuCommand(List.of("빨강"), 15000, 1)));

            // 외래키는 "그 선택지가 존재하나" 까지고 "이 상품의 것인가" 는 안 본다.
            assertThatThrownBy(() -> productService.create(ownerA, command))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SKU_OPTION_MISMATCH));
        }

        @Test
        @DisplayName("옵션 축 수와 조합 길이가 다르면 못 만든다")
        void rejectsWrongCombinationLength() {
            ProductService.Command command = new ProductService.Command(
                    sellerA, "티셔츠", null, null, false, null, null,
                    List.of(new ProductService.OptionCommand("색상", List.of("검정")),
                            new ProductService.OptionCommand("사이즈", List.of("M"))),
                    List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 1)));

            assertThatThrownBy(() -> productService.create(ownerA, command))
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("수정하면")
    class Replacing {

        @Test
        @DisplayName("옵션과 SKU 가 통째로 갈린다")
        void replacesStructureWholesale() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();

            ProductService.Command changed = new ProductService.Command(
                    sellerA, "티셔츠 개정", null, null, false, null, null,
                    List.of(new ProductService.OptionCommand("사이즈", List.of("M", "L", "XL"))),
                    List.of(new ProductService.SkuCommand(List.of("M"), 17000, 5),
                            new ProductService.SkuCommand(List.of("L"), 17000, 5),
                            new ProductService.SkuCommand(List.of("XL"), 18000, 5)));

            productService.replace(ownerA, productId, changed);

            assertThat(count("product_option", productId)).isEqualTo(1);
            assertThat(countValues(productId)).isEqualTo(3);
            assertThat(count("sku", productId))
                    .as("부분 수정을 열면 어느 조합이 사라지나를 클라이언트가 계산해야 한다")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("없는 상품은 404 다")
        void unknownProductIsNotFound() {
            assertThatThrownBy(() -> productService.replace(ownerA, -1L, tshirt(sellerA)))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("주문에 쓰인 상품은")
    class OrderedProduct {

        @Test
        @DisplayName("그 SKU 가 지워지지 않고 판매중지로 내려간다")
        void retiresOrderedSkuInsteadOfDeleting() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();
            long orderedSkuId = anySkuOf(productId);
            placeOrderOn(orderedSkuId);

            productService.replace(ownerA, productId, tshirt(sellerA));

            assertThat(jdbc.sql("""
                            select status from sku where sku_id = :id and deleted_at is not null
                            """).param("id", orderedSkuId).query(String.class).optional())
                    .as("`order_item.sku_id` 가 restrict 라 지우려 들면 상품 수정이 통째로 막힌다")
                    .contains("suspended");
        }

        @Test
        @DisplayName("가격과 재고는 그대로 바꿀 수 있다")
        void allowsPriceAndStockChange() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();
            placeOrderOn(anySkuOf(productId));

            ProductService.Command repriced = new ProductService.Command(
                    sellerA, "티셔츠", null, null, false, null, null,
                    List.of(new ProductService.OptionCommand("색상", List.of("검정", "흰색"))),
                    List.of(new ProductService.SkuCommand(List.of("검정"), 19000, 3),
                            new ProductService.SkuCommand(List.of("흰색"), 19000, 3)));

            productService.replace(ownerA, productId, repriced);

            assertThat(jdbc.sql("""
                            select count(*) from sku
                             where product_id = :id and deleted_at is null and price_incl_vat = 19000
                            """).param("id", productId).query(Long.class).single())
                    .as("막는 것은 옵션 축이지 값이 아니다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("옵션 축을 바꾸면 422 다")
        void locksOptionAxis() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();
            placeOrderOn(anySkuOf(productId));

            ProductService.Command differentAxis = new ProductService.Command(
                    sellerA, "티셔츠", null, null, false, null, null,
                    List.of(new ProductService.OptionCommand("사이즈", List.of("M", "L"))),
                    List.of(new ProductService.SkuCommand(List.of("M"), 15000, 5),
                            new ProductService.SkuCommand(List.of("L"), 15000, 5)));

            assertThatThrownBy(() -> productService.replace(ownerA, productId, differentAxis))
                    .as("바꾸면 지나간 주문의 옵션 라벨이 가리키던 것이 사라진다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_OPTIONS_LOCKED));
        }

        private long anySkuOf(long productId) {
            return jdbc.sql("select sku_id from sku where product_id = :id order by sku_id limit 1")
                    .param("id", productId)
                    .query(Long.class)
                    .single();
        }

        /** 이 SKU 를 가리키는 주문을 하나 만든다. 금액 등식을 맞춰야 커밋이 통과한다 */
        private void placeOrderOn(long skuId) {
            long buyerId = new AuthFixture(jdbc).insertUser("ordered-buyer@test.local", "구매자");

            long orderId = jdbc.sql("""
                            insert into shop_order (order_number, user_id, total_amount,
                                                    commission_total, shipping_fee_total, payable_amount)
                            values ('20260809-7QX4M7', :userId, 15000, 1500, 0, 15000)
                            returning order_id
                            """)
                    .param("userId", buyerId)
                    .query(Long.class)
                    .single();

            long sellerOrderId = jdbc.sql("""
                            insert into seller_order (seller_order_number, order_id, seller_id,
                                                      shipping_fee)
                            values (:number, :orderId, :sellerId, 0)
                            returning seller_order_id
                            """)
                    .param("number", com.projectshop.shop.order.OrderFixture.sellerOrderNumber())
                    .param("orderId", orderId)
                    .param("sellerId", sellerA)
                    .query(Long.class)
                    .single();

            jdbc.sql("""
                            insert into order_item (seller_order_id, sku_id, product_name,
                                                    unit_price_incl_vat, quantity, line_amount,
                                                    commission_bp, commission_amount)
                            values (:sellerOrderId, :skuId, '티셔츠', 15000, 1, 15000, 1000, 1500)
                            """)
                    .param("sellerOrderId", sellerOrderId)
                    .param("skuId", skuId)
                    .update();
        }
    }

    @Nested
    @DisplayName("내리면")
    class Deleting {

        @Test
        @DisplayName("행은 남고 수명만 끊긴다")
        void keepsRow() {
            long productId = productService.create(ownerA, tshirt(sellerA)).productId();

            productService.delete(ownerA, productId);

            assertThat(jdbc.sql("select count(*) from product where product_id = :id")
                            .param("id", productId).query(Long.class).single())
                    .as("과거 주문이 이 상품을 가리킨다(`D13`)")
                    .isEqualTo(1);
            assertThat(jdbc.sql("select deleted_at from product where product_id = :id")
                            .param("id", productId).query(java.time.OffsetDateTime.class).single())
                    .isNotNull();
        }
    }

    /** 색상 두 가지짜리 티셔츠. 대부분의 테스트가 이걸 쓴다 */
    private static ProductService.Command tshirt(long sellerId) {
        return new ProductService.Command(
                sellerId, "티셔츠", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정", "흰색"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10),
                        new ProductService.SkuCommand(List.of("흰색"), 15000, 7)));
    }

    private long count(String table, long productId) {
        return jdbc.sql("select count(*) from " + table + " where product_id = :id")
                .param("id", productId)
                .query(Long.class)
                .single();
    }

    private long countValues(long productId) {
        return jdbc.sql("""
                        select count(*) from product_option_value v
                          join product_option o on o.product_option_id = v.product_option_id
                         where o.product_id = :id
                        """)
                .param("id", productId)
                .query(Long.class)
                .single();
    }

    private String statusOf(long productId) {
        return jdbc.sql("select status from product where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .single();
    }

    private String sellerStatusOf(long sellerId) {
        return jdbc.sql("select status from seller where seller_id = :id")
                .param("id", sellerId)
                .query(String.class)
                .single();
    }
}
