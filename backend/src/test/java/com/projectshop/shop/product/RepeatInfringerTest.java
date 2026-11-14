package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.PermissionResource;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.review.ReviewFixture;
import com.projectshop.shop.review.ReviewImageService;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 반복 침해자 방침 — 12개월 안 게시 중단 3회면 계정을 정지하고, 정지가 실제로 막는지 잰다(`Q466`, 저작권법 제102조제1항제1호다목
 * `D2` R42). 횟수는 법이 안 정한다 — 우리 방침이다(2026-11-14 사용자).
 */
@DisplayName("반복 침해자 정지")
class RepeatInfringerTest extends StorageTestBase {

    @Autowired
    private CopyrightReportService reports;

    @Autowired
    private ProductImageService images;

    @Autowired
    private ProductService products;

    @Autowired
    private ProductQuery productQuery;

    @Autowired
    private ReviewImageService reviewImages;

    @Autowired
    private PermissionEvaluator evaluator;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private long admin;
    private long owner;
    private long sellerId;
    private long productId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("cr-strike", "침해셀러");
        fixture.verifySeller(sellerId);
        owner = fixture.insertUser("cr-strike-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);
        admin = fixture.insertUser("cr-strike-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");
        productId = products.create(owner, new ProductService.Command(
                sellerId, "침해 상품", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)))).productId();
        jdbc.sql("update product set status = 'on_sale' where product_id = :id").param("id", productId).update();
    }

    @Test
    @DisplayName("같은 셀러 상품 사진이 12개월 안 세 번 중단되면 셀러가 정지되고 감사가 남는다")
    void threeStrikesSuspendSeller() {
        takeDownProductImage();
        takeDownProductImage();
        assertThat(sellerStatus()).isEqualTo("active");

        takeDownProductImage();

        assertThat(sellerStatus()).isEqualTo("suspended");
        assertThat(jdbc.sql("""
                        select count(*) from audit_log where event_type = 'seller.suspended' and target_id = :id
                        """).param("id", sellerId).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("정지된 셀러의 상품은 공개에서 빠지고 구성원은 셀러 스코프를 잃는다")
    void suspensionIsEnforced() {
        assertThat(evaluator.decide(owner, PermissionResource.PRODUCT, "update", Target.ofSeller(sellerId)).allowed())
                .isTrue();
        for (int i = 0; i < 3; i++) {
            takeDownProductImage();
        }

        assertThatThrownBy(() -> productQuery.findPublicDetail(productId)).isInstanceOf(ShopException.class);
        assertThat(productQuery.findPublic(null, null, new Paging(0, 50)).items())
                .noneMatch(item -> item.productId() == productId);
        assertThat(evaluator.decide(owner, PermissionResource.PRODUCT, "update", Target.ofSeller(sellerId)).allowed())
                .as("정지 셀러의 구성원은 그 셀러 스코프를 안 받는다")
                .isFalse();
    }

    @Test
    @DisplayName("재개된 중단과 12개월 전 중단은 안 센다")
    void reinstatedAndOldDoNotCount() {
        long reinstated = takeDownProductImage();
        jdbc.sql("""
                        insert into copyright_reinstatement (copyright_report_id, requested_at, requester_claim, decision,
                                                             decided_at, decided_by_user_id, scheduled_for, reinstated_at)
                        values (:id, now(), '소명', 'scheduled', now(), :admin, current_date + 7, now())
                        """).param("id", reinstated).param("admin", admin).update();
        long old = takeDownProductImage();
        jdbc.sql("update copyright_report set decided_at = now() - interval '13 months' where copyright_report_id = :id")
                .param("id", old).update();

        takeDownProductImage();
        takeDownProductImage();

        assertThat(sellerStatus()).as("센 것은 둘이다").isEqualTo("active");
    }

    @Test
    @DisplayName("후기 사진 작성자가 세 번 중단되면 그 계정이 정지되고 세션이 끊긴다")
    void threeStrikesSuspendReviewer() throws Exception {
        ReviewFixture fixture = new ReviewFixture(jdbc);
        long writer = fixture.buyerId();
        for (int i = 0; i < 3; i++) {
            long reviewProduct = fixture.insertProduct("후기 상품 " + i);
            long orderItemId = fixture.placeOrder(reviewProduct, "delivered");
            fixture.insertReview(orderItemId, reviewProduct, writer, 5, "좋아요 " + i);
            long reviewId = jdbc.sql("select review_id from review where order_item_id = :id")
                    .param("id", orderItemId).query(Long.class).single();
            long imageId = reviewImages.upload(writer, reviewId,
                    new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60))).reviewImageId();
            long reportId = reports.reportReviewImage(imageId, new CopyrightReportService.Command(
                    "권리자", "rights@test.local", "화보 " + i)).copyrightReportId();
            reports.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);
        }

        assertThat(jdbc.sql("select status from app_user where user_id = :id").param("id", writer)
                .query(String.class).single())
                .isEqualTo("suspended");
        mvc.perform(get("/api/me").with(user(new ShopUser(writer, "w@test.local", null, true).verified())))
                .andExpect(status().isUnauthorized());
    }

    /** 후기 사진 신고는 작성자를 박제한다 — 사진이 지워져도 셈이 그 사람을 가리켜야 한다 */
    @Test
    @DisplayName("작성자 없는 후기 사진 신고는 DB 가 막는다")
    void reviewReportNeedsUploader() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into copyright_report (product_id, target, reporter_name, reporter_email, claimed_work)
                        values (:id, 'review_image', '권리자', 'rights@test.local', '화보')
                        """).param("id", productId).update())
                .hasMessageContaining("copyright_report_review_uploader_check");
    }

    /** 이 셀러 상품에 사진을 올려 신고·중단한다 */
    private long takeDownProductImage() {
        ProductImageService.Uploaded uploaded = images.upload(owner, productId,
                new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60)));
        long reportId = reports.report(uploaded.productImageId(),
                new CopyrightReportService.Command("권리자", "rights@test.local", "화보")).copyrightReportId();
        reports.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);
        return reportId;
    }

    private String sellerStatus() {
        return jdbc.sql("select status from seller where seller_id = :id").param("id", sellerId)
                .query(String.class).single();
    }
}
