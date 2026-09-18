package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.product.ProductService;

/**
 * 저작권 신고 입구를 <b>실제 HTTP 로</b> 잰다({@code Q99}, {@code D2} {@code R42}).
 *
 * <h2>왜 HTTP 여야 하나</h2>
 *
 * <p>「접수는 로그인을 안 받는다」가 <b>법 요건</b>이다(저작권법 제102조 — 절차가 있어야
 * 온라인서비스제공자 책임 제한을 받는다). 그런데 그것을 재던 것이
 * {@code ArchitectureTest} 의 <b>정적 이름 목록</b>과 문서뿐이라,
 * {@code SecurityConfig} 의 {@code permitAll} 줄을 지워도 전부 초록이었다 —
 * <b>법 요건인데 강제 지점이 실질 5위</b>였다(마무리 26차 독립 리뷰).
 *
 * <p>서비스를 직접 부르면 보안 체인을 안 지나서 이 자리를 못 잰다.
 */
@DisplayName("저작권 신고 입구")
class CopyrightReportApiTest extends HttpTestBase {

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcClient jdbc;

    private long imageId;
    private long productId;
    private long sellerId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("api-cr", "신고셀러");
        fixture.verifySeller(sellerId);

        long owner = fixture.insertUser(EMAIL_PREFIX + "cr-owner@test.local", "사장");
        fixture.joinSeller(sellerId, owner);
        fixture.grantOrg(owner, "seller_owner", sellerId);

        productId = productService.create(owner, tshirt(sellerId)).productId();

        // **사진 행을 직접 넣는다.** 이 시험이 재는 것은 <b>보안 경계</b>지 저장소가 아니다 —
        // 업로드를 부르면 MinIO 컨테이너가 필요해지고, 그러면 이 바탕이 그것까지 띄워야 한다.
        imageId = jdbc.sql("""
                        insert into product_image
                            (product_id, object_key, thumbnail_key, original_name,
                             content_type, byte_size)
                        values (:id, :key, :thumb, :name, :type, :size)
                        returning product_image_id
                        """)
                .param("id", productId)
                .param("key", "product/api-cr/original.jpg")
                .param("thumb", "product/api-cr/thumbnail.jpg")
                .param("name", "photo.jpg")
                .param("type", "image/jpeg")
                .param("size", 100L)
                .query(Long.class)
                .single();
    }

    /**
     * <b>이 바탕은 롤백이 없다.</b> 만든 것을 직접 지운다 — 상품이 계정을 {@code restrict} 로
     * 가리켜서, 안 지우면 바탕의 계정 정리가 외래키에서 막힌다.
     *
     * <p>바깥쪽부터 지운다. {@code @AfterEach} 는 <b>하위 클래스가 먼저</b> 돌아서
     * 바탕의 정리보다 앞선다.
     */
    @org.junit.jupiter.api.AfterEach
    void 만든_것을_지운다() {
        jdbc.sql("delete from copyright_report where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from product_image where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from sku_option_value where sku_id in (select sku_id from sku where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from sku_stock where sku_id in (select sku_id from sku where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from sku where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from product_option_value where product_option_id in "
                + "(select product_option_id from product_option where product_id = :id)")
                .param("id", productId).update();
        jdbc.sql("delete from product_option where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from product where product_id = :id").param("id", productId).update();
        jdbc.sql("delete from seller_member where seller_id = :id").param("id", sellerId).update();
        jdbc.sql("delete from seller where seller_id = :id").param("id", sellerId).update();
    }

    /** <b>세션 없이 넣는다.</b> 저작권자가 우리 회원일 이유가 없다 */
    @Test
    @DisplayName("로그인 없이 신고를 넣을 수 있다")
    void 로그인_없이_신고할_수_있다() {
        Session session = newSession();
        session.get("/api/health");

        Response response = session.post("/api/copyright-reports/images/" + imageId,
                "{\"reporter_name\":\"권리자\",\"reporter_email\":\"rights@test.local\","
                        + "\"claimed_work\":\"우리 화보 사진이다\"}");

        assertThat(response.is(201))
                .as("실제 상태 코드는 %s 였다", response.status())
                .isTrue();
    }

    /**
     * <b>판정은 반대다.</b> 누가 언제 무엇을 했는지가 증거라 로그인이 필요하다 —
     * 접수만 여는 것이지 이 자원을 통째로 여는 것이 아니다.
     */
    @Test
    @DisplayName("판정은 로그인 없이 못 한다")
    void 판정은_로그인이_필요하다() {
        Session session = newSession();
        session.get("/api/health");

        Response response = session.post("/api/copyright-reports/1/decision",
                "{\"decision\":\"rejected\"}");

        assertThat(response.is(401))
                .as("실제 상태 코드는 %s 였다", response.status())
                .isTrue();
    }

    private static ProductService.Command tshirt(long sellerId) {
        return new ProductService.Command(
                sellerId, "티셔츠", "면 100%", null, false, null, null,
                List.of(new ProductService.OptionCommand("색상", List.of("검정"))),
                List.of(new ProductService.SkuCommand(List.of("검정"), 15000, 10)));
    }
}
