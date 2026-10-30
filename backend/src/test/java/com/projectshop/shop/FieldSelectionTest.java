package com.projectshop.shop;

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

import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * {@code fields} 로 응답의 최상위 칸을 고른다(`Q309a`, `api-guidelines.md` 「필드 선택·확장」).
 *
 * <p><b>마스킹 뒤에 건다.</b> 판정이 깎은 칸은 {@code fields} 로 이름을 불러도 안 열린다 —
 * 여기가 뚫리면 {@code fields} 가 권한을 지나치는 두 번째 입구가 된다.
 *
 * <p>{@link PagingResolverTest} 와 같은 층이다 — {@code MockMvc} 가 {@code ResponseBodyAdvice} 를 그대로 태운다.
 */
@DisplayName("응답 필드 선택")
class FieldSelectionTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    private AuthFixture fixture;
    private ShopUser customer;
    private ShopUser auditor;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        long customerId = fixture.insertUser("fs-customer@test.local", "고객");
        fixture.grantGlobal(customerId, "customer");
        customer = new ShopUser(customerId, "fs-customer@test.local", null, true);

        long auditorId = fixture.insertUser("fs-auditor@test.local", "감사자");
        fixture.grantGlobal(auditorId, "auditor");
        auditor = new ShopUser(auditorId, "fs-auditor@test.local", null, true);
    }

    @Test
    @DisplayName("상세에서 고른 칸만 오고 두 단어 칸은 snake_case 로 부른다")
    void detailKeepsOnlyChosenFields() throws Exception {
        mvc.perform(get("/api/me").param("fields", "display_name,created_at").with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("고객"))
                .andExpect(jsonPath("$.created_at").exists())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.user_id").doesNotExist());
    }

    @Test
    @DisplayName("무엇이 보이는지 알리는 칸은 고르지 않아도 남는다")
    void keepsVisibleFieldGroups() throws Exception {
        mvc.perform(get("/api/me").param("fields", "display_name").with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._visible_field_groups").isArray());
    }

    @Test
    @DisplayName("판정이 깎은 칸은 이름을 불러도 안 열린다")
    void cannotReopenMaskedField() throws Exception {
        // V7 이 감사자의 user:read 를 basic 으로만 묶었다 — email 은 판정이 이미 깎았다
        mvc.perform(get("/api/me").param("fields", "email,display_name").with(user(auditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("감사자"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    @DisplayName("목록은 items 의 원소에 걸고 껍데기 넷은 그대로 둔다")
    void listFiltersItemsAndKeepsEnvelope() throws Exception {
        createOnSaleProduct();

        mvc.perform(get("/api/products").param("fields", "name"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("고르기 티셔츠"))
                .andExpect(jsonPath("$.items[0].product_id").doesNotExist())
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.limit").value(20))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    @DisplayName("모르는 이름은 400 이고 fields 칸을 짚는다")
    void unknownNameIsRejected() throws Exception {
        mvc.perform(get("/api/me").param("fields", "display_name,password_hash").with(user(customer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("fields"));
    }

    @Test
    @DisplayName("안 보내면 응답이 그대로다")
    void absentMeansUnchanged() throws Exception {
        mvc.perform(get("/api/me").with(user(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").exists())
                .andExpect(jsonPath("$.email").value("fs-customer@test.local"));
    }

    private void createOnSaleProduct() throws Exception {
        long sellerId = fixture.insertSeller("fs-a", "고르기셀러");
        fixture.verifySeller(sellerId);
        long ownerId = fixture.insertUser("fs-owner@test.local", "사장");
        fixture.joinSeller(sellerId, ownerId);
        fixture.grantOrg(ownerId, "seller_owner", sellerId);
        ShopUser owner = new ShopUser(ownerId, "fs-owner@test.local", null, true);

        String body = mvc.perform(post("/api/products").with(user(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seller_id": %d,
                                  "name": "고르기 티셔츠",
                                  "options": [{"name": "색상", "values": ["검정"]}],
                                  "skus": [{"option_values": ["검정"], "price_incl_vat": 15000, "stock_count": 10}]
                                }
                                """.formatted(sellerId)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long productId = Long.parseLong(body.replaceAll(".*\"product_id\"\\s*:\\s*(\\d+).*", "$1"));
        jdbc.sql("update product set status = 'on_sale' where product_id = :id")
                .param("id", productId)
                .update();
    }
}
