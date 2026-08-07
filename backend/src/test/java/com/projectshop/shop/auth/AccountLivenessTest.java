package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 계정이 죽은 뒤에도 살아 있는 세션이 통하는지를 본다(`ADR 0010` 안쪽 겹).
 *
 * <p>로그인할 때만 계정 상태를 보면 <b>이미 로그인한 다른 기기가 안 막힌다.</b>
 * 그 상태는 화면에 아무 증상이 없다 — 탈퇴한 사람이 계속 쓰고 있어도 아무도 모른다.
 */
@AutoConfigureMockMvc
class AccountLivenessTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PermissionRuleLoader loader;

    AuthFixture fixture;
    long userId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("alive@test.local", "살아있음");
        fixture.grantGlobal(userId, "customer");
    }

    @Nested
    @DisplayName("살아 있는 계정")
    class Alive {

        @Test
        @DisplayName("요청이 그대로 지나간다")
        void passesThrough() throws Exception {
            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("인증이 없는 요청은 이 필터가 안 건드린다")
        void ignoresAnonymous() throws Exception {
            // 익명 요청이 여기서 막히면 원인이 인가가 아니라 생존 확인으로 보인다.
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("죽은 계정")
    class Deleted {

        @Test
        @DisplayName("세션이 살아 있어도 막힌다")
        void blockedEvenWithSession() throws Exception {
            withdraw();

            // 로그인 시점 검사만으로는 이미 열린 세션을 못 막는다. 이 필터가 그 자리다.
            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("업무 상태가 아니라 수명을 본다")
        void suspensionIsNotWithdrawal() throws Exception {
            // 정지는 풀리고 탈퇴는 안 풀린다. 축이 달라서 여기서 안 섞는다.
            jdbc.sql("update app_user set status = 'suspended' where user_id = :id")
                    .param("id", userId)
                    .update();
            loader.evict(userId);

            // 정지 계정을 막는 것은 로그인이 할 일이다. 여기서 섞으면 복구가 어려워진다.
            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("캐시")
    class Caching {

        @Test
        @DisplayName("무효화를 빼먹으면 죽은 계정이 그대로 통한다")
        void staleWithoutEvict() throws Exception {
            // 캐시를 채운다.
            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isOk());

            jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                    .param("id", userId)
                    .update();

            // 이 구멍을 알고 남긴다. TTL 이 지나거나 탈퇴(5g)가 evict 를 부르면 닫힌다.
            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("무효화하면 그 자리에서 막힌다")
        void evictClosesItImmediately() throws Exception {
            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isOk());

            withdraw();

            mvc.perform(get("/api/me/permissions").with(user(principal())))
                    .andExpect(status().isUnauthorized());
        }
    }

    /** 탈퇴가 할 일. 수명을 끊고 캐시를 지운다 — 청크 5g 가 이 순서를 그대로 쓴다. */
    private void withdraw() {
        jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                .param("id", userId)
                .update();
        loader.evict(userId);
    }

    private ShopUser principal() {
        return new ShopUser(userId, "alive@test.local", "{noop}x", true);
    }
}
