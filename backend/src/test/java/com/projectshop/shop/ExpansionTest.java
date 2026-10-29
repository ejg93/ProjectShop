package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.order.OrderFixture;

/**
 * {@code expand} 가 상세에 하위 목록의 첫 쪽을 끼운다(`Q309b`, `api-guidelines.md` 「필드 선택·확장」).
 *
 * <p><b>끼운 것은 하위 입구의 첫 쪽과 같은 본문이다.</b> 같은 질의를 같은 기본 쪽으로 부르기 때문이고,
 * 다르면 표를 다시 읽는 두 번째 경로가 생긴 것이다 — 판정을 지나칠 수 있는 자리다.
 */
@DisplayName("응답 확장")
class ExpansionTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private long sellerId;
    private ShopUser owner;
    private ShopUser stranger;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        sellerId = fixture.insertSeller("ex-a", "확장셀러");
        fixture.verifySeller(sellerId);
        long ownerId = fixture.insertUser("ex-owner@test.local", "사장");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);
        owner = new ShopUser(ownerId, "ex-owner@test.local", null, true);

        long otherSeller = fixture.insertSeller("ex-b", "남의셀러");
        long strangerId = fixture.insertUser("ex-stranger@test.local", "남");
        fixture.joinSeller(otherSeller, strangerId);
        fixture.grantOrg(strangerId, "seller_owner", otherSeller);
        stranger = new ShopUser(strangerId, "ex-stranger@test.local", null, true);
    }

    @Test
    @DisplayName("상품 상세에 후기와 문의의 첫 쪽이 하위 입구와 같은 본문으로 끼인다")
    void productDetailEmbedsReviewsAndInquiries() throws Exception {
        long productId = createOnSaleProduct();

        JsonNode detail = body(get("/api/products/" + productId).param("expand", "reviews,inquiries"));

        assertThat(detail.path("reviews")).isEqualTo(body(get("/api/products/" + productId + "/reviews")));
        assertThat(detail.path("inquiries")).isEqualTo(body(get("/api/products/" + productId + "/inquiries")));
        assertThat(detail.path("reviews").path("limit").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("웹훅 상세에 발송 기록의 첫 쪽이 끼인다")
    void webhookDetailEmbedsDeliveries() throws Exception {
        long endpointId = jdbc.sql("""
                        insert into webhook_endpoint (seller_id, url, event_types, secret_ciphertext, secret_key_version)
                        values (:sellerId, 'https://example.test/x',
                                cast('{shop.seller_order.status_changed}' as text[]), '\\x00', 1)
                        returning webhook_endpoint_id
                        """)
                .param("sellerId", sellerId).query(Long.class).single();
        String path = "/api/seller/webhooks/" + endpointId;

        JsonNode detail = body(get(path).param("expand", "deliveries").with(user(owner)));

        assertThat(detail.path("deliveries")).isEqualTo(body(get(path + "/deliveries").with(user(owner))));
    }

    @Test
    @DisplayName("정산서 상세에 줄의 첫 쪽이 끼인다")
    void settlementDetailEmbedsLines() throws Exception {
        String path = "/api/settlements/" + insertSettlement();

        JsonNode detail = body(get(path).param("expand", "lines").with(user(owner)));

        assertThat(detail.path("lines")).isEqualTo(body(get(path + "/lines").with(user(owner))));
    }

    @Test
    @DisplayName("권한이 없으면 확장해도 상세와 같은 404 다")
    void strangerGetsNotFoundEvenWithExpand() throws Exception {
        String path = "/api/settlements/" + insertSettlement();

        mvc.perform(get(path).param("expand", "lines").with(user(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.lines").doesNotExist());
    }

    @Test
    @DisplayName("모르는 이름은 400 이고 expand 칸을 짚는다")
    void unknownNameIsRejected() throws Exception {
        long productId = createOnSaleProduct();

        mvc.perform(get("/api/products/" + productId).param("expand", "reviews,orders"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("expand"));
    }

    @Test
    @DisplayName("안 보내면 하위 목록 칸이 없다")
    void absentMeansNoEmbeddedList() throws Exception {
        long productId = createOnSaleProduct();

        mvc.perform(get("/api/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviews").doesNotExist())
                .andExpect(jsonPath("$.inquiries").doesNotExist());
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        String content = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(content);
    }

    private String insertSettlement() {
        long cycleId = jdbc.sql("""
                        insert into settlement_cycle (period_start, period_end, payout_date)
                        values (date '2019-01-01', date '2019-01-31', date '2019-02-10')
                        returning settlement_cycle_id
                        """)
                .query(Long.class).single();
        String number = "T-" + OrderFixture.sellerOrderNumber().substring(2);
        jdbc.sql("""
                        insert into settlement (settlement_number, settlement_cycle_id, seller_id, payout_amount)
                        values (:number, :cycleId, :sellerId, 10000)
                        """)
                .param("number", number).param("cycleId", cycleId).param("sellerId", sellerId).update();
        return number;
    }

    private long createOnSaleProduct() throws Exception {
        String body = mvc.perform(post("/api/products").with(user(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seller_id": %d,
                                  "name": "확장 티셔츠",
                                  "options": [{"name": "색상", "values": ["검정"]}],
                                  "skus": [{"option_values": ["검정"], "price_incl_vat": 15000, "stock_count": 10}]
                                }
                                """.formatted(sellerId)))
                .andReturn().getResponse().getContentAsString();
        long productId = Long.parseLong(body.replaceAll(".*\"product_id\"\\s*:\\s*(\\d+).*", "$1"));
        jdbc.sql("update product set status = 'on_sale' where product_id = :id").param("id", productId).update();
        return productId;
    }
}
