package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.ImagePipeline;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * 저작권 신고로 숨긴 사진을 47일 뒤 비공개 버킷과 표에서 지우는지 잰다(`Q464`, 시행령 제41~43조 `D2` R42).
 *
 * <p>47일 = 통보 3일 + 재개 요구 30일 + 재개 예정일 14일. 그 안에는 재개할 것이 남아야 하고, 지나면 「실제로 지운다」가 된다.
 */
@DisplayName("숨긴 사진 파기 배치")
class CopyrightHiddenImagePurgeBatchTest extends StorageTestBase {

    @Autowired
    private CopyrightHiddenImagePurgeBatch batch;

    @Autowired
    private CopyrightReportService reports;

    @Autowired
    private ProductImageService images;

    @Autowired
    private ProductService products;

    @Autowired
    private S3Client s3;

    @Autowired
    private JdbcClient jdbc;

    private long admin;
    private long owner;
    private long productId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("cr-purge", "파기셀러");
        fixture.verifySeller(sellerId);
        owner = fixture.insertUser("cr-purge-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);
        admin = fixture.insertUser("cr-purge-admin@test.local", "관리자");
        fixture.grantGlobal(admin, "admin");
        productId = products.create(owner, new ProductService.Command(
                sellerId, "파기 상품", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)))).productId();
    }

    @Test
    @DisplayName("47일이 지난 숨긴 사진은 비공개 객체와 행이 사라진다")
    void purgesAfterRetention() {
        ProductImageService.Uploaded hidden = hideDaysAgo(48);

        batch.runFor(today());

        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PRIVATE_BUCKET).key(hidden.objectKey())))
                .isInstanceOf(NoSuchKeyException.class);
        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PRIVATE_BUCKET).key(hidden.thumbnailKey())))
                .isInstanceOf(NoSuchKeyException.class);
        assertThat(rows(hidden.productImageId())).isZero();
    }

    @Test
    @DisplayName("46일이면 그대로 남는다")
    void keepsWithinRetention() {
        ProductImageService.Uploaded hidden = hideDaysAgo(46);

        batch.runFor(today());
        batch.runFor(today());

        s3.headObject(b -> b.bucket(PRIVATE_BUCKET).key(hidden.objectKey()));
        assertThat(rows(hidden.productImageId())).isOne();
    }

    /** 사진 하나를 올려 신고·중단하고 숨긴 시각을 {@code days} 일 전으로 돌린다 */
    private ProductImageService.Uploaded hideDaysAgo(int days) {
        ProductImageService.Uploaded uploaded = images.upload(owner, productId,
                new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60)));
        long reportId = reports.report(uploaded.productImageId(),
                new CopyrightReportService.Command("권리자", "rights@test.local", "화보")).copyrightReportId();
        reports.decide(admin, reportId, CopyrightDecision.TAKEN_DOWN);
        jdbc.sql("update product_image set hidden_at = now() - make_interval(days => :days) where product_image_id = :id")
                .param("days", days)
                .param("id", uploaded.productImageId())
                .update();
        return uploaded;
    }

    private long rows(long imageId) {
        return jdbc.sql("select count(*) from product_image where product_image_id = :id")
                .param("id", imageId)
                .query(Long.class)
                .single();
    }

    private static LocalDate today() {
        return LocalDate.now(BusinessCalendar.ZONE);
    }
}
