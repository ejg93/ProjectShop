package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.ObjectStorage;

/**
 * 상품 사진 업로드({@code 27}, {@code media-rules.md}).
 *
 * <p><b>여기가 「내용으로 판별한다」의 강제 지점이다.</b> 요청이 적은 {@code Content-Type} 을
 * 믿으면 이름만 {@code .jpg} 인 무엇이든 들어온다 — 그래서 <b>헤더는 이미지라고 적고
 * 내용은 아닌 것</b>을 일부러 보내 본다.
 */
@DisplayName("상품 사진 업로드")
class ProductImageServiceTest extends StorageTestBase {

    @Autowired
    private ProductImageService service;

    @Autowired
    private ProductService productService;

    @Autowired
    private ObjectStorage storage;

    @Autowired
    private JdbcClient jdbc;

    private long ownerA;
    private long ownerB;
    private long productA;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        long sellerA = fixture.insertSeller("img-a", "A셀러");
        long sellerB = fixture.insertSeller("img-b", "B셀러");

        ownerA = fixture.insertUser("img-owner-a@test.local", "A사장");
        fixture.joinSeller(sellerA, ownerA);
        fixture.grantOrg(ownerA, "seller_owner", sellerA);

        ownerB = fixture.insertUser("img-owner-b@test.local", "B사장");
        fixture.joinSeller(sellerB, ownerB);
        fixture.grantOrg(ownerB, "seller_owner", sellerB);

        productA = productService.create(ownerA, tshirt(sellerA)).productId();
    }

    @Test
    @DisplayName("원본과 썸네일이 둘 다 저장소에 선다")
    void 원본과_썸네일이_둘_다_저장소에_선다() throws IOException {
        ProductImageService.Uploaded uploaded =
                service.upload(ownerA, productA, jpeg("photo.jpg", 1200, 900));

        assertThat(uploaded.objectKey()).endsWith(".jpg");
        assertThat(uploaded.thumbnailKey()).endsWith("/thumbnail.jpg");

        BufferedImage thumbnail = ImageIO.read(URI.create(storage.presignedUrl(uploaded.thumbnailKey())).toURL());
        assertThat(Math.max(thumbnail.getWidth(), thumbnail.getHeight())).isEqualTo(600);

        BufferedImage original = ImageIO.read(URI.create(storage.presignedUrl(uploaded.objectKey())).toURL());
        assertThat(original.getWidth()).isEqualTo(1200);
    }

    @Test
    @DisplayName("남의 상품에는 못 올린다")
    void 남의_상품에는_못_올린다() {
        assertThatThrownBy(() -> service.upload(ownerB, productA, jpeg("photo.jpg", 100, 100)))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_FORBIDDEN);
    }

    /**
     * <b>헤더가 이미지라고 적어도 안 통한다.</b> 보내는 쪽이 자유롭게 적는 값이라
     * 검증의 입력이 될 수 없다(OWASP) — 내용을 읽어서 판별한다.
     */
    @Test
    @DisplayName("내용이 이미지가 아니면 헤더가 뭐라 적혀 있든 거부한다")
    void 내용이_이미지가_아니면_거부한다() {
        ProductImageService.Incoming fake = new ProductImageService.Incoming(
                "photo.jpg", "이건 그냥 글자다".getBytes());

        assertThatThrownBy(() -> service.upload(ownerA, productA, fake))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("5 MiB 를 넘으면 거부한다")
    void 너무_크면_거부한다() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        ProductImageService.Incoming file = new ProductImageService.Incoming("big.jpg", big);

        assertThatThrownBy(() -> service.upload(ownerA, productA, file))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_TOO_LARGE);
    }

    @Test
    @DisplayName("열한 장째는 거부한다")
    void 장수_상한을_넘으면_거부한다() {
        for (int i = 0; i < 10; i++) {
            service.upload(ownerA, productA, jpeg("photo.jpg", 40, 30));
        }

        assertThatThrownBy(() -> service.upload(ownerA, productA, jpeg("photo.jpg", 40, 30)))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_LIMIT_REACHED);
    }

    private static ProductImageService.Incoming jpeg(String name, int width, int height) {
        return new ProductImageService.Incoming(name, ProductImageFixture.jpegBytes(width, height));
    }

    private static ProductService.Command tshirt(long sellerId) {
        return new ProductService.Command(
                sellerId, "티셔츠", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)));
    }
}
