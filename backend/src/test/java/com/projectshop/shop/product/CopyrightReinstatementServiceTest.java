package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.ImagePipeline;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * 숨긴 사진의 재개 요구·판단·예정일 재개·소 제기 통보를 잰다(`Q465`, 저작권법 제103조③ · 시행령 제42·43조 `D2` R42).
 *
 * <p><b>복제·전송자 셀프 입구는 없다</b> — 수령인이 받은 요구를 관리자가 기록한다. 기간은 법이 정한 것이라 DB 제약(예정일 7~14일)과
 * 서비스(통보 뒤 30일)가 든다.
 */
@DisplayName("저작권 재개 절차")
class CopyrightReinstatementServiceTest extends StorageTestBase {

    @Autowired
    private CopyrightReinstatementService reinstatements;

    @Autowired
    private CopyrightReinstatementBatch batch;

    @Autowired
    private CopyrightHiddenImagePurgeBatch purgeBatch;

    @Autowired
    private CopyrightReportService reports;

    @Autowired
    private ProductImageService images;

    @Autowired
    private ProductService products;

    @Autowired
    private S3Client s3;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private long admin;
    private long owner;
    private long auditor;
    private ProductImageService.Uploaded image;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("cr-reinstate", "재개셀러");
        fixture.verifySeller(sellerId);
        owner = fixture.insertUser("cr-reinstate-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);
        admin = fixture.insertUser("cr-reinstate-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");
        auditor = fixture.insertUser("cr-reinstate-auditor@test.local", "감사");
        fixture.grantGlobal(auditor, "auditor");
        long productId = products.create(owner, new ProductService.Command(
                sellerId, "재개 상품", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)))).productId();
        image = images.upload(owner, productId,
                new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60)));
    }

    @Test
    @DisplayName("기각한 신고에는 재개 요구를 못 받는다")
    void rejectedReportRefuses() {
        long reportId = report();
        reports.decide(admin, reportId, CopyrightDecision.REJECTED);

        assertCode(() -> reinstatements.record(admin, reportId, OffsetDateTime.now(), "내 사진이다"),
                ErrorCode.COPYRIGHT_NOT_TAKEN_DOWN);
    }

    @Test
    @DisplayName("통보 뒤 30일이 지난 요구는 못 받는다")
    void lateRequestRefuses() {
        long reportId = takenDown();
        jdbc.sql("update notification set sent_at = now() - interval '31 days' where copyright_report_id = :id")
                .param("id", reportId)
                .update();

        assertCode(() -> reinstatements.record(admin, reportId, OffsetDateTime.now(), "내 사진이다"),
                ErrorCode.COPYRIGHT_REINSTATEMENT_LATE);
    }

    @Test
    @DisplayName("재개 요구는 신고당 하나다")
    void secondRequestRefuses() {
        long reportId = takenDown();
        reinstatements.record(admin, reportId, OffsetDateTime.now(), "내 사진이다");

        assertCode(() -> reinstatements.record(admin, reportId, OffsetDateTime.now(), "다시"),
                ErrorCode.COPYRIGHT_REINSTATEMENT_EXISTS);
    }

    @Test
    @DisplayName("예정일이 요구받은 날의 7일 전이거나 14일 뒤면 400 이다")
    void windowIsChecked() {
        long reportId = takenDown();
        reinstatements.record(admin, reportId, OffsetDateTime.now(), "내 사진이다");
        LocalDate today = LocalDate.now(BusinessCalendar.ZONE);

        assertCode(() -> reinstatements.decide(admin, reportId, ReinstatementDecision.SCHEDULED, today.plusDays(6)),
                ErrorCode.COPYRIGHT_REINSTATEMENT_WINDOW);
        assertCode(() -> reinstatements.decide(admin, reportId, ReinstatementDecision.SCHEDULED, today.plusDays(15)),
                ErrorCode.COPYRIGHT_REINSTATEMENT_WINDOW);
    }

    @Test
    @DisplayName("예정일을 정하면 권리주장자에게 재개 요구와 예정일을 알린다")
    void scheduledNotifiesReporter() {
        long reportId = takenDown();
        reinstatements.record(admin, reportId, OffsetDateTime.now(), "내 사진이다");

        reinstatements.decide(admin, reportId, ReinstatementDecision.SCHEDULED,
                LocalDate.now(BusinessCalendar.ZONE).plusDays(7));

        assertThat(jdbc.sql("""
                        select count(*) from notification
                         where copyright_report_id = :id and event_type = 'copyright_reinstatement_reporter'
                           and user_id is null
                        """)
                .param("id", reportId)
                .query(Integer.class)
                .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("예정일에 배치가 공개로 되돌리고 복제·전송자에게 알린다")
    void batchReinstatesOnSchedule() {
        long reportId = scheduled();

        batch.runFor(LocalDate.now(BusinessCalendar.ZONE).plusDays(7));

        s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(image.objectKey()));
        assertThat(jdbc.sql("select hidden_at is null from product_image where product_image_id = :id")
                .param("id", image.productImageId()).query(Boolean.class).single())
                .isTrue();
        assertThat(jdbc.sql("""
                        select user_id from notification
                         where copyright_report_id = :id and event_type = 'copyright_reinstated_uploader'
                        """)
                .param("id", reportId).query(Long.class).list())
                .containsExactly(owner);
    }

    @Test
    @DisplayName("소 제기 통보를 받았으면 배치가 재개하지 않는다")
    void suitBlocksReinstatement() {
        long reportId = scheduled();
        reinstatements.recordSuitNotice(admin, reportId, OffsetDateTime.now());

        batch.runFor(LocalDate.now(BusinessCalendar.ZONE).plusDays(7));

        assertThat(jdbc.sql("select hidden_at is not null from product_image where product_image_id = :id")
                .param("id", image.productImageId()).query(Boolean.class).single())
                .isTrue();
    }

    @Test
    @DisplayName("재개 예정인 숨김은 47일이 지나도 안 지운다")
    void purgeSkipsScheduled() {
        scheduled();
        jdbc.sql("update product_image set hidden_at = now() - interval '48 days' where product_image_id = :id")
                .param("id", image.productImageId()).update();

        purgeBatch.runFor(LocalDate.now(BusinessCalendar.ZONE));

        assertThat(jdbc.sql("select count(*) from product_image where product_image_id = :id")
                .param("id", image.productImageId()).query(Integer.class).single())
                .isOne();
    }

    @Test
    @DisplayName("예정일이 7~14일 밖이면 DB 가 막는다")
    void windowCheckBlocks() {
        long reportId = takenDown();

        assertThatThrownBy(() -> insertReinstatement(reportId, "'scheduled'", "current_date + 15"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("copyright_reinstatement_window_check");
    }

    @Test
    @DisplayName("판단 값은 둘뿐이다")
    void decisionCheckBlocks() {
        long reportId = takenDown();

        assertThatThrownBy(() -> insertReinstatement(reportId, "'maybe'", "null"))
                .hasMessageContaining("copyright_reinstatement_decision_check");
    }

    @Test
    @DisplayName("판단·시각·판단자는 같이 찬다")
    void decidedCheckBlocks() {
        long reportId = takenDown();

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into copyright_reinstatement (copyright_report_id, requested_at, requester_claim, decision)
                        values (:id, now(), '소명', 'refused')
                        """).param("id", reportId).update())
                .hasMessageContaining("copyright_reinstatement_decided_check");
    }

    @Test
    @DisplayName("예정일은 예정 판단에만 있다")
    void scheduledForCheckBlocks() {
        long reportId = takenDown();

        assertThatThrownBy(() -> insertReinstatement(reportId, "'refused'", "current_date + 7"))
                .hasMessageContaining("copyright_reinstatement_scheduled_for_check");
    }

    @Test
    @DisplayName("예정 판단 없이 재개 시각이 못 선다")
    void reinstatedCheckBlocks() {
        long reportId = takenDown();

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into copyright_reinstatement (copyright_report_id, requested_at, requester_claim, reinstated_at)
                        values (:id, now(), '소명', now())
                        """).param("id", reportId).update())
                .hasMessageContaining("copyright_reinstatement_reinstated_check");
    }

    @Test
    @DisplayName("소명은 2000자까지다")
    void claimLengthBlocks() {
        long reportId = takenDown();

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into copyright_reinstatement (copyright_report_id, requested_at, requester_claim)
                        values (:id, now(), repeat('가', 2001))
                        """).param("id", reportId).update())
                .hasMessageContaining("copyright_reinstatement_requester_claim_length_check");
    }

    @Test
    @DisplayName("재개 요구 기록은 201 이고 신고 자원을 가리킨다 · 감사자와 셀러는 403 이다")
    void apiRecordsRequest() throws Exception {
        long reportId = takenDown();
        String body = "{\"requested_at\": \"" + OffsetDateTime.now() + "\", \"requester_claim\": \"내 사진이다\"}";

        for (long denied : new long[] {auditor, owner}) {
            mvc.perform(post("/api/copyright-reports/{id}/reinstatement", reportId).with(user(principal(denied)))
                            .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/copyright-reports/{id}/reinstatement", reportId).with(user(principal(admin)))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/copyright-reports/" + reportId + "/reinstatement"));
    }

    private long report() {
        return reports.report(image.productImageId(),
                new CopyrightReportService.Command("권리자", "rights@test.local", "우리 화보 사진이다")).copyrightReportId();
    }

    private long takenDown() {
        long reportId = report();
        reports.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);
        return reportId;
    }

    /** 오늘 요구를 받아 7일 뒤로 예정한 신고 */
    private long scheduled() {
        long reportId = takenDown();
        reinstatements.record(admin, reportId, OffsetDateTime.now(), "내 사진이다");
        reinstatements.decide(admin, reportId, ReinstatementDecision.SCHEDULED,
                LocalDate.now(BusinessCalendar.ZONE).plusDays(7));
        return reportId;
    }

    private void insertReinstatement(long reportId, String decision, String scheduledFor) {
        jdbc.sql("""
                        insert into copyright_reinstatement (copyright_report_id, requested_at, requester_claim,
                                                             decision, decided_at, decided_by_user_id, scheduled_for)
                        values (:id, now(), '소명', %s, now(), :admin, %s)
                        """.formatted(decision, scheduledFor))
                .param("id", reportId)
                .param("admin", admin)
                .update();
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ShopException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    private static ShopUser principal(long userId) {
        return new ShopUser(userId, "cr@test.local", null, true).verified();
    }
}
