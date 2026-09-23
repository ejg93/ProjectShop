package com.projectshop.shop.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.support.ListQuery.Paging;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * 후기 사진(`Q159`).
 *
 * <p>판별·재인코딩·썸네일은 {@code ProductImageServiceTest} 가 이미 잰다 — 같은 {@link ImagePipeline} 이다.
 * 여기는 <b>후기 쪽 규칙</b>이다: 누가 붙이나, 몇 장까지인가, 목록에 실리나, 떼면 저장소까지 가나.
 */
@DisplayName("후기 사진")
class ReviewImageServiceTest extends StorageTestBase {

    private static final Paging FIRST = new Paging(0, 20);

    @Autowired
    private ReviewImageService service;

    @Autowired
    private ReviewQuery query;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private S3Client s3;

    private ReviewFixture fixture;
    private long productId;
    private long reviewId;
    private long buyerId;

    @BeforeEach
    void setUp() {
        fixture = new ReviewFixture(jdbc);
        productId = fixture.insertProduct("사진 상품");
        long orderItemId = fixture.placeOrder(productId, "delivered");
        buyerId = fixture.buyerId();
        fixture.insertReview(orderItemId, productId, buyerId, 5, "사진과 같은 물건이 왔어요");
        reviewId = jdbc.sql("select review_id from review where order_item_id = :id")
                .param("id", orderItemId).query(Long.class).single();
    }

    @Test
    @DisplayName("붙이면 상품 후기와 내 후기에 실린다")
    void 붙이면_상품_후기와_내_후기에_실린다() {
        service.upload(buyerId, reviewId, image("photo.jpg", "jpeg"));

        assertThat(query.findByProduct(null, productId, FIRST).items())
                .singleElement()
                .satisfies(item -> assertThat(item.photos()).singleElement()
                        .satisfies(photo -> {
                            assertThat(photo.thumbnailUrl()).contains("/thumbnail.jpg");
                            assertThat(photo.originalUrl()).contains("/original.jpg");
                        }));
        assertThat(query.findMine(buyerId, FIRST).items())
                .singleElement()
                .satisfies(item -> assertThat(item.photos()).hasSize(1));
    }

    @Test
    @DisplayName("남의 후기에는 못 붙인다")
    void 남의_후기에는_못_붙인다() {
        long stranger = fixture.insertUser("stranger@test.local");

        assertThatThrownBy(() -> service.upload(stranger, reviewId, image("photo.jpg", "jpeg")))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.REVIEW_NOT_ALLOWED);
    }

    /**
     * 장수는 상품과 같은 10장이다(사용자 선택). 앱이 먼저 422 로 답하고 트리거가 뒤를 막는다 —
     * 여기서는 앱을 잰다. 열 장을 손으로 넣는 것은 저장소를 열 번 부르지 않으려는 것이다.
     */
    @Test
    @DisplayName("열 장을 넘기면 거부한다")
    void 열_장을_넘기면_거부한다() {
        for (int i = 0; i < ImagePipeline.MAX_IMAGES_PER_OWNER; i++) {
            jdbc.sql("""
                            insert into review_image (review_id, object_key, thumbnail_key, original_name,
                                                      content_type, byte_size)
                            values (:review, :key, :thumb, 'p.jpg', 'image/jpeg', 10)
                            """)
                    .param("review", reviewId)
                    .param("key", "review/fill-" + i + "/original.jpg")
                    .param("thumb", "review/fill-" + i + "/thumbnail.jpg")
                    .update();
        }

        assertThatThrownBy(() -> service.upload(buyerId, reviewId, image("photo.jpg", "jpeg")))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_LIMIT_REACHED);
    }

    /** 이름은 {@code .jpg} 인데 내용은 PNG 다 — 헤더와 이름이 아니라 내용으로 판별한다 */
    @Test
    @DisplayName("이름과 내용이 어긋나면 거부한다")
    void 이름과_내용이_어긋나면_거부한다() {
        assertThatThrownBy(() -> service.upload(buyerId, reviewId, image("photo.jpg", "png")))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("떼면 행과 저장소 객체가 같이 사라진다")
    void 떼면_행과_저장소_객체가_같이_사라진다() {
        long imageId = service.upload(buyerId, reviewId, image("photo.png", "png")).reviewImageId();
        String objectKey = jdbc.sql("select object_key from review_image where review_image_id = :id")
                .param("id", imageId).query(String.class).single();

        service.delete(buyerId, imageId);

        assertThat(jdbc.sql("select count(*) from review_image where review_id = :id")
                .param("id", reviewId).query(Long.class).single()).isZero();
        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(objectKey)))
                .isInstanceOf(NoSuchKeyException.class);
    }

    private static ImagePipeline.Incoming image(String name, String format) {
        BufferedImage image = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new ImagePipeline.Incoming(name, out.toByteArray());
    }
}
