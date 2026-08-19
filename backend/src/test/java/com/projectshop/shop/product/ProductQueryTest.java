package com.projectshop.shop.product;

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
 * 상품 조회(`8`). <b>「알려진 구멍 3」(목록 스코프 누출)을 밟는 자리다.</b>
 *
 * <p>행 하나 판정과 다르다. 목록은 대상이 없어서 {@code decide} 를 못 부르고
 * <b>어느 행이 대상인지를 조건이 정한다</b> — 조건이 곧 판정이라 틀리면 남의 것이 섞인다.
 * `4c` 매트릭스도 `7` 도 이 축을 안 봤다.
 */
@DisplayName("상품 조회")
class ProductQueryTest extends PostgresTestBase {

    @Autowired
    private ProductQuery productQuery;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcClient jdbc;

    private long sellerA;
    private long sellerB;
    private long ownerA;
    private long ownerB;
    private long admin;
    private long customer;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        sellerA = fixture.insertSeller("q-a", "A셀러");
        sellerB = fixture.insertSeller("q-b", "B셀러");

        // 상품을 파는 상태로 만들려면 셀러가 확인돼 있어야 한다(`7c` 트리거).
        fixture.verifySeller(sellerA);
        fixture.verifySeller(sellerB);

        ownerA = fixture.insertUser("q-owner-a@test.local", "A사장");
        fixture.joinSeller(sellerA, ownerA);
        fixture.grantOrg(ownerA, "seller_owner", sellerA);

        ownerB = fixture.insertUser("q-owner-b@test.local", "B사장");
        fixture.joinSeller(sellerB, ownerB);
        fixture.grantOrg(ownerB, "seller_owner", sellerB);

        admin = fixture.insertUser("q-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");

