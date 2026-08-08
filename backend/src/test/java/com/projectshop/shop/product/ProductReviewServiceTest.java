package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 상품 검수(`7c`, `D2` R15).
 *
 * <p>거짓·과장 광고는 시스템이 판단할 수 없어서 요건이 <b>"사람이 검수하는 경로가 있느냐"</b> 로 바뀐다.
 *
 * <p><b>판매 개시에는 셀러 상태가 걸린다</b>(제20조②). 그걸 앱과 트리거 두 겹으로 막고,
 * 여기서는 두 겹이 각자 실제로 도는지를 본다.
 */
@DisplayName("상품 검수")
class ProductReviewServiceTest extends PostgresTestBase {

    @Autowired
    private ProductReviewService reviewService;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcClient jdbc;

    private long sellerId;
    private long owner;
    private long admin;
    private long productId;
    private AuthFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        sellerId = fixture.insertSeller("rv-a", "A셀러");
        owner = fixture.insertUser("rv-owner@test.local", "A사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        admin = fixture.insertUser("rv-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");

        productId = productService.create(owner, new ProductService.Command(
                sellerId, "티셔츠", null, null, false, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)))).productId();
    }

    @Nested
    @DisplayName("전이")
    class Transitions {

        @Test
        @DisplayName("셀러가 신청하면 pending_review 가 된다")
        void sellerSubmits() {
            reviewService.submit(owner, productId);

            assertThat(statusOf(productId)).isEqualTo("pending_review");
        }

        @Test
        @DisplayName("관리자가 승인하면 판매가 시작된다")
        void adminApproves() {
            verifySeller();
            reviewService.submit(owner, productId);

            reviewService.approve(admin, productId);

            assertThat(statusOf(productId)).isEqualTo("on_sale");
        }

        @Test
        @DisplayName("반려하면 draft 로 돌아가고 사유가 남는다")
        void rejectionLeavesNote() {
            reviewService.submit(owner, productId);

            reviewService.reject(admin, productId, "영양성분 표시가 없다");

            assertThat(statusOf(productId)).isEqualTo("draft");
            assertThat(noteOf(productId))
                    .as("사유를 안 주면 셀러가 같은 것을 다시 올리고 검수가 반복된다")
                    .isEqualTo("영양성분 표시가 없다");
        }

        @Test
        @DisplayName("승인하면 지난 반려 사유가 지워진다")
        void approvalClearsNote() {
            reviewService.submit(owner, productId);
            reviewService.reject(admin, productId, "영양성분 표시가 없다");
            reviewService.submit(owner, productId);
            verifySeller();

            reviewService.approve(admin, productId);

            assertThat(noteOf(productId))
                    .as("남겨 두면 지난 반려가 현재 상태처럼 보인다")
                    .isNull();
        }

        @Test
        @DisplayName("draft 를 바로 승인할 수 없다")
        void cannotApproveDraft() {
            verifySeller();

            assertThatThrownBy(() -> reviewService.approve(admin, productId))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_TRANSITION_NOT_ALLOWED));
        }

        @Test
        @DisplayName("두 번 신청할 수 없다")
        void cannotSubmitTwice() {
            reviewService.submit(owner, productId);

            assertThatThrownBy(() -> reviewService.submit(owner, productId))
                    .isInstanceOf(ShopException.class);
        }
    }

    @Nested
    @DisplayName("셀러 신원 확인")
    class SellerVerification {

        @Test
        @DisplayName("확인 전 셀러의 상품은 승인되지 않는다 — 앱이 이유를 준다")
        void appRejectsUnverifiedSeller() {
            reviewService.submit(owner, productId);

            assertThat(sellerStatus()).isEqualTo("pending");

            assertThatThrownBy(() -> reviewService.approve(admin, productId))
                    .as("on_sale 이 청약이 가능해지는 지점이다(제20조②)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.SELLER_NOT_VERIFIED));
        }

        @Test
        @DisplayName("앱을 건너뛰어도 트리거가 막는다")
        void triggerBlocksDirectUpdate() {
            // 앱만 두면 새 입구가 생길 때 빠뜨린다. 이 update 가 그 새 입구를 흉내 낸다.
            assertThatThrownBy(() -> jdbc.sql("""
                            update product set status = 'on_sale' where product_id = :id
                            """).param("id", productId).update())
                    .as("psql·배치·미래의 API 가 같은 자리를 지난다")
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("이미 팔던 것을 고치는 경우는 안 막는다")
        void allowsUpdatingAlreadyOnSale() {
            verifySeller();
            reviewService.submit(owner, productId);
            reviewService.approve(admin, productId);

            // 셀러가 뒤에 정지돼도 이미 나간 상품의 다른 값은 고칠 수 있어야 한다.
            jdbc.sql("update seller set status = 'suspended' where seller_id = :id")
                    .param("id", sellerId)
                    .update();

            jdbc.sql("update product set name = '이름만 바꾼다' where product_id = :id")
                    .param("id", productId)
                    .update();

            assertThat(statusOf(productId)).isEqualTo("on_sale");
        }
    }

    @Nested
    @DisplayName("권한")
    class Permissions {

        @Test
        @DisplayName("셀러는 자기 상품을 승인할 수 없다")
        void sellerCannotApproveOwnProduct() {
            verifySeller();
            reviewService.submit(owner, productId);

            assertThatThrownBy(() -> reviewService.approve(owner, productId))
                    .as("검수는 product:review 고 셀러에게는 그 권한이 없다")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.PRODUCT_FORBIDDEN));
        }

        @Test
        @DisplayName("셀러는 자기 상품을 반려할 수도 없다")
        void sellerCannotReject() {
            reviewService.submit(owner, productId);

            assertThatThrownBy(() -> reviewService.reject(owner, productId, "아무거나"))
                    .isInstanceOf(ShopException.class);
        }
    }

    private void verifySeller() {
        fixture.verifySeller(sellerId);
    }

    private String statusOf(long productId) {
        return jdbc.sql("select status from product where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .single();
    }

    private String noteOf(long productId) {
        return jdbc.sql("select review_note from product where product_id = :id")
                .param("id", productId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private String sellerStatus() {
        return jdbc.sql("select status from seller where seller_id = :id")
                .param("id", sellerId)
                .query(String.class)
                .single();
    }
}
