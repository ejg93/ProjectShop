package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 남의 저작물 신고·삭제({@code Q94}, {@code D2} {@code R42}).
 *
 * <h2>이 시험이 무엇을 막나</h2>
 *
 * <p>「삭제했다」가 <b>행만 지운 것</b>이면 서명 URL 을 아는 사람에게 사진이 계속 열린다 —
 * 그러면 절차가 있는 것처럼 보이는데 실제로는 아무것도 안 내린 것이다.
 * 여기서 <b>저장소에 객체가 없는 것</b>까지 잰다.
 */
@DisplayName("저작권 신고·삭제")
class CopyrightReportServiceTest extends StorageTestBase {

    @Autowired
    private CopyrightReportService service;

    @Autowired
    private ProductImageService imageService;

    @Autowired
    private ProductService productService;

    @Autowired
    private S3Client s3;

    @Autowired
    private JdbcClient jdbc;

    private long admin;
    private long owner;
    private ProductImageService.Uploaded image;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("cr-a", "A셀러");
        fixture.verifySeller(sellerId);

        owner = fixture.insertUser("cr-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        admin = fixture.insertUser("cr-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");

        long productId = productService.create(owner, tshirt(sellerId)).productId();
        image = imageService.upload(owner, productId,
                new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60)));
    }

    /**
     * <b>이것이 `R42` 의 강제 지점이다.</b> 「지웠다」가 행만 지운 것이면
     * 서명 URL 을 아는 사람에게 사진이 계속 열린다.
     */
    @Test
    @DisplayName("게시 중단하면 저장소에서도 사라진다")
    void 게시_중단하면_저장소에서도_사라진다() {
        long reportId = report();

        service.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);

        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(image.objectKey())))
                .isInstanceOf(NoSuchKeyException.class);
        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(image.thumbnailKey())))
                .isInstanceOf(NoSuchKeyException.class);
        assertThat(imageRows()).isZero();
    }

    /** 신고가 근거 없으면 사진은 그대로다. <b>접수와 삭제는 다른 일이다</b> */
    @Test
    @DisplayName("기각하면 사진이 그대로 남는다")
    void 기각하면_사진이_그대로_남는다() {
        long reportId = report();

        service.decide(admin, reportId, CopyrightDecision.REJECTED);

        assertThat(imageRows()).isOne();
    }

    /** 신고 기록은 <b>사진이 사라져도 남는다</b>. 접수된 사실 자체가 증거다 */
    @Test
    @DisplayName("사진을 지워도 신고 기록은 남는다")
    void 사진을_지워도_신고_기록은_남는다() {
        long reportId = report();

        service.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);

        String decision = jdbc.sql("""
                        select decision from copyright_report where copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query(String.class)
                .single();

        assertThat(decision).isEqualTo("taken_down");
    }

    @Test
    @DisplayName("셀러는 자기 상품 신고를 스스로 판정하지 못한다")
    void 셀러는_스스로_판정하지_못한다() {
        long reportId = report();

        assertThatThrownBy(() -> service.decide(owner, reportId, CopyrightDecision.REJECTED))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_FORBIDDEN);
    }

    /**
     * <b>사진이 먼저 사라진 신고도 판정할 수 있어야 한다.</b> 표가 {@code set null} 로
     * 그 상태를 일부러 만들어 뒀는데, 판정 조회가 사진을 안쪽 조인으로 읽으면
     * <b>그 행이 영영 미판정으로 남는다</b>(마무리 26차 독립 리뷰가 찾았다).
     */
    @Test
    @DisplayName("사진이 이미 사라진 신고도 판정할 수 있다")
    void 사진이_사라진_신고도_판정할_수_있다() {
        long reportId = report();
        jdbc.sql("delete from product_image where product_image_id = :id")
                .param("id", image.productImageId())
                .update();

        service.decide(admin, reportId, CopyrightDecision.REJECTED);

        String decision = jdbc.sql("""
                        select decision from copyright_report where copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query(String.class)
                .single();

        assertThat(decision).isEqualTo("rejected");
    }

    private long report() {
        return service.report(image.productImageId(), new CopyrightReportService.Command(
                "권리자", "rights@test.local", "우리 화보 사진이다")).copyrightReportId();
    }

    private long imageRows() {
        return jdbc.sql("select count(*) from product_image where product_image_id = :id")
                .param("id", image.productImageId())
                .query(Long.class)
                .single();
    }

    private static ProductService.Command tshirt(long sellerId) {
        return new ProductService.Command(
                sellerId, "티셔츠", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)));
    }
}
