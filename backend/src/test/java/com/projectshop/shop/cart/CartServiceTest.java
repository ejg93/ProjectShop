package com.projectshop.shop.cart;

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
import com.projectshop.shop.product.ProductReviewService;
import com.projectshop.shop.product.ProductService;

/**
 * 장바구니(`9`). <b>비로그인도 담는다.</b>
 *
 * <p>동의 없이 되는 근거는 개인정보법 제15조제1항 4호 —
 * "계약을 체결하는 과정에서 정보주체의 요청에 따른 조치" 가 담아 달라는 요청 그 자체다.
 */
@DisplayName("장바구니")
class CartServiceTest extends PostgresTestBase {

    @Autowired
    private CartService cartService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductReviewService reviewService;

    @Autowired
    private JdbcClient jdbc;

    private long buyer;
    private long skuA;
    private long skuB;

    private static final String TOKEN = "guest-token-1";

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        long sellerId = fixture.insertSeller("cart-a", "A셀러");
        fixture.verifySeller(sellerId);

        long owner = fixture.insertUser("cart-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        long admin = fixture.insertUser("cart-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");

        buyer = fixture.insertUser("cart-buyer@test.local", "고객");
        fixture.grantGlobal(buyer, "customer");

        ProductService.Created created = productService.create(owner, new ProductService.Command(
                sellerId, "티셔츠", null, null, false, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정", "흰색"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10),
                        new ProductService.SkuCommand(List.of("흰색"), 18000, 10))));

        reviewService.submit(owner, created.productId());
        reviewService.approve(admin, created.productId());

