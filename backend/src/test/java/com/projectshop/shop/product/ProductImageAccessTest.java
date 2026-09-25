package com.projectshop.shop.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.StorageTestBase;
import com.projectshop.shop.support.ImagePipeline;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 파일 접근 판정({@code 28}, {@code media-rules.md} 「여는 법」).
 *
 * <h2>무엇이 통과 기준인가</h2>
 *
 * <p><b>저장소 주소를 알아도 안 열린다.</b> 여는 방법이 앱이 내준 서명 하나뿐이다. 여기서 재는 것은
 * <b>우리 몫</b> — 내려주는 주소마다 서명과 만료가 붙어 있고, 붙이면 열린다. <b>서명을 뗀 주소를 저장소가
 * 거절하나는 저장소 몫</b>이라 여기서 못 잰다({@code Q229}): S3Mock 은 자격 증명을 안 봐서 200 이고,
 * MinIO 였을 때 그 시험이 재던 것은 우리 코드가 아니라 MinIO 의 기본 정책이었다. 그 반쪽은
 * {@code scripts/deploy-check.sh} 가 운영 R2 에서 잰다 — 배포 뒤 서명 뗀 주소 하나를 불러 2xx 가 아닌지 본다(R2 는 400 InvalidArgument).
 *
 * <p>공개 갈래라도 <b>버킷은 비공개</b>다. 「공개」는 누가 보느냐의 말이지
 * 저장소가 열려 있다는 말이 아니다.
 */
@DisplayName("상품 사진 접근")
class ProductImageAccessTest extends StorageTestBase {

    @Autowired
    private ProductImageService imageService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductQuery productQuery;

    @Autowired
    private JdbcClient jdbc;

    private long productId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        long sellerId = fixture.insertSeller("acc-a", "A셀러");
        fixture.verifySeller(sellerId);
        long owner = fixture.insertUser("acc-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        productId = productService.create(owner, tshirt(sellerId)).productId();
        jdbc.sql("update product set status = :s where product_id = :id")
                .param("s", "on_sale").param("id", productId).update();
        imageService.upload(owner, productId,
                new ImagePipeline.Incoming("photo.jpg", ProductImageFixture.jpegBytes(80, 60)));
    }

    @Test
    @DisplayName("목록이 썸네일 서명 URL 을 실어 보낸다")
    void 목록이_썸네일_서명_URL_을_실어_보낸다() {
        ProductQuery.PublicPage page = productQuery.findPublic(null, null, new Paging(0, 20));

        String url = page.items().stream()
                .filter(item -> item.productId() == productId)
                .findFirst()
                .orElseThrow()
                .thumbnailUrl();

        assertThat(url).contains("/thumbnail.jpg").contains("X-Amz-Signature");
    }

    @Test
    @DisplayName("서명이 붙으면 열린다")
    void 서명이_붙으면_열린다() throws IOException, InterruptedException {
        String url = productQuery.findPublicDetail(productId).imageUrls().getFirst();

        assertThat(status(url)).isEqualTo(200);
    }

    /**
     * <b>이것이 이 청크의 통과 기준이다.</b> 내려주는 주소는 전부 서명과 만료를 달고 있다 —
     * 서명이 없는 주소를 앱이 내주는 순간 저장소가 비공개여도 열리는 길이 생긴다. 저장소가 서명 없는 요청을
     * 실제로 거절하나는 운영에서 {@code deploy-check.sh} 가 본다(클래스 주석).
     */
    @Test
    @DisplayName("내려주는 주소마다 서명과 만료가 붙어 있다")
    void 내려주는_주소마다_서명과_만료가_붙어_있다() {
        List<String> urls = productQuery.findPublicDetail(productId).imageUrls();

        assertThat(urls).isNotEmpty();
        assertThat(urls).allSatisfy(url -> assertThat(url)
                .as("서명 없는 주소가 나갔다 — 저장소 주소를 아는 사람이 그대로 연다")
                .contains("X-Amz-Signature=").contains("X-Amz-Expires="));
    }

    private static int status(String url) throws IOException, InterruptedException {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static ProductService.Command tshirt(long sellerId) {
        return new ProductService.Command(
                sellerId, "티셔츠", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)));
    }
}
