package com.projectshop.shop.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 고객 외 역할인데 2차 인증 표시가 없는 세션을 요청마다 끊는지 잰다(`Q453`, 고시 제6조② `D2` R45).
 *
 * <p><b>로그인 입구만 막으면 세션 도중에 역할을 받은 사람이 샌다.</b> 이 필터가 그 자리를 요청마다 한 곳에서 본다.
 */
@DisplayName("2차 인증 필터")
class SecondFactorFilterTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    AuthFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
    }

    @Test
    @DisplayName("관리자 역할인데 표시 없는 세션은 401 이다")
    void rejectsUnverifiedAdmin() throws Exception {
        long admin = account("filter-admin@test.local", "admin");

        mvc.perform(get("/api/me").with(user(principal(admin, "filter-admin@test.local"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("second-factor-required")));
    }

    @Test
    @DisplayName("표시가 있는 관리자 세션은 지나간다")
    void passesVerifiedAdmin() throws Exception {
        long admin = account("filter-admin@test.local", "admin");

        mvc.perform(get("/api/me").with(user(principal(admin, "filter-admin@test.local").verified())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("고객은 표시 없이 지나간다")
    void passesCustomer() throws Exception {
        long customer = account("filter-customer@test.local", "customer");

        mvc.perform(get("/api/me").with(user(principal(customer, "filter-customer@test.local"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("대행 토큰은 대상이 셀러여도 지나간다")
    void passesImpersonation() throws Exception {
        long admin = account("filter-admin@test.local", "admin");
        long seller = fixture.insertSeller("filter-seller", "필터 셀러");
        long staff = account("filter-staff@test.local", "customer");
        fixture.joinSeller(seller, staff);
        fixture.grantOrg(staff, "seller_staff", seller);

        ShopUser adminUser = principal(admin, "filter-admin@test.local").verified();
        ImpersonationToken token = new ImpersonationToken(principal(staff, "filter-staff@test.local"), admin,
                UsernamePasswordAuthenticationToken.authenticated(adminUser, null, List.of()), OffsetDateTime.now());

        mvc.perform(get("/api/me").with(authentication(token)))
                .andExpect(status().isOk());
    }

    private long account(String email, String role) {
        long id = fixture.insertUser(email, "필터");
        fixture.grantGlobal(id, "customer");
        if (!"customer".equals(role)) {
            fixture.grantGlobal(id, role);
        }
        return id;
    }

    private static ShopUser principal(long id, String email) {
        return new ShopUser(id, email, null, true);
    }
}