        skuA = created.skuIds().get(0);
        skuB = created.skuIds().get(1);
    }

    @Nested
    @DisplayName("비로그인")
    class Guest {

        @Test
        @DisplayName("쿠키 토큰만으로 담고 본다")
        void addsWithTokenOnly() {
            cartService.add(guest(), skuA, 2);

            CartService.Cart cart = cartService.read(guest());

            assertThat(cart.items()).hasSize(1);
            assertThat(cart.total())
                    .as("담는 것은 계약 체결 과정의 요청이라 동의 없이 된다(제15조①4호)")
                    .isEqualTo(30000);
        }

        @Test
        @DisplayName("다른 토큰은 남의 장바구니를 못 본다")
        void tokensAreIsolated() {
            cartService.add(guest(), skuA, 2);

            assertThat(cartService.read(new CartService.Owner(null, "guest-token-2")).items())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("담기")
    class Adding {

        @Test
        @DisplayName("같은 조합을 다시 담으면 수량이 더해진다")
        void addsUpQuantity() {
            cartService.add(account(), skuA, 2);
            cartService.add(account(), skuA, 3);

            assertThat(cartService.read(account()).items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.quantity())
                            .as("'같은 것을 하나 더' 가 담기의 뜻이다")
                            .isEqualTo(5));
        }

        @Test
        @DisplayName("파는 중이 아닌 것은 못 담는다")
        void rejectsUnsellable() {
            long draftSku = draftProductSku();

            assertThatThrownBy(() -> cartService.add(account(), draftSku, 1))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SKU_NOT_BUYABLE));
        }

        @Test
        @DisplayName("담은 뒤 상품이 내려가도 지우지 않는다 — 살 수 없다고만 표시한다")
        void keepsUnavailableItem() {
            cartService.add(account(), skuA, 1);

            jdbc.sql("""
                            update product set status = 'suspended'
                             where product_id = (select product_id from sku where sku_id = :id)
                            """).param("id", skuA).update();

            CartService.Cart cart = cartService.read(account());

            assertThat(cart.items())
                    .as("사라지면 왜 없어졌는지를 사용자가 모른다. 다시 팔면 살 수 있다")
                    .hasSize(1);
            assertThat(cart.items().get(0).available()).isFalse();
            assertThat(cart.total())
                    .as("못 사는 것은 합계에서 뺀다")
                    .isZero();
        }
    }

    /**
     * 화면이 줄을 그리는 데 필요한 것이 실려 나가나(`15-1`).
     *
     * <p>둘 다 <b>없으면 화면이 조용히 틀린다.</b> 조합이 없으면 같은 상품의 다른 조합이
     * 글자가 똑같은 두 줄로 보이고, 파는 사람이 없으면 주문서가 누구에게 사는 것인지를
     * 청약 전에 못 밝힌다(`D2` R1, 전자상거래법 제20조제2항).
     */
    @Nested
    @DisplayName("담긴 것을 보여줄 때")
    class Display {

        @Test
        @DisplayName("고른 조합이 같이 나온다")
        void carriesOptionLabel() {
            cartService.add(account(), skuA, 1);

            assertThat(cartService.read(account()).items().get(0).optionLabel())
                    .as("없으면 검정과 흰색이 화면에서 같은 줄로 보인다")
                    .isEqualTo("검정");
        }

        @Test
        @DisplayName("파는 사람이 같이 나온다")
        void carriesSeller() {
            cartService.add(account(), skuA, 1);

            CartService.Item item = cartService.read(account()).items().get(0);

            assertThat(item.sellerName()).isEqualTo("A셀러");
            assertThat(item.sellerId())
                    .as("화면이 셀러로 묶으려면 이름만으로는 안 된다 — 같은 이름의 셀러가 있을 수 있다")
                    .isPositive();
        }
    }

    @Nested
    @DisplayName("수량과 삭제")
    class Changing {

        @Test
        @DisplayName("수량을 0 으로 두면 빠진다")
        void zeroRemoves() {
            cartService.add(account(), skuA, 2);

            cartService.changeQuantity(account(), skuA, 0);

            assertThat(cartService.read(account()).items()).isEmpty();
        }

        @Test
        @DisplayName("담지 않은 것의 수량은 못 바꾼다")
        void unknownItemIsNotFound() {
            cartService.add(account(), skuA, 1);

            assertThatThrownBy(() -> cartService.changeQuantity(account(), skuB, 3))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.CART_ITEM_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("로그인할 때 병합")
    class Merging {

        @Test
        @DisplayName("같은 조합은 큰 쪽이 남는다")
        void keepsLargerQuantity() {
            cartService.add(guest(), skuA, 2);
            cartService.add(account(), skuA, 3);

            cartService.mergeIntoAccount(buyer, TOKEN);

            assertThat(cartService.read(account()).items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.quantity())
                            .as("더하면 두 기기에서 담은 사람이 의도하지 않은 수량을 받는다")
                            .isEqualTo(3));
        }

        @Test
        @DisplayName("비로그인에만 있던 것은 그대로 옮겨진다")
        void movesGuestOnlyItems() {
            cartService.add(guest(), skuB, 4);

            cartService.mergeIntoAccount(buyer, TOKEN);

            assertThat(cartService.read(account()).items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.skuId()).isEqualTo(skuB));
        }

        @Test
        @DisplayName("옮긴 뒤 비로그인 장바구니가 사라진다")
        void guestCartIsGoneAfterMerge() {
            cartService.add(guest(), skuA, 1);

            cartService.mergeIntoAccount(buyer, TOKEN);

            assertThat(cartService.read(guest()).items())
                    .as("남겨 두면 같은 물건이 두 군데 있고 다음 로그인에 또 병합된다")
                    .isEmpty();
        }

        @Test
        @DisplayName("토큰이 없으면 아무 일도 없다")
        void noTokenIsNoop() {
            cartService.add(account(), skuA, 1);

            cartService.mergeIntoAccount(buyer, null);

            assertThat(cartService.read(account()).items()).hasSize(1);
        }
    }

    private CartService.Owner guest() {
        return new CartService.Owner(null, TOKEN);
    }

    private CartService.Owner account() {
        return new CartService.Owner(buyer, null);
    }

    /** 검수를 안 지난 상품의 조합. 담을 수 없어야 한다 */
    private long draftProductSku() {
        long sellerId = jdbc.sql("select seller_id from seller where code = 'cart-a'")
                .query(Long.class).single();
        long owner = jdbc.sql("select user_id from app_user where email = 'cart-owner@test.local'")
                .query(Long.class).single();

        return productService.create(owner, new ProductService.Command(
                sellerId, "준비 중", null, null, false, null,
                List.of(),
                List.of(new ProductService.SkuCommand(List.of(), 1000, 1)))).skuIds().get(0);
    }
}
