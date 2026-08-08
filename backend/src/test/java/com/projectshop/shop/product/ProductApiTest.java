package com.projectshop.shop.product;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
                                    {"option_values": ["검정"], "price": 15000, "stock_count": 10},
                                    {"option_values": ["흰색"], "price": 15000, "stock_count": 5}
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
}
