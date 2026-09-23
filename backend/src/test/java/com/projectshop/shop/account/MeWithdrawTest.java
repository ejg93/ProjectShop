package com.projectshop.shop.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
                        returning user_id
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

            assertThat(jdbc.sql("select deleted_at is not null from app_user where user_id = :id")
                    .param("id", userId).query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("select count(*) from app_user where user_id = :id")
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
            withdraw("wrong-but-long-enough").andExpect(status().isUnprocessableContent());

            assertThat(jdbc.sql("select deleted_at is null from app_user where user_id = :id")
                    .param("id", userId).query(Boolean.class).single())
                    .as("되돌릴 수 없는 조작이라 세션만으로는 부족하다")
                    .isTrue();
        }

        @Test
        @DisplayName("실패하면 동의도 그대로다")
        void failureRollsBackConsents() throws Exception {
            withdraw("wrong-but-long-enough").andExpect(status().isUnprocessableContent());

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

        /**
         * 탈퇴는 역할 행을 안 지워서 멤버 관리 쪽 검사로는 안 걸린다. 풀어 두면 그 셀러는
         * 멤버를 부를 수도 뺄 수도 없이 잠긴다(`Q169`). 대표를 넘긴 뒤에 나간다(사용자 선택).
         */
        @Test
        @DisplayName("살아 있는 셀러의 마지막 대표는 탈퇴 못 한다")
        void lastOwnerCannotWithdraw() throws Exception {
            long seller = fixture.insertSeller("bye-shop", "떠나는가게");
            fixture.joinSeller(seller, userId);
            fixture.grantOrg(userId, "seller_owner", seller);

            withdraw(PASSWORD)
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.type").value(
                            "tag:projectshop.example,2026:error:withdrawal-last-owner"));

            assertThat(jdbc.sql("select deleted_at is null from app_user where user_id = :id")
                    .param("id", userId).query(Boolean.class).single()).isTrue();
        }

        @Test
        @DisplayName("대표가 하나 더 있으면 탈퇴한다")
        void ownerWithCoOwnerCanWithdraw() throws Exception {
            long seller = fixture.insertSeller("bye-shop2", "둘이가게");
            fixture.joinSeller(seller, userId);
            fixture.grantOrg(userId, "seller_owner", seller);
            long other = fixture.insertUser("stay@test.local", "남는대표");
            fixture.joinSeller(seller, other);
            fixture.grantOrg(other, "seller_owner", seller);

            withdraw(PASSWORD).andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("폐업한 셀러의 마지막 대표는 탈퇴한다")
        void ownerOfClosedSellerCanWithdraw() throws Exception {
            long seller = fixture.insertSeller("bye-shop3", "닫은가게");
            fixture.joinSeller(seller, userId);
            fixture.grantOrg(userId, "seller_owner", seller);
            jdbc.sql("update seller set deleted_at = now() where seller_id = :id")
                    .param("id", seller).update();

            withdraw(PASSWORD).andExpect(status().isNoContent());
        }

        /**
         * <b>탈퇴한 대표는 「남은 대표」가 아니다</b>(`Q169`). 탈퇴가 역할 행을 남기므로 역할만 세면
         * 없는 사람이 대표로 남아 있는 것처럼 보이고, 산 대표가 나갈 수 있게 된다.
         */
        @Test
        @DisplayName("이미 탈퇴한 대표는 남은 대표로 안 센다")
        void withdrawnCoOwnerDoesNotCount() throws Exception {
            long seller = fixture.insertSeller("bye-shop4", "빈자리가게");
            fixture.joinSeller(seller, userId);
            fixture.grantOrg(userId, "seller_owner", seller);
            long gone = fixture.insertUser("gone@test.local", "먼저간대표");
            fixture.joinSeller(seller, gone);
            fixture.grantOrg(gone, "seller_owner", seller);
            jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                    .param("id", gone).update();

            withdraw(PASSWORD).andExpect(status().isUnprocessableContent());
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
                        insert into user_consent (user_id, consent_item_id, granted, source)
                        select :id, consent_item_id, true, 'signup' from consent_item
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
