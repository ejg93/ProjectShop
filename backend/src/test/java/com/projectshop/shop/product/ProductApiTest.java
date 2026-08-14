package com.projectshop.shop.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 상품 API 의 <b>JSON 경계</b>.
 *
 * <p>{@code ProductServiceTest} 는 서비스를 직접 부르므로 요청 본문이 record 로 바뀌는 구간을 안 밟는다.
 * 중첩 배열이 들어간 첫 요청이라 그 구간이 실제로 도는지 여기서 본다.
 */
@AutoConfigureMockMvc
@DisplayName("상품 API")
class ProductApiTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private long sellerId;
    private ShopUser owner;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);

        sellerId = fixture.insertSeller("api-a", "A셀러");
        // 신원정보가 확인돼야 판매를 시작할 수 있다. 트리거가 막는다(`3c`).
        fixture.verifySeller(sellerId);

        long ownerId = fixture.insertUser("api-owner@test.local", "A사장");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);

        owner = new ShopUser(ownerId, "api-owner@test.local", null, true);
    }

    @Test
    @DisplayName("중첩 배열이 들어간 요청이 record 로 바뀐다")
    void bindsNestedRequest() throws Exception {
        mvc.perform(post("/api/products").with(user(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seller_id": %d,
                                  "name": "티셔츠",
                                  "options": [{"name": "색상", "values": ["검정", "흰색"]}],
                                  "skus": [
                                    {"option_values": ["검정"], "price_incl_vat": 15000, "stock_count": 10},
                                    {"option_values": ["흰색"], "price_incl_vat": 15000, "stock_count": 5}
                                  ]
                                }
                                """.formatted(sellerId)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.product_id").isNumber())
                .andExpect(jsonPath("$.sku_ids.length()").value(2));
    }

    @Test
    @DisplayName("조합이 없으면 422 고 type 이 실린다")
    void rejectsProductWithoutSku() throws Exception {
        mvc.perform(post("/api/products").with(user(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"seller_id": %d, "name": "빈 상품", "options": [], "skus": []}
                                """.formatted(sellerId)))
                // skus 가 @NotEmpty 라 Bean Validation 이 먼저 잡는다.
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'skus')]").exists());
    }

    /**
     * 상세를 열면서 <b>같은 경로의 쓰기까지 열리지 않았는지</b>를 본다.
     *
     * <p>{@code /api/products/{id}} 에는 조회 말고 수정·삭제도 걸려 있다. 경로만으로 공개하면
     * <b>비로그인이 남의 상품을 고치고 지운다.</b> 그래서 메서드까지 묶어서 열었고,
     * 그 조건이 유지되는지는 이 테스트가 유일한 방벽이다.
     */
    @Test
    @DisplayName("상세는 비로그인도 보지만 고치고 지우는 것은 여전히 막힌다")
    void opensDetailForReadOnly() throws Exception {
        long productId = createOnSaleProduct();

        mvc.perform(get("/api/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("공개 티셔츠"))
                .andExpect(jsonPath("$.options[0].values.length()").value(2))
                .andExpect(jsonPath("$.skus[0].in_stock").value(true));

        mvc.perform(put("/api/products/" + productId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete("/api/products/" + productId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("검수 경로는 별 하나에 안 걸려서 그대로 막힌다")
    void keepsReviewPathsClosed() throws Exception {
        long productId = createOnSaleProduct();

        // `/api/products/*` 가 한 칸만 덮는다는 전제가 깨지면 검수가 통째로 열린다.
        mvc.perform(post("/api/products/" + productId + "/approve").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    /** 공개 조회 대상이 되려면 파는 중이어야 한다. 검수(7c)가 아직 없어서 상태를 직접 올린다 */
    private long createOnSaleProduct() throws Exception {
        String body = mvc.perform(post("/api/products").with(user(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seller_id": %d,
                                  "name": "공개 티셔츠",
                                  "options": [{"name": "색상", "values": ["검정", "흰색"]}],
                                  "skus": [
                                    {"option_values": ["검정"], "price_incl_vat": 15000, "stock_count": 10},
                                    {"option_values": ["흰색"], "price_incl_vat": 18000, "stock_count": 4}
                                  ]
                                }
                                """.formatted(sellerId)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long productId = Long.parseLong(body.replaceAll(".*\"product_id\"\\s*:\\s*(\\d+).*", "$1"));
        jdbc.sql("update product set status = 'on_sale' where product_id = :id")
                .param("id", productId)
                .update();
        return productId;
    }
}
