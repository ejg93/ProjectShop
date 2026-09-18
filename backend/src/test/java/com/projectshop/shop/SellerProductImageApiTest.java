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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.utility.DockerImageName;

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

    private static final MinIOContainer MINIO = new MinIOContainer(DockerImageName
            .parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
            .asCompatibleSubstituteFor("minio/minio"))
            .withReuse(true);

    static {
        MINIO.start();
    }

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
        registry.add("shop.storage.endpoint", MINIO::getS3URL);
        registry.add("shop.storage.access-key", MINIO::getUserName);
        registry.add("shop.storage.secret-key", MINIO::getPassword);
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

        assertThat(response.is(415))
                .as("실제 상태 코드는 %s 였다", response.status())
                .isTrue();
    }

    @Test
    @DisplayName("로그인 없이는 못 올린다")
    void 로그인_없이는_못_올린다() {
        Session session = newSession();
        session.get("/api/health");

        Response response = session.postFile(
                "/api/seller/products/" + productId + "/images", "file", "photo.jpg", jpeg(40, 30));

        assertThat(response.is(401))
                .as("실제 상태 코드는 %s 였다", response.status())
                .isTrue();
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