        customer = fixture.insertUser("q-customer@test.local", "고객");
        fixture.grantGlobal(customer, "customer");
    }

    @Nested
    @DisplayName("공개 목록")
    class PublicList {

        @Test
        @DisplayName("파는 중인 것만 나온다 — draft 는 안 샌다")
        void showsOnlyOnSale() {
            long onSale = createAndPutOnSale(ownerA, sellerA, "파는 티셔츠");
            long draft = create(ownerA, sellerA, "준비 중인 티셔츠");

            List<Long> ids = productQuery.findPublic(null, null, 0, 20).items().stream()
                    .map(ProductQuery.PublicItem::productId)
                    .toList();

            assertThat(ids).contains(onSale);
            assertThat(ids)
                    .as("공개 목록에 draft 가 섞이면 팔지도 않는 것이 노출된다")
                    .doesNotContain(draft);
        }

        @Test
        @DisplayName("내린 상품은 안 나온다")
        void hidesDeleted() {
            long productId = createAndPutOnSale(ownerA, sellerA, "곧 내릴 티셔츠");
            productService.delete(ownerA, productId);

            assertThat(publicIds()).doesNotContain(productId);
        }

        @Test
        @DisplayName("최저가가 같이 온다")
        void includesMinPrice() {
            long productId = createAndPutOnSale(ownerA, sellerA, "가격 여럿");

            ProductQuery.PublicItem item = productQuery.findPublic(sellerA, null, 0, 20).items()
                    .stream()
                    .filter(i -> i.productId() == productId)
                    .findFirst()
                    .orElseThrow();

            assertThat(item.minPriceInclVat())
                    .as("가격은 sku 에 있어서 상품당 여럿이다. 목록은 하나를 골라 보여줘야 한다")
                    .isEqualTo(15000);
        }

        @Test
        @DisplayName("셀러로 좁힌다")
        void filtersBySeller() {
            createAndPutOnSale(ownerA, sellerA, "A 상품");
            createAndPutOnSale(ownerB, sellerB, "B 상품");

            assertThat(productQuery.findPublic(sellerA, null, 0, 20).items())
                    .allSatisfy(item -> assertThat(item.sellerId()).isEqualTo(sellerA));
        }
    }

    @Nested
    @DisplayName("셀러 목록")
    class SellerList {

        @Test
        @DisplayName("자기 셀러의 draft 는 보인다")
        void ownerSeesOwnDraft() {
            long draft = create(ownerA, sellerA, "A 준비 중");

            assertThat(sellerIds(ownerA))
                    .as("팔기 전 상태를 못 보면 셀러가 자기 상품을 관리할 방법이 없다")
                    .contains(draft);
        }

        @Test
        @DisplayName("남의 셀러 상품은 안 보인다 — 목록 스코프 누출")
        void ownerDoesNotSeeOthers() {
            create(ownerA, sellerA, "A 상품");
            long bProduct = create(ownerB, sellerB, "B 상품");

            assertThat(sellerIds(ownerA))
                    .as("조건이 곧 판정이다. 틀리면 남의 상품이 목록에 섞인다")
                    .doesNotContain(bProduct);
        }

        @Test
        @DisplayName("관리자는 전부 본다")
        void adminSeesEverything() {
            long a = create(ownerA, sellerA, "A 상품");
            long b = create(ownerB, sellerB, "B 상품");

            assertThat(sellerIds(admin))
                    .as("관리자는 셀러 소속이 없다. 소속 목록을 그대로 조건에 넣으면 아무것도 안 나온다")
                    .contains(a, b);
        }

        @Test
        @DisplayName("고객은 거부된다 — 0건이 아니다")
        void customerIsForbidden() {
            create(ownerA, sellerA, "A 상품");

            assertThatThrownBy(() -> productQuery.findForSeller(customer, null, null, 0, 20))
                    .as("0건과 못 봄이 갈려야 개수로 정보가 새지 않는다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_FORBIDDEN));
        }

        @Test
        @DisplayName("재고와 수수료율이 같이 온다 — 공개 목록에는 없는 것")
        void includesSellerOnlyFields() {
            long productId = create(ownerA, sellerA, "A 상품");

            ProductQuery.SellerItem item = productQuery.findForSeller(ownerA, sellerA, null, 0, 20)
                    .items().stream()
                    .filter(i -> i.productId() == productId)
                    .findFirst()
                    .orElseThrow();

            assertThat(item.totalStock()).isEqualTo(17);
            assertThat(item.status())
                    .as("열거값은 대문자 스네이크로 나간다(`D5` 「형식」)")
                    .isEqualTo("DRAFT");
        }
    }

    @Nested
    @DisplayName("정렬")
    class Sorting {

        @Test
        @DisplayName("허용 목록에 없는 필드는 거부된다")
        void rejectsUnknownSortField() {
            assertThatThrownBy(() -> productQuery.findPublic(null, "price,asc", 0, 20))
                    .as("컬럼명은 바인딩이 안 되는 자리라 우리가 값을 정해야 한다(`D14`)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SORT_NOT_ALLOWED));
        }

        @Test
        @DisplayName("SQL 을 섞어 보내도 거부된다")
        void rejectsInjectionAttempt() {
            assertThatThrownBy(() ->
                    productQuery.findPublic(null, "created_at; drop table product--,asc", 0, 20))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("이름순으로 고를 수 있다")
        void sortsByName() {
            createAndPutOnSale(ownerA, sellerA, "가나다");
            createAndPutOnSale(ownerA, sellerA, "하하하");

            List<String> names = productQuery.findPublic(sellerA, "name,asc", 0, 20).items().stream()
                    .map(ProductQuery.PublicItem::name)
                    .toList();

            assertThat(names).isSorted();
        }
    }

    @Nested
    @DisplayName("페이징")
    class Paging {

        @Test
        @DisplayName("크기를 100 으로 막는다")
        void capsSize() {
            assertThat(productQuery.findPublic(null, null, 0, 5000).size()).isEqualTo(100);
        }

        @Test
        @DisplayName("음수 페이지는 0 으로 본다")
        void negativePageIsFirst() {
            assertThat(productQuery.findPublic(null, null, -3, 20).page()).isZero();
        }
    }

    @Nested
    @DisplayName("공개 상세")
    class PublicDetail {

        @Test
        @DisplayName("옵션과 살 수 있는 조합이 같이 온다")
        void includesOptionsAndSkus() {
            long productId = createAndPutOnSale(ownerA, sellerA, "상세 티셔츠");

            ProductQuery.PublicDetail detail = productQuery.findPublicDetail(productId);

            assertThat(detail.sellerName()).isEqualTo("A셀러");
            assertThat(detail.options()).singleElement()
                    .satisfies(group -> {
                        assertThat(group.name()).isEqualTo("색상");
                        assertThat(group.values()).extracting(ProductQuery.OptionValue::value)
                                .containsExactly("검정", "흰색");
                    });
            assertThat(detail.skus()).extracting(ProductQuery.PublicSku::priceInclVat)
                    .containsExactlyInAnyOrder(15000L, 18000L);
            assertThat(detail.skus())
                    .as("어느 값들의 조합인지가 없으면 화면이 고른 옵션으로 SKU 를 못 찾는다")
                    .allSatisfy(sku -> assertThat(sku.optionValueIds()).isNotEmpty());
        }

        @Test
        @DisplayName("파는 중이 아니면 없는 것과 같은 404 다")
        void hidesDraft() {
            long draft = create(ownerA, sellerA, "준비 중인 상세");

            assertThatThrownBy(() -> productQuery.findPublicDetail(draft))
                    .as("""
                            없는 것과 아직 안 파는 것을 가르면 주소를 하나씩 두드려서
                            남의 draft 가 존재한다는 것을 알아낼 수 있다.
                            """)
                    .isInstanceOf(ShopException.class)
                    .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_NOT_FOUND);
        }

        @Test
        @DisplayName("내린 상품도 404 다")
        void hidesDeleted() {
            long productId = createAndPutOnSale(ownerA, sellerA, "곧 내릴 상세");
            productService.delete(ownerA, productId);

            assertThatThrownBy(() -> productQuery.findPublicDetail(productId))
                    .isInstanceOf(ShopException.class);
        }

        @Test
        @DisplayName("재고는 수량이 아니라 있고 없고만 나간다")
        void tellsStockWithoutCount() {
            long productId = createAndPutOnSale(ownerA, sellerA, "품절 섞인 상세");
            jdbc.sql("""
                            update sku set stock_count = 0
                             where product_id = :id and price_incl_vat = 15000
                            """)
                    .param("id", productId)
                    .update();

            List<ProductQuery.PublicSku> skus = productQuery.findPublicDetail(productId).skus();

            assertThat(skus).filteredOn(sku -> sku.priceInclVat() == 15000L)
                    .singleElement()
                    .satisfies(sku -> assertThat(sku.inStock()).isFalse());
            assertThat(skus).filteredOn(sku -> sku.priceInclVat() == 18000L)
                    .singleElement()
                    .satisfies(sku -> assertThat(sku.inStock()).isTrue());
        }

        @Test
        @DisplayName("내린 조합은 목록에서 빠진다")
        void hidesRetiredSku() {
            long productId = createAndPutOnSale(ownerA, sellerA, "조합 하나 내린 상세");
            jdbc.sql("""
                            update sku set status = 'suspended'
                             where product_id = :id and price_incl_vat = 18000
                            """)
                    .param("id", productId)
                    .update();

            assertThat(productQuery.findPublicDetail(productId).skus())
                    .as("못 사는 조합을 주면 화면이 고를 수 있는 것으로 그리고 담기에서야 막힌다")
                    .extracting(ProductQuery.PublicSku::priceInclVat)
                    .containsExactly(15000L);
        }

        @Test
        @DisplayName("옵션이 없어도 살 수 있는 조합이 나온다")
        void includesSkuOfOptionlessProduct() {
            long productId = createOptionlessAndPutOnSale(ownerA, sellerA, "옵션 없는 머그컵");

            ProductQuery.PublicDetail detail = productQuery.findPublicDetail(productId);

            assertThat(detail.options()).isEmpty();
            assertThat(detail.skus())
                    .as("""
                            조합이 비면 화면에 살 수 있는 것이 하나도 안 보인다.
                            담기·주문은 sku_id 로 하므로 오류가 안 나고 화면만 못 그린다.
                            """)
                    .singleElement()
                    .satisfies(sku -> {
                        assertThat(sku.priceInclVat()).isEqualTo(9000L);
                        assertThat(sku.inStock()).isTrue();
                        assertThat(sku.optionValueIds())
                                .as("고를 것이 없으면 빈 배열이다. 0 이 섞이면 없는 선택지를 가리킨다")
                                .isEmpty();
                    });
        }

        @Test
        @DisplayName("청약철회 제한은 사유까지 대문자로 나간다")
        void exposesWithdrawalRestriction() {
            long productId = productService.create(ownerA, new ProductService.Command(
                    sellerA, "주문 제작 티셔츠", null, null, true, "made_to_order", null,
                    List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                    List.of(new ProductService.SkuCommand(List.of("검정"), 20000, 3))))
                    .productId();
            jdbc.sql("update product set status = 'on_sale' where product_id = :id")
                    .param("id", productId)
                    .update();

            ProductQuery.PublicDetail detail = productQuery.findPublicDetail(productId);

            assertThat(detail.withdrawalRestricted()).isTrue();
            assertThat(detail.withdrawalRestrictionReason())
                    .as("법이 고지를 요구하는 값이라 공개로 나가야 한다(`D2` R4)")
                    .isEqualTo("MADE_TO_ORDER");
        }

        @Test
        @DisplayName("제한이 없으면 사유가 비어 있다")
        void leavesReasonEmptyWhenUnrestricted() {
            long productId = createAndPutOnSale(ownerA, sellerA, "그냥 티셔츠");

            ProductQuery.PublicDetail detail = productQuery.findPublicDetail(productId);

            assertThat(detail.withdrawalRestricted()).isFalse();
            assertThat(detail.withdrawalRestrictionReason()).isNull();
        }
    }

    private long create(long actorUserId, long sellerId, String name) {
        return productService.create(actorUserId, new ProductService.Command(
                sellerId, name, null, null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정", "흰색"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10),
                        new ProductService.SkuCommand(List.of("흰색"), 18000, 7)))).productId();
    }

    /** 검수(7c)가 아직 없어서 상태를 직접 올린다. 그 전이는 그 청크가 규칙을 정한다 */
    private long createAndPutOnSale(long actorUserId, long sellerId, String name) {
        return putOnSale(create(actorUserId, sellerId, name));
    }

    /** 옵션 축이 없는 상품. SKU 는 하나고 {@code sku_option_value} 에 행이 안 생긴다 */
    private long createOptionlessAndPutOnSale(long actorUserId, long sellerId, String name) {
        long productId = productService.create(actorUserId, new ProductService.Command(
                sellerId, name, null, null, false, null, null,
                List.of(),
                List.of(new ProductService.SkuCommand(List.of(), 9000, 4)))).productId();
        return putOnSale(productId);
    }

    private long putOnSale(long productId) {
        jdbc.sql("update product set status = 'on_sale' where product_id = :id")
                .param("id", productId)
                .update();
        return productId;
    }

    private List<Long> publicIds() {
        return productQuery.findPublic(null, null, 0, 100).items().stream()
                .map(ProductQuery.PublicItem::productId)
                .toList();
    }

    private List<Long> sellerIds(long viewerId) {
        return productQuery.findForSeller(viewerId, null, null, 0, 100).items().stream()
                .map(ProductQuery.SellerItem::productId)
                .toList();
    }
}
