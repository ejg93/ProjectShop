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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

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
    private S3Client s3;

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

    /**
     * <b>이름이 내용과 어긋나면 거부한다</b>({@code Q96}). 저장은 내용대로 하므로
     * 이름을 그냥 두면 {@code original_name} 이 거짓말을 하고,
     * <b>그 이름을 믿는 다음 코드</b>가 그것을 물려받는다.
     */
    @Test
    @DisplayName("내용이 PNG 인데 이름이 jpg 면 거부한다")
    void 확장자가_내용과_어긋나면_거부한다() {
        ProductImageService.Incoming mislabeled =
                new ProductImageService.Incoming("photo.jpg", ProductImageFixture.pngBytes(40, 30));

        assertThatThrownBy(() -> service.upload(ownerA, productA, mislabeled))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("확장자가 없는 이름도 거부한다")
    void 확장자가_없으면_거부한다() {
        ProductImageService.Incoming noExtension =
                new ProductImageService.Incoming("photo", ProductImageFixture.jpegBytes(40, 30));

        assertThatThrownBy(() -> service.upload(ownerA, productA, noExtension))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.IMAGE_TYPE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("png 이름에 PNG 내용이면 통과한다")
    void 짝이_맞으면_통과한다() {
        ProductImageService.Uploaded uploaded = service.upload(ownerA, productA,
                new ProductImageService.Incoming("photo.png", ProductImageFixture.pngBytes(40, 30)));

        assertThat(uploaded.objectKey()).endsWith(".png");
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

    /**
     * <b>지우면 저장소에서도 사라진다</b>({@code Q95}). 행만 지우면 주인 없는 파일이 남고,
     * 그 열쇠를 아는 사람에게는 서명 URL 이 계속 나온다.
     */
    @Test
    @DisplayName("사진을 지우면 저장소에서도 사라진다")
    void 사진을_지우면_저장소에서도_사라진다() {
        ProductImageService.Uploaded uploaded =
                service.upload(ownerA, productA, jpeg("photo.jpg", 40, 30));

        service.delete(ownerA, uploaded.productImageId());

        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(uploaded.objectKey())))
                .isInstanceOf(NoSuchKeyException.class);
        assertThatThrownBy(() -> s3.headObject(b -> b.bucket(PUBLIC_BUCKET).key(uploaded.thumbnailKey())))
                .isInstanceOf(NoSuchKeyException.class);
    }

    @Test
    @DisplayName("남의 상품 사진은 못 지운다")
    void 남의_상품_사진은_못_지운다() {
        ProductImageService.Uploaded uploaded =
                service.upload(ownerA, productA, jpeg("photo.jpg", 40, 30));

        assertThatThrownBy(() -> service.delete(ownerB, uploaded.productImageId()))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_FORBIDDEN);
    }

    /**
     * <b>앱 검증을 지나쳐도 DB 가 막는다</b>({@code Q102}). 여기서 {@code insert} 를 직접
     * 넣는 것은 <b>「앱 검증을 빠뜨린 새 입구」를 흉내 내는 것</b>이다 — 관리자 일괄 등록이든
     * 이관 스크립트든, 세는 코드를 안 부르는 다음 자리는 이 트리거를 못 지나간다.
     *
     * <p>{@code check} 로는 못 한다. 그것은 자기 행 안에서 끝나는 조건이고
     * 여기 세는 것은 <b>같은 상품의 다른 행</b>이다({@code coding-rules.md} 「불변식」).
     */
    @Test
    @DisplayName("앱을 건너뛰고 열한 장째를 넣어도 DB 가 거절한다")
    void 앱을_건너뛰어도_DB_가_거절한다() {
        for (int i = 0; i < 10; i++) {
            service.upload(ownerA, productA, jpeg("photo.jpg", 40, 30));
        }

        assertThatThrownBy(() -> jdbc.sql("""
                        insert into product_image
                            (product_id, object_key, thumbnail_key, original_name,
                             content_type, byte_size)
                        values (:id, :key, :thumb, :name, :type, :size)
                        """)
                .param("id", productA)
                .param("key", "product/bypass/original.jpg")
                .param("thumb", "product/bypass/thumbnail.jpg")
                .param("name", "bypass.jpg")
                .param("type", "image/jpeg")
                .param("size", 100L)
                .update())
                .hasMessageContaining("Q102");
    }

    /**
     * 목록을 내주는 자리(`Q139`).
     *
     * <p><b>왜 `Q95` 의 삭제만으로는 부족했나</b> — 지우는 입구가 {@code productImageId} 를 받는데
     * 그 번호를 판매자에게 내주는 자리가 없었다. 공개 상세는 서명 URL 목록만 준다.
     * 그래서 <b>올리기만 하고 지울 수 없는 화면</b>밖에 못 만들었다.
     */
    @Test
    @DisplayName("올린 사진을 번호와 함께 돌려준다")
    void 올린_사진을_번호와_함께_돌려준다() {
        ProductImageService.Uploaded first = service.upload(ownerA, productA, jpeg("a.jpg", 100, 100));
        ProductImageService.Uploaded second = service.upload(ownerA, productA, jpeg("b.jpg", 100, 100));

        List<ProductImageService.Image> images = service.find(ownerA, productA);

        // 번호가 있어야 지울 수 있다. 이것이 이 입구의 존재 이유다.
        assertThat(images).extracting(ProductImageService.Image::productImageId)
                .containsExactly(first.productImageId(), second.productImageId());
        // 올린 순서가 곧 화면 순서다(`sort_no`).
        assertThat(images).extracting(ProductImageService.Image::sortNo).containsExactly(0, 1);
        assertThat(images).extracting(ProductImageService.Image::originalName)
                .containsExactly("a.jpg", "b.jpg");
        // 원본이 아니라 썸네일이다 — 격자로 훑는 자리라 원본을 열 장 내려받을 이유가 없다.
        assertThat(images.getFirst().thumbnailUrl()).contains("/thumbnail.jpg");
    }

    @Test
    @DisplayName("사진이 없으면 빈 목록이다")
    void 사진이_없으면_빈_목록이다() {
        // `null` 을 안 쓴다. 「없다」를 빈 목록이 말하고 `null` 은 「모른다」로도 읽힌다(`D23`).
        assertThat(service.find(ownerA, productA)).isEmpty();
    }

    @Test
    @DisplayName("남의 상품 사진은 못 본다")
    void 남의_상품_사진은_못_본다() {
        service.upload(ownerA, productA, jpeg("a.jpg", 100, 100));

        // 보는 권한과 지우는 권한을 가르지 않는다 — 가르면 보이는데 못 지우는 줄이 화면에 생긴다.
        assertThatThrownBy(() -> service.find(ownerB, productA))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PRODUCT_FORBIDDEN);
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
