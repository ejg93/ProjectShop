package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.product.ProductService;

/**
 * 상품 사진 업로드 입구를 <b>실제 HTTP 로</b> 잰다({@code Q97}).
 *
 * <h2>왜 MockMvc 로는 안 되나</h2>
 *
 * <p>MockMvc 는 멀티파트 봉투를 테스트가 직접 만들어서 {@code spring.servlet.multipart} 의
 * 크기 상한을 안 지난다. 그래서 <b>그 상한이 Boot 기본값 1 MB 인 채로</b> 있었는데도
 * 서비스를 직접 부르는 시험은 전부 초록이었다 — 5 MiB 를 문서·코드·DB 세 자리에 박아 놓고
 * <b>실제로는 1 MB 에서 끊기고 있었다</b>(마무리 26차 독립 리뷰가 찾았다).
 *
 * <h2>저장소를 여기서 띄운다</h2>
 *
 * <p>{@link HttpTestBase} 는 저장소가 없고 {@code StorageTestBase} 는 실제 HTTP 가 없다.
 * 둘이 다 필요한 자리라 이 클래스가 컨테이너를 든다.
 */
// **`@SpringBootTest` 를 다시 안 단다.** 하위 클래스에 달면 바탕의 `webEnvironment` 가
// 통째로 사라져서 실제 서버를 안 띄우고 `local.server.port` 가 없다고 죽는다(`stack.md`).
@TestPropertySource(properties = "shop.storage.bootstrap=true")
@DisplayName("상품 사진 업로드 입구")
class SellerProductImageApiTest extends HttpTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    /** 저장소 컨테이너는 {@link StorageTestBase} 의 것을 같이 쓴다 — 이미지 좌표가 한 자리여야 컴포즈와 같이 갈린다 */
    private static final GenericContainer<?> S3MOCK = StorageTestBase.S3MOCK;

    /** fork 마다 하나. 재사용 컨테이너가 지난 실행의 버킷을 보여 주는 것을 막는다 */
    private static final String PUBLIC_BUCKET = "api-public-" + ProcessHandle.current().pid();

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcClient jdbc;

    private long productId;
    private long sellerId;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("shop.storage.endpoint", StorageTestBase::s3Url);
        registry.add("shop.storage.access-key", () -> "s3mock");
        registry.add("shop.storage.secret-key", () -> "s3mock");
        registry.add("shop.storage.public-bucket", () -> PUBLIC_BUCKET);
        registry.add("shop.storage.private-bucket", () -> "api-private-" + ProcessHandle.current().pid());
    }

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("api-img", "업로드셀러");
        fixture.verifySeller(sellerId);

        long owner = fixture.insertUser(EMAIL_PREFIX + "img-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        productId = productService.create(owner, tshirt(sellerId)).productId();
    }

    /** 이 바탕은 롤백이 없다. 만든 것을 직접 지운다 — 상품이 계정을 restrict 로 가리킨다 */
    @AfterEach
    void 만든_것을_지운다() {
        jdbc.sql("delete from product_image where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from sku_option_value where sku_id in (select sku_id from sku where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from sku_stock_movement where sku_id in (select sku_id from sku where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from sku_stock where sku_id in (select sku_id from sku where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from product_substantiation where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from sku where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from product_option_value where product_option_id in "
                + "(select product_option_id from product_option where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from product_option where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from product where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from seller_member where seller_id = :id").param("id", sellerId).update();
        jdbc.sql("delete from seller where seller_id = :id").param("id", sellerId).update();
    }

    @Test
    @DisplayName("셀러가 올리면 201 이다")
    void 셀러가_올리면_201_이다() {
        Session session = logIn();

        Response response = session.postFile(
                "/api/seller/products/" + productId + "/images", "file", "photo.jpg", jpeg(80, 60));

        assertThat(response.is(201))
                .as("실제 상태 코드는 %s 였다", response.status())
                .isTrue();
    }

    /**
     * <b>이것이 이 청크의 통과 기준이다.</b> 봉투 상한이 앱 검증보다 낮으면
     * 우리가 정한 413 이 아니라 {@code MaxUploadSizeExceededException} 으로 끊긴다 —
     * 실제로 기본값 1 MB 인 채로 있었고 서비스를 직접 부르는 시험은 그것을 못 봤다.
     */
    @Test
    @DisplayName("1 MB 를 넘는 사진도 봉투를 지나 앱까지 온다")
    void 큰_사진도_봉투를_지난다() {
        Session session = logIn();

        byte[] big = noisyJpeg(2400, 1800);
        assertThat(big.length)
                .as("봉투 상한을 재려면 1 MB 는 넘어야 한다")
                .isGreaterThan(1024 * 1024);
        assertThat(big.length)
                .as("앱 검증(5 MiB)은 통과해야 한다")
                .isLessThan(5 * 1024 * 1024);

        Response response = session.postFile(
                "/api/seller/products/" + productId + "/images", "file", "big.jpg", big);

        assertThat(response.is(201))
                .as("실제 상태 코드는 %s 였다 — 413 이면 봉투 상한이 앱 검증보다 낮다", response.status())
                .isTrue();
    }

    @Test
    @DisplayName("내용이 이미지가 아니면 415 다")
    void 내용이_이미지가_아니면_415_다() {
        Session session = logIn();

        Response response = session.postFile("/api/seller/products/" + productId + "/images",
                "file", "photo.jpg", "이건 그냥 글자다".getBytes(StandardCharsets.UTF_8));

        assertProblem(response, 415, "image-type-not-allowed");
    }

    @Test
    @DisplayName("로그인 없이는 못 올린다")
    void 로그인_없이는_못_올린다() {
        Session session = newSession();
        session.get("/api/health");

        Response response = session.postFile(
                "/api/seller/products/" + productId + "/images", "file", "photo.jpg", jpeg(40, 30));

        assertProblem(response, 401, "unauthenticated");
    }

    /**
     * <b>앱 검증이 봉투보다 먼저 걸리는지를 잰다.</b> 5 MiB 를 한 바이트 넘기면
     * 우리가 정한 413 이 나가야 한다 — 봉투 상한(6MB)을 한 뼘 크게 잡아 둬서다.
     *
     * <p>진짜 사진을 안 쓴다. {@code ProductImageService} 가 <b>크기를 형식보다 먼저</b>
     * 본다. 무작위 바이트는 크기가 정확해서 봉투 상한과 앱 상한 사이의 좁은 구간을
     * 맞히는 데 운이 안 낀다 — 사진은 압축 결과를 미리 못 정한다.
     */
    @Test
    @DisplayName("5 MiB 를 넘으면 413 이다")
    void 오_메비바이트를_넘으면_413_이다() {
        Session session = logIn();

        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        new java.util.Random(2).nextBytes(tooBig);

        Response response = session.postFile(
                "/api/seller/products/" + productId + "/images", "file", "too-big.jpg", tooBig);

        assertProblem(response, 413, "image-too-large");
    }

    /**
     * 열한 장째는 422 다. 장수 제한은 우리 규칙이라 HTTP 가 뜻을 정해 둔 코드가 없다.
     *
     * <p><b>열 장을 행으로만 채운다.</b> 실제로 열 번 올리면 저장소 왕복이 스무 번인데,
     * 이 시험이 재는 것은 저장 경로가 아니라 <b>장수를 세는 자리와 그 응답</b>이다 —
     * 저장 경로는 201 을 보는 시험 둘이 이미 지난다.
     */
    @Test
    @DisplayName("열한 장째는 422 다")
    void 열한_장째는_422_다() {
        Session session = logIn();
        fillImageRows(10);

        Response response = session.postFile("/api/seller/products/" + productId + "/images",
                "file", "eleventh.jpg", jpeg(40, 30));

        assertProblem(response, 422, "image-limit-reached");
    }

    @Test
    @DisplayName("셀러가 자기 사진을 지우면 204 다")
    void 셀러가_자기_사진을_지우면_204_다() {
        Session session = logIn();
        long productImageId = uploadOne(session);

        Response response = session.delete("/api/seller/products/images/" + productImageId);

        assertThat(response.is(204))
                .as("실제 상태 코드는 %s 였다 — 본문: %s", response.status(), response.body())
                .isTrue();
        assertThat(imageCount())
                .as("행이 남아 있으면 지운 것이 아니다")
                .isZero();
    }

    /**
     * 남의 사진은 못 지운다. <b>로그인은 됐는데 그 셀러 소속이 아닌 계정</b>이라
     * 401 이 아니라 403 이다.
     */
    @Test
    @DisplayName("남의 사진은 못 지운다")
    void 남의_사진은_못_지운다() {
        long productImageId = uploadOne(logIn());

        Response response = strangerSession().delete("/api/seller/products/images/" + productImageId);

        assertProblem(response, 403, "product-forbidden");
        assertThat(imageCount())
                .as("거부됐는데 행이 사라지면 안 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("로그인 없이는 못 지운다")
    void 로그인_없이는_못_지운다() {
        long productImageId = uploadOne(logIn());

        Session session = newSession();
        session.get("/api/health");

        Response response = session.delete("/api/seller/products/images/" + productImageId);

        assertProblem(response, 401, "unauthenticated");
    }

    /**
     * <b>가입 입구로 실계정을 만든다.</b> 비밀번호 해시를 SQL 로 밀어 넣으면 그 형식이
     * 바뀌는 날 이 시험만 조용히 낡는다 — 로그인이 실제로 되는 계정을 쓰는 편이 싸다.
     */
    private Session logIn() {
        Session session = newSession();
        session.get("/api/health");

        String email = EMAIL_PREFIX + "img-seller@test.local";
        session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "업로드",
                  "birth_date": "1990-01-01",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(email, PASSWORD));

        long userId = jdbc.sql("select user_id from app_user where email = :email")
                .param("email", email)
                .query(Long.class)
                .single();
        AuthFixture fixture = new AuthFixture(jdbc);
        fixture.joinSeller(sellerId, userId);
        fixture.grantOrg(userId, "seller_owner", sellerId);

        session.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD));
        return session;
    }

    /** 사진 하나를 실제로 올리고 그 번호를 돌려준다. 지우기 시험의 준비다 */
    private long uploadOne(Session session) {
        Response response = session.postFile("/api/seller/products/" + productId + "/images",
                "file", "photo.jpg", jpeg(80, 60));

        assertThat(response.is(201))
                .as("준비가 실패했다 — 실제 상태 코드는 %s 였다", response.status())
                .isTrue();

        return jdbc.sql("select product_image_id from product_image where product_id = :id")
                .param("id", productId)
                .query(Long.class)
                .single();
    }

    /** 로그인은 됐지만 이 셀러와 아무 관계가 없는 계정. 403 과 401 을 가르는 자리다 */
    private Session strangerSession() {
        Session session = newSession();
        session.get("/api/health");

        String email = EMAIL_PREFIX + "img-stranger@test.local";
        session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "남",
                  "birth_date": "1990-01-01",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(email, PASSWORD));

        session.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, PASSWORD));
        return session;
    }

    /**
     * 사진 행을 그 수만큼 채운다. <b>저장소에는 아무것도 안 올린다</b> —
     * 장수를 세는 자리만 재는 준비라 파일이 실재할 필요가 없다.
     */
    private void fillImageRows(int count) {
        for (int index = 0; index < count; index++) {
            String folder = "product/q105-" + productId + "-" + index;
            jdbc.sql("""
                            insert into product_image
                                (product_id, object_key, thumbnail_key, original_name,
                                 content_type, byte_size, sort_no)
                            values (:productId, :objectKey, :thumbnailKey, :originalName,
                                    'image/jpeg', 1024, :sortNo)
                            """)
                    .param("productId", productId)
                    .param("objectKey", folder + "/original.jpg")
                    .param("thumbnailKey", folder + "/thumbnail.jpg")
                    .param("originalName", "채움-" + index + ".jpg")
                    .param("sortNo", index)
                    .update();
        }
    }

    private int imageCount() {
        return jdbc.sql("select count(*) from product_image where product_id = :id")
                .param("id", productId)
                .query(Integer.class)
                .single();
    }

    /**
     * 오류 응답의 <b>본문</b>까지 잰다({@code D5} 「오류 응답」).
     *
     * <p><b>슬러그를 글자 그대로 박는다.</b> {@link com.projectshop.shop.error.ErrorCode} 를
     * 참조하면 그 값을 바꿔도 시험이 같이 따라가서 <b>계약이 바뀐 것을 아무도 못 본다</b> —
     * 화면은 상태 코드가 아니라 이 값으로 분기한다.
     */
    private static void assertProblem(Response response, int status, String slug) {
        assertThat(response.is(status))
                .as("실제 상태 코드는 %s 였다 — 본문: %s", response.status(), response.body())
                .isTrue();

        assertThat(response.body())
                .as("본문이 RFC 9457 형식이 아니다")
                .contains("\"type\":\"tag:projectshop.example,2026:error:" + slug + "\"")
                .contains("\"trace_id\":");
    }

    private static ProductService.Command tshirt(long sellerId) {
        return new ProductService.Command(
                sellerId, "티셔츠", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)));
    }

    private static byte[] jpeg(int width, int height) {
        return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB));
    }

    /** 단색은 JPEG 가 거의 0 바이트로 접는다. 봉투 상한을 재려면 안 접히는 그림이 필요하다 */
    private static byte[] noisyJpeg(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(1);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return encode(image);
    }

    private static byte[] encode(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "jpeg", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
