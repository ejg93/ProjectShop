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
 * 담당자가 자기가 등록한 상품만 고친다(`Q161`).
 *
 * <p><b>`5a` 가 준 권한이 아무것도 안 덮고 있었다.</b> {@code seller_staff} 에게
 * {@code product:update}·{@code delete} 를 {@code own} 으로 줬는데, 판정 대상에 등록자가
 * 안 실려서 {@code ownerUserId} 가 {@code null} 이었다 — {@code Scope.OWN} 은 그 값이
 * 그 사용자와 같을 때만 덮으므로 <b>담당자는 자기가 등록한 상품도 못 고쳤다.</b>
 *
 * <p><b>판정을 실제로 지난다.</b> {@code role_permission.scope} 가 {@code 'own'} 인지만 재면
 * <b>그 구멍이 열려 있는 채로 초록</b>이다 — `5a` 의 시험이 정확히 그 모양이었고,
 * 마무리 43차 독립 리뷰가 그것을 짚었다.
 */
@DisplayName("담당자의 상품 권한")
class SellerStaffProductTest extends PostgresTestBase {

    @Autowired
    private ProductService products;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private long seller;
    private long staff;
    private long owner;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        seller = fixture.insertSeller("staff-seller", "담당자셀러");
        fixture.verifySeller(seller);

        owner = fixture.insertUser("owner@example.com", "대표");
        fixture.joinSeller(seller, owner);
        fixture.grantOrg(owner, "seller_owner", seller);

        staff = fixture.insertUser("staff@example.com", "담당자");
        fixture.joinSeller(seller, staff);
        fixture.grantOrg(staff, "seller_staff", seller);
    }

    @Nested
    @DisplayName("수정")
    class Update {

        @Test
        @DisplayName("자기가 등록한 상품은 고친다")
        void 자기가_등록한_상품은_고친다() {
            long mine = insertProduct(staff, "담당자가 올린 것");

            products.replace(staff, mine, command("이름을 바꾼다"));

            assertThat(nameOf(mine)).isEqualTo("이름을 바꾼다");
        }

        /** `own` 이 그 셀러 전체를 덮으면 담당자와 대표를 가른 뜻이 없어진다 */
        @Test
        @DisplayName("남이 등록한 상품은 못 고친다")
        void 남이_등록한_상품은_못_고친다() {
            long theirs = insertProduct(owner, "대표가 올린 것");

            assertThatThrownBy(() -> products.replace(staff, theirs, command("끼어든다")))
                    .isInstanceOf(ShopException.class)
                    .extracting(error -> ((ShopException) error).code())
                    .isEqualTo(ErrorCode.PRODUCT_FORBIDDEN);
        }

        /** 대표는 `seller` 스코프라 누가 올렸든 고친다 */
        @Test
        @DisplayName("대표는 담당자가 올린 것도 고친다")
        void 대표는_담당자가_올린_것도_고친다() {
            long theirs = insertProduct(staff, "담당자가 올린 것");

            products.replace(owner, theirs, command("대표가 고친다"));

            assertThat(nameOf(theirs)).isEqualTo("대표가 고친다");
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("자기가 등록한 상품은 내린다")
        void 자기가_등록한_상품은_내린다() {
            long mine = insertProduct(staff, "내릴 것");

            products.delete(staff, mine);

            assertThat(aliveCount(mine)).isZero();
        }

        @Test
        @DisplayName("남이 등록한 상품은 못 내린다")
        void 남이_등록한_상품은_못_내린다() {
            long theirs = insertProduct(owner, "대표 것");

            assertThatThrownBy(() -> products.delete(staff, theirs))
                    .isInstanceOf(ShopException.class);
        }
    }

    /** 등록은 `seller` 스코프다 — 만들기 전에는 주인이 없어서 `own` 이 뜻이 없다(`V3` 주석) */
    @Test
    @DisplayName("담당자도 새 상품을 등록한다")
    void 담당자도_새_상품을_등록한다() {
        var created = products.create(staff, command("새 상품"));

        assertThat(created.productId()).isPositive();
    }

    /** 팔 조합이 하나는 있어야 상품이 선다(`ProductService` 의 「팔 조합이 하나도 없다」) */
    private ProductService.Command command(String name) {
        return new ProductService.Command(seller, name, null, null, false, null, null,
                List.of(), List.of(new ProductService.SkuCommand(List.of(), 10_000L, 10)));
    }

    private long insertProduct(long createdBy, String name) {
        return jdbc.sql("""
                        insert into product (seller_id, created_by_user_id, name)
                        values (:sellerId, :userId, :name)
                        returning product_id
                        """)
                .param("sellerId", seller)
                .param("userId", createdBy)
                .param("name", name)
                .query(Long.class)
                .single();
    }

    private String nameOf(long productId) {
        return jdbc.sql("select name from product where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .single();
    }

    private int aliveCount(long productId) {
        return jdbc.sql("""
                        select count(*) from product
                         where product_id = :id and deleted_at is null
                        """)
                .param("id", productId)
                .query(Integer.class)
                .single();
    }
}
