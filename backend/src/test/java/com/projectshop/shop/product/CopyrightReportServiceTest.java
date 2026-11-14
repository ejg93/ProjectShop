package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.ListQuery.Paging;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.review.ReviewFixture;
import com.projectshop.shop.review.ReviewImageService;

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

    private static final Paging FIRST = new Paging(0, 20);

    @Autowired
    private CopyrightReportService service;

    @Autowired
    private CopyrightReportQuery query;

    @Autowired
    private ProductImageService imageService;

    @Autowired
    private ProductService productService;

    @Autowired
    private S3Client s3;

    @Autowired
    private ReviewImageService reviewImages;

    @Autowired
    private ProductQuery productQuery;

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
    /**
     * 중단은 숨김이다(`Q464`) — 공개 버킷에서 사라지고 비공개 버킷에 같은 키로 남으며 행은 {@code hidden_at} 을 든다.
     * 재개(저작권법 제103조③)할 것이 남아야 한다.
     */
    @Test
    @DisplayName("게시 중단하면 공개 버킷에서 사라지고 비공개 버킷에 남으며 hidden_at 이 찬다")
    void 게시_중단하면_공개에서_사라지고_비공개에_남는다() {
        long reportId = report();

        service.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);

        for (String key : List.of(image.objectKey(), image.thumbnailKey())) {
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(key)))
                    .isInstanceOf(NoSuchKeyException.class);
            s3.headObject(b -> b.bucket(PRIVATE_BUCKET).key(key));
        }
        assertThat(imageRows()).isOne();
        assertThat(jdbc.sql("select hidden_at is not null from product_image where product_image_id = :id")
                .param("id", image.productImageId())
                .query(Boolean.class)
                .single())
                .isTrue();
    }

    @Test
    @DisplayName("숨긴 사진은 공개 상세에 안 나오고 셀러 자기 목록에는 숨김으로 나온다")
    void 숨긴_사진은_공개에서_빠지고_셀러에게는_숨김으로_보인다() {
        long productId = jdbc.sql("select product_id from product_image where product_image_id = :id")
                .param("id", image.productImageId()).query(Long.class).single();
        jdbc.sql("update product set status = 'on_sale' where product_id = :id").param("id", productId).update();
        assertThat(productQuery.findPublicDetail(productId).imageIds()).contains(image.productImageId());
        service.decide(admin, report(), CopyrightDecision.TAKEN_DOWN);

        assertThat(productQuery.findPublicDetail(productId).imageIds())
                .doesNotContain(image.productImageId());
        assertThat(imageService.find(owner, productId))
                .singleElement()
                .satisfies(listed -> {
                    assertThat(listed.hidden()).isTrue();
                    assertThat(listed.thumbnailUrl()).isNull();
                });
    }

    /**
     * 중단하면 권리주장자와 복제·전송자에게 알린다(저작권법 제103조②, 시행령 제41조 `R42`). 신고자는 계정이 없어
     * {@code user_id} 가 비고 주소는 신고에서 온다. 상품 사진의 복제·전송자는 그 셀러의 구성원이다.
     */
    @Test
    @DisplayName("게시 중단하면 신고자와 복제·전송자에게 통보한다")
    void 게시_중단하면_신고자와_복제전송자에게_통보한다() {
        long reportId = report();

        service.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);

        assertThat(notices(reportId)).containsExactlyInAnyOrder(
                "copyright_taken_down_reporter:null:succeeded",
                "copyright_taken_down_uploader:" + owner + ":succeeded");
        assertThat(noticeBody(reportId, "copyright_taken_down_uploader"))
                .contains("우리 화보 사진이다")
                .contains("30일");
    }

    @Test
    @DisplayName("기각하면 아무에게도 통보하지 않는다")
    void 기각하면_통보하지_않는다() {
        long reportId = report();

        service.decide(admin, reportId, CopyrightDecision.REJECTED);

        assertThat(notices(reportId)).isEmpty();
    }

    /** 계정 없는 수신자는 신고자 사건에만 연다 — 다른 사건이 주소 없는 행을 쓰면 「누구에게 보냈나」에 못 답한다 */
    @Test
    @DisplayName("계정 없는 통보는 신고자 사건에만 열린다")
    void 계정_없는_통보는_신고자_사건에만_열린다() {
        long reportId = report();

        assertThatThrownBy(() -> insertNotice(reportId, "order_placed"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("notification_recipient_check");
    }

    @Test
    @DisplayName("같은 신고에 신고자 통보는 하나다")
    void 같은_신고에_신고자_통보는_하나다() {
        long reportId = report();
        service.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);

        assertThatThrownBy(() -> insertNotice(reportId, "copyright_taken_down_reporter"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("notification_copyright_reporter_unique");
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

    /**
     * 판정하려면 신고를 볼 수 있어야 한다(`Q183`). 목록이 없던 동안은 절차가 있어도 돌릴 수가 없었다.
     */
    @Test
    @DisplayName("관리자는 판정 전 신고를 보고, 판정하면 판정한 쪽으로 옮겨 간다")
    void 관리자는_판정_전_신고를_보고_판정하면_옮겨_간다() {
        long reportId = report();

        assertThat(query.find(admin, true, FIRST).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.copyrightReportId()).isEqualTo(reportId);
                    assertThat(item.thumbnailUrl()).isNotNull();
                    assertThat(item.decision()).isNull();
                });

        service.decide(admin, reportId, CopyrightDecision.REJECTED);

        assertThat(query.find(admin, true, FIRST).items()).isEmpty();
        assertThat(query.find(admin, false, FIRST).items())
                .singleElement()
                .satisfies(item -> assertThat(item.decision()).isEqualTo("REJECTED"));
    }

    @Test
    @DisplayName("셀러는 자기 상품에 들어온 신고도 못 본다 — 신고의 상대다")
    void 셀러는_신고를_못_본다() {
        report();

        assertThatThrownBy(() -> query.find(owner, true, FIRST))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_FORBIDDEN);
    }

    /**
     * 후기 사진도 같은 절차다(`Q196`, `R42`). <b>게시 중단이 저장소 객체까지 지워야 한다</b> — 전에는 후기 신고로
     * 내려도 {@code blocked_at} 만 서고 사진이 공개 버킷에 남았다. 신고된 것은 사진이라 후기 글은 남는다.
     */
    @Test
    @DisplayName("후기 사진도 게시 중단하면 저장소에서 사라지고 후기 글은 남는다")
    void 후기_사진도_게시_중단하면_저장소에서_사라진다() {
        ReviewFixture reviews = new ReviewFixture(jdbc);
        long reviewProduct = reviews.insertProduct("후기 상품");
        long orderItemId = reviews.placeOrder(reviewProduct, "delivered");
        reviews.insertReview(orderItemId, reviewProduct, reviews.buyerId(), 5, "사진과 같은 물건이 왔어요");
        long reviewId = jdbc.sql("select review_id from review where order_item_id = :id")
                .param("id", orderItemId).query(Long.class).single();
        long reviewImageId = reviewImages.upload(reviews.buyerId(), reviewId,
                new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60))).reviewImageId();
        List<String> keys = jdbc.sql("select object_key, thumbnail_key from review_image where review_image_id = :id")
                .param("id", reviewImageId)
                .query((rs, rowNum) -> List.of(rs.getString("object_key"), rs.getString("thumbnail_key")))
                .single();

        long reportId = service.reportReviewImage(reviewImageId, new CopyrightReportService.Command(
                "권리자", "rights@test.local", "우리 화보 사진이다")).copyrightReportId();

        assertThat(query.find(admin, true, FIRST).items())
                .as("관리자가 사진을 보고 판정한다 — 대기열에 후기 사진이 실린다")
                .anySatisfy(item -> {
                    assertThat(item.copyrightReportId()).isEqualTo(reportId);
                    assertThat(item.target()).isEqualTo("REVIEW_IMAGE");
                    assertThat(item.thumbnailUrl()).isNotNull();
                });

        service.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);

        for (String key : keys) {
            assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(key)))
                    .isInstanceOf(NoSuchKeyException.class);
        }
        assertThat(jdbc.sql("select count(*) from review where review_id = :id and deleted_at is null")
                .param("id", reviewId).query(Long.class).single())
                .as("글까지 내리면 저작권 절차로 후기를 지우는 길이 된다")
                .isEqualTo(1);
        assertThat(notices(reportId))
                .as("후기 사진의 복제·전송자는 작성자다")
                .containsExactlyInAnyOrder(
                        "copyright_taken_down_reporter:null:succeeded",
                        "copyright_taken_down_uploader:" + reviews.buyerId() + ":succeeded");
    }

    /** 무엇을 신고했나와 가리키는 칸이 갈리면 판정 이력이 다른 사진을 말한다(`V109`) */
    @Test
    @DisplayName("신고 대상 종류와 가리키는 칸이 어긋나면 DB 가 막는다")
    void 신고_대상_종류와_가리키는_칸이_어긋나면_막는다() {
        assertThatThrownBy(() -> jdbc.sql("""
                        insert into copyright_report (product_image_id, product_id, target,
                                                      reporter_name, reporter_email, claimed_work)
                        select product_image_id, product_id, 'review_image', '권리자', 'r@test.local', '작품'
                          from product_image where product_image_id = :id
                        """)
                .param("id", image.productImageId())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** 그 신고의 통보 — {@code 사건:받는계정:상태} */
    private List<String> notices(long reportId) {
        return jdbc.sql("""
                        select event_type || ':' || coalesce(user_id::text, 'null') || ':' || status
                          from notification where copyright_report_id = :id
                        """)
                .param("id", reportId)
                .query(String.class)
                .list();
    }

    private String noticeBody(long reportId, String eventType) {
        return jdbc.sql("""
                        select b.body from notification n join notification_body b on b.notification_id = n.notification_id
                         where n.copyright_report_id = :id and n.event_type = :event
                        """)
                .param("id", reportId)
                .param("event", eventType)
                .query(String.class)
                .single();
    }

    private void insertNotice(long reportId, String eventType) {
        jdbc.sql("""
                        insert into notification (user_id, event_type, kind, notification_template_id, channel, status,
                                                  copyright_report_id)
                        select null, :event, 'transactional', notification_template_id, 'email', 'pending', :id
                          from notification_template where code = 'copyright_taken_down_reporter'
                        """)
                .param("event", eventType)
                .param("id", reportId)
                .update();
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
