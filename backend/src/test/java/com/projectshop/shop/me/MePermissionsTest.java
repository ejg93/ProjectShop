package com.projectshop.shop.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.PermissionCatalog;
import com.projectshop.shop.auth.PermissionCatalog.Entry;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 판정 엔진이 <b>요청 흐름 안에서</b> 도는 첫 자리다.
 *
 * <p>지금까지 판정은 테스트가 직접 불렀다. 여기서부터는 로그인한 사용자의 요청이 판정을 지나간다.
 * 1차 점검이 최대 리스크로 짚은 지점이라, 목록이 <b>실제 초기 데이터 위에서</b> 맞는지를 본다.
 */
@AutoConfigureMockMvc
class MePermissionsTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PermissionCatalog catalog;

    AuthFixture fixture;
    long customer;
    long owner;
    long alpha;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        alpha = fixture.insertSeller("alpha", "알파상회");

        customer = fixture.insertUser("cat-customer@test.local", "고객");
        fixture.grantGlobal(customer, "customer");

        owner = fixture.insertUser("cat-owner@test.local", "사장");
        fixture.joinSeller(alpha, owner);
        fixture.grantOrg(owner, "seller_owner", alpha);
    }

    @Nested
    @DisplayName("목록 계산")
    class Listing {

        @Test
        @DisplayName("고객은 자기 주문만 본다")
        void customerSeesOwnOrdersOnly() {
            assertThat(scopesOf(catalog.listFor(customer), "order", "read"))
                    .as("all 이 열리면 남의 주문이 보인다")
                    .containsExactly("own");
        }

        @Test
        @DisplayName("상품은 고객도 전부 본다 — 공개 카탈로그다")
        void productCatalogIsOpenToCustomers() {
            assertThat(scopesOf(catalog.listFor(customer), "product", "read"))
                    .as("여기서 own 만 나오면 남의 상품을 못 사게 된다")
                    .contains("all");
        }

        @Test
        @DisplayName("셀러 사장은 자기 셀러 범위를 갖는다")
        void sellerOwnerGetsSellerScope() {
            List<String> scopes = scopesOf(catalog.listFor(owner), "order", "read");

            assertThat(scopes).contains("seller");
        }

        @Test
        @DisplayName("규칙이 하나도 없는 사람은 빈 목록을 받는다")
        void noRolesMeansEmpty() {
            long stranger = fixture.insertUser("cat-stranger@test.local", "역할없음");

            assertThat(catalog.listFor(stranger)).isEmpty();
        }

        @Test
        @DisplayName("거부가 덮은 범위는 목록에서 빠진다")
        void denyRemovesTheScope() {
            // V5 가 판매자의 order:read 에 deny/own 을 걸어 뒀다.
            // 사장이 자기 계정으로 낸 주문은 셀러 권한으로 못 본다.
            List<String> scopes = scopesOf(catalog.listFor(owner), "order", "read");

            assertThat(scopes)
                    .as("deny/own 이 걸린 자리라 own 이 열리면 거부 규칙이 안 도는 것이다")
                    .doesNotContain("own");
        }

        @Test
        @DisplayName("필드 제한이 목록에 실린다")
        void carriesFieldGroups() {
            Entry orderRead = entryOf(catalog.listFor(owner), "order", "read");

            assertThat(orderRead.visibleFieldGroups())
                    .as("셀러의 order:read 는 payment 가 빠져 있다(4d)")
                    .isNotEmpty()
                    .doesNotContain("payment");
        }

        @Test
        @DisplayName("목록을 뽑아도 거부가 감사 로그에 쌓이지 않는다")
        void doesNotFloodTheAuditLog() {
            Long before = auditCount(customer);

            catalog.listFor(customer);

            assertThat(auditCount(customer))
                    .as("decide() 를 쓰면 목록 한 번에 거부가 수십 건 쌓인다")
                    .isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("엔드포인트")
    class Endpoint {

        @Test
        @DisplayName("로그인 안 하면 못 본다")
        void requiresLogin() throws Exception {
            mvc.perform(get("/api/me/permissions")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("snake_case 로 내려간다")
        void respondsInSnakeCase() throws Exception {
            mvc.perform(get("/api/me/permissions").with(user(principalOf(customer))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user_id").value(customer))
                    .andExpect(jsonPath("$.permissions[0].resource").exists())
                    .andExpect(jsonPath("$.permissions[0].visible_field_groups").exists());
        }
    }

    private static ShopUser principalOf(long userId) {
        return new ShopUser(userId, "cat-customer@test.local", "{noop}x", true);
    }

    private Long auditCount(long userId) {
        return jdbc.sql("select count(*) from audit_log where actor_user_id = :id")
                .param("id", userId)
                .query(Long.class)
                .single();
    }

    private static Entry entryOf(List<Entry> entries, String resource, String action) {
        return entries.stream()
                .filter(e -> e.resource().equals(resource) && e.action().equals(action))
                .findFirst()
                .orElseThrow(() -> new AssertionError(resource + ":" + action + " 이 목록에 없다"));
    }

    private static List<String> scopesOf(List<Entry> entries, String resource, String action) {
        return entryOf(entries, resource, action).scopes();
    }
}
