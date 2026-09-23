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
 * <p><b>저장소 주소를 알아도 안 열린다.</b> 여는 방법이 앱이 내준 서명 하나뿐인 것을
 * 여기서 잰다 — 서명을 떼면 거절이고, 붙이면 열린다.
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
     * <b>이것이 이 청크의 통과 기준이다.</b> 저장소 주소를 알아도 서명이 없으면 안 열린다 —
     * URL 을 추측해서 남의 파일을 여는 길이 하나도 없다는 뜻이다.
     */
    @Test
    @DisplayName("서명을 떼면 같은 주소가 안 열린다")
    void 서명을_떼면_안_열린다() throws IOException, InterruptedException {
        String signed = productQuery.findPublicDetail(productId).imageUrls().getFirst();
        String bare = signed.substring(0, signed.indexOf('?'));

        assertThat(status(bare)).isIn(401, 403);
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
