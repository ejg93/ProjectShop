package com.projectshop.shop.me;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 탈퇴. 계정 관리 축의 마지막이고 `ADR 0010` 의 두 겹이 여기서 다 쓰인다.
 *
 * <p>탈퇴가 곧 삭제가 아니다(`D13`). 여기서 보는 것은 <b>수명이 끊겼다는 사실이 즉시 먹는가</b>다.
 */
@AutoConfigureMockMvc
class MeWithdrawTest extends PostgresTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    AuthFixture fixture;
    long userId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values ('bye@test.local', :hash, '떠남')
                        returning id
                        """)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .query(Long.class)
                .single();
        fixture.grantGlobal(userId, "customer");

        consent("terms_of_service");
        consent("marketing_email");
    }

    @Nested
    @DisplayName("탈퇴하면")
    class Withdrawing {

        @Test
        @DisplayName("수명이 끊긴다 — 행은 남는다")
        void marksLifetimeNotDeletion() throws Exception {
            withdraw(PASSWORD).andExpect(status().isNoContent());

            assertThat(jdbc.sql("select deleted_at is not null from app_user where id = :id")
                    .param("id", userId).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("select count(*) from app_user where id = :id")
                    .param("id", userId).query(Long.class).single())
                    .as("주문 기록이 5년 남아야 해서 행을 지우지 않는다(D13)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("남아 있던 동의가 전부 거둬진다 — 필수도 함께")
        void revokesEveryConsent() throws Exception {
            withdraw(PASSWORD).andExpect(status().isNoContent());

            List<String> stillGranted = jdbc.sql("""
                            select item_code from current_consent
                             where user_id = :id and granted
                            """)
                    .param("id", userId)
                    .query(String.class)
                    .list();

            assertThat(stillGranted)
                    .as("계약이 끝났는데 동의가 유효한 채로 남으면 안 된다")
                    .isEmpty();
        }

        @Test
        @DisplayName("철회가 탈퇴로 일어난 것임을 남긴다")
        void marksTheSourceAsWithdraw() throws Exception {
            withdraw(PASSWORD).andExpect(status().isNoContent());

            List<String> sources = jdbc.sql("""
                            select distinct uc.source from user_consent uc
                             where uc.user_id = :id and not uc.granted
                            """)
                    .param("id", userId)
                    .query(String.class)
                    .list();

            assertThat(sources)
                    .as("마이페이지에서 거둔 것과 탈퇴로 거둬진 것이 갈려야 한다")
                    .containsExactly("withdraw");
        }

        @Test
        @DisplayName("그 자리에서 막힌다 — 캐시를 기다리지 않는다")
        void blocksImmediately() throws Exception {
            // 캐시를 먼저 채운다. evict 를 빠뜨리면 여기서 200 이 나온다.
            mvc.perform(get("/api/me").with(user(principal()))).andExpect(status().isOk());

            withdraw(PASSWORD).andExpect(status().isNoContent());

            mvc.perform(get("/api/me").with(user(principal())))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("감사에 남는다")
        void recordsAudit() throws Exception {
            withdraw(PASSWORD).andExpect(status().isNoContent());

            assertThat(jdbc.sql("""
                            select count(*) from audit_log
                             where actor_user_id = :id and event_type = 'user.withdrawn'
                            """).param("id", userId).query(Long.class).single())
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("막는 것")
    class Guards {

        @Test
        @DisplayName("비밀번호가 틀리면 탈퇴가 안 된다")
        void wrongPasswordKeepsAccount() throws Exception {
            withdraw("wrong-but-long-enough").andExpect(status().isUnprocessableEntity());

            assertThat(jdbc.sql("select deleted_at is null from app_user where id = :id")
                    .param("id", userId).query(Boolean.class).single())
                    .as("되돌릴 수 없는 조작이라 세션만으로는 부족하다")
                    .isTrue();
        }

        @Test
        @DisplayName("실패하면 동의도 그대로다")
        void failureRollsBackConsents() throws Exception {
            withdraw("wrong-but-long-enough").andExpect(status().isUnprocessableEntity());

            assertThat(jdbc.sql("""
                            select count(*) from current_consent
                             where user_id = :id and granted
                            """).param("id", userId).query(Long.class).single())
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("이미 탈퇴한 계정은 또 못 한다")
        void cannotWithdrawTwice() throws Exception {
            withdraw(PASSWORD).andExpect(status().isNoContent());

            withdraw(PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("로그인 없이는 못 부른다")
        void requiresLogin() throws Exception {
            mvc.perform(post("/api/me/withdraw").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\": \"%s\"}".formatted(PASSWORD)))
                    .andExpect(status().isUnauthorized());
        }
    }

    private ResultActions withdraw(String password) throws Exception {
        return mvc.perform(post("/api/me/withdraw")
                .with(user(principal()))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\": \"%s\"}".formatted(password)));
    }

    private void consent(String code) {
        jdbc.sql("""
                        insert into user_consent (user_id, item_id, granted, source)
                        select :id, id, true, 'signup' from consent_item
                         where code = :code order by version desc limit 1
                        """)
                .param("id", userId)
                .param("code", code)
                .update();
    }

    private ShopUser principal() {
        return new ShopUser(userId, "bye@test.local", "{noop}x", true);
    }
}
