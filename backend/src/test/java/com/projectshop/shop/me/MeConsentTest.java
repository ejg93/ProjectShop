package com.projectshop.shop.me;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 동의를 거두고 다시 켜는 경로.
 *
 * <p>`5-0` 이 동의를 append-only 로 만든 이유가 철회인데 부를 경로가 없었다.
 * <b>스키마가 표현할 수 있는 것을 앱이 못 하면 그 설계는 쓰인 적이 없는 것이다</b>(`D2` R7).
 */
@AutoConfigureMockMvc
class MeConsentTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    AuthFixture fixture;
    long userId;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);
        userId = fixture.insertUser("consent-me@test.local", "동의자");
        fixture.grantGlobal(userId, "customer");

        grantAtSignup("terms_of_service");
        grantAtSignup("privacy_collect");
    }

    @Nested
    @DisplayName("목록")
    class Listing {

        @Test
        @DisplayName("건드린 적 없는 항목도 나온다")
        void includesUntouchedItems() throws Exception {
            // 동의한 것만 보여주면 무엇을 더 켤 수 있는지 알 방법이 없다.
            mvc.perform(get("/api/me/consents").with(user(principal())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(5))
                    .andExpect(jsonPath("$[?(@.code == 'marketing_sms')].granted").value(false))
                    // 필터는 배열을 준다. acted_at 이 null 이라는 것이 "건드린 적 없다" 는 뜻이다.
                    .andExpect(jsonPath("$[?(@.code == 'marketing_sms')].acted_at")
                            .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        }

        @Test
        @DisplayName("필수 항목이 먼저 나오고 종속이 표시된다")
        void showsRequiredFirstAndDependency() throws Exception {
            mvc.perform(get("/api/me/consents").with(user(principal())))
                    .andExpect(jsonPath("$[0].required").value(true))
                    .andExpect(jsonPath("$[?(@.code == 'marketing_night')].depends_on")
                            .value("marketing_email"));
        }
    }

    @Nested
    @DisplayName("철회")
    class Revoking {

        @Test
        @DisplayName("거둔 사실이 행으로 남는다 — 동의했던 행은 안 지운다")
        void keepsBothRows() throws Exception {
            grant("marketing_email").andExpect(status().isNoContent());
            revoke("marketing_email").andExpect(status().isNoContent());

            List<Boolean> history = jdbc.sql("""
                            select uc.granted from user_consent uc
                              join consent_item ci on ci.consent_item_id = uc.consent_item_id
                             where uc.user_id = :id and ci.code = 'marketing_email'
                             order by uc.user_consent_id
                            """)
                    .param("id", userId)
                    .query(Boolean.class)
                    .list();

            assertThat(history)
                    .as("철회가 update 였으면 동의 시점을 잃는다(R7)")
                    .containsExactly(true, false);
        }

        @Test
        @DisplayName("필수 항목은 거둘 수 없다")
        void requiredCannotBeRevoked() throws Exception {
            // 허용하면 동의 없이 살아 있는 계정이 생긴다. 그건 탈퇴지 철회가 아니다.
            revoke("terms_of_service").andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("채널을 거두면 야간 수신도 같이 거둬진다")
        void revokingChannelCascadesToNight() throws Exception {
            grant("marketing_email");
            grant("marketing_night").andExpect(status().isNoContent());

            revoke("marketing_email").andExpect(status().isNoContent());

            assertThat(granted("marketing_night"))
                    .as("채널 없는 야간 동의가 남으면 나중에 채널만 켜질 때 야간까지 열린다")
                    .isFalse();
        }

        @Test
        @DisplayName("이미 거둔 것을 또 거둬도 행이 안 늘어난다")
        void repeatedRevokeIsIdempotent() throws Exception {
            grant("marketing_sms");
            revoke("marketing_sms");
            long before = rowCount("marketing_sms");

            revoke("marketing_sms").andExpect(status().isNoContent());

            assertThat(rowCount("marketing_sms"))
                    .as("남길 것은 바뀐 순간이지 요청이 온 횟수가 아니다")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("모르는 항목은 404")
        void unknownItemIsNotFound() throws Exception {
            revoke("no-such-thing").andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("재동의")
    class Granting {

        @Test
        @DisplayName("껐던 것을 다시 켠다")
        void turnsItBackOn() throws Exception {
            grant("marketing_sms");
            revoke("marketing_sms");

            grant("marketing_sms").andExpect(status().isNoContent());

            assertThat(granted("marketing_sms")).isTrue();
        }

        @Test
        @DisplayName("채널 없이 야간만 켤 수 없다")
        void nightNeedsItsChannel() throws Exception {
            grant("marketing_night").andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("채널을 먼저 켜면 야간도 켜진다")
        void nightFollowsItsChannel() throws Exception {
            grant("marketing_email").andExpect(status().isNoContent());

            grant("marketing_night").andExpect(status().isNoContent());

            assertThat(granted("marketing_night")).isTrue();
        }
    }

    @Nested
    @DisplayName("권한")
    class Authorization {

        @Test
        @DisplayName("로그인 없이는 못 본다")
        void requiresLogin() throws Exception {
            mvc.perform(get("/api/me/consents")).andExpect(status().isUnauthorized());
        }
    }

    private ResultActions grant(String code) throws Exception {
        return mvc.perform(post("/api/me/consents/{code}/grant", code)
                .with(user(principal())).with(csrf()));
    }

    private ResultActions revoke(String code) throws Exception {
        return mvc.perform(post("/api/me/consents/{code}/revoke", code)
                .with(user(principal())).with(csrf()));
    }

    private boolean granted(String code) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        select coalesce((select granted from current_consent
                                          where user_id = :id and item_code = :code), false)
                        """)
                .param("id", userId)
                .param("code", code)
                .query(Boolean.class)
                .single());
    }

    private long rowCount(String code) {
        return jdbc.sql("""
                        select count(*) from user_consent uc
                          join consent_item ci on ci.consent_item_id = uc.consent_item_id
                         where uc.user_id = :id and ci.code = :code
                        """)
                .param("id", userId)
                .param("code", code)
                .query(Long.class)
                .single();
    }

    private void grantAtSignup(String code) {
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
        return new ShopUser(userId, "consent-me@test.local", "{noop}x", true);
    }
}
