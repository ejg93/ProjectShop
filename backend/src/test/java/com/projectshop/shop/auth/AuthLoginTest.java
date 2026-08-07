package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;

/**
 * 로그인이 <b>세션에 남는지</b>와 <b>실패가 정보를 안 흘리는지</b>를 본다.
 *
 * <p>{@code formLogin} 을 껐기 때문에 인증·세션 처리·컨텍스트 저장을 컨트롤러가 직접 부른다.
 * 하나를 빠뜨리면 로그인 응답은 200 인데 다음 요청에서 인증이 사라진다 —
 * 로그인만 눌러 보면 정상으로 보인다.
 */
@AutoConfigureMockMvc
class AuthLoginTest extends PostgresTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SessionRegistry sessionRegistry;

    long userId;

    @BeforeEach
    void setUp() {
        userId = jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values ('login@test.local', :hash, '로그인')
                        returning user_id
                        """)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .query(Long.class)
                .single();
    }

    @Nested
    @DisplayName("로그인이 되면")
    class Success {

        @Test
        @DisplayName("누구인지 내려준다")
        void respondsWithWhoYouAre() throws Exception {
            logIn(PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user_id").value(userId))
                    .andExpect(jsonPath("$.email").value("login@test.local"));
        }

        @Test
        @DisplayName("다음 요청에도 인증이 남는다")
        void authenticationSurvivesToTheNextRequest() throws Exception {
            HttpSession session = logIn(PASSWORD).andReturn().getRequest().getSession(false);

            assertThat(session).isNotNull();

            // 잠긴 경로가 지나가면 세션에 인증이 실제로 저장된 것이다.
            mvc.perform(get("/api/orders").session((MockHttpSession) session))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("세션 ID 를 갈아 끼운다 — 세션 고정 공격을 막는다")
        void changesSessionId() throws Exception {
            MockHttpSession planted = new MockHttpSession();
            String plantedId = planted.getId();

            MvcResult result = mvc.perform(loginRequest(PASSWORD).session(planted)).andReturn();
            HttpSession after = result.getRequest().getSession(false);

            assertThat(after).isNotNull();
            assertThat(after.getId())
                    .as("심어 둔 ID 가 그대로면 공격자가 그 ID 로 인증된 세션을 얻는다")
                    .isNotEqualTo(plantedId);
        }

        @Test
        @DisplayName("세션이 레지스트리에 등록된다 — 탈퇴가 이걸 보고 끊는다")
        void registersTheSession() throws Exception {
            logIn(PASSWORD);

            boolean registered = sessionRegistry.getAllPrincipals().stream()
                    .filter(ShopUserDetailsService.ShopUser.class::isInstance)
                    .map(ShopUserDetailsService.ShopUser.class::cast)
                    .anyMatch(user -> user.id() == userId);

            assertThat(registered)
                    .as("등록이 빠지면 5g 의 세션 만료가 대상 세션을 못 찾는다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("로그인이 안 되면")
    class Failure {

        @Test
        @DisplayName("틀린 비밀번호와 없는 계정이 같은 문구를 받는다")
        void tellsNothingApart() throws Exception {
            String wrongPassword = bodyOf(logIn("wrong-but-long-enough"));
            String noSuchAccount = bodyOf(logIn("nobody@test.local", PASSWORD));

            assertThat(wrongPassword)
                    .as("문구가 갈리면 가입 여부를 물어보는 도구가 된다")
                    .isEqualTo(noSuchAccount);
        }

        @Test
        @DisplayName("401 로 떨어진다")
        void respondsUnauthorized() throws Exception {
            logIn("wrong-but-long-enough").andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("정지된 계정도 같은 문구를 받는다")
        void suspendedLooksTheSame() throws Exception {
            jdbc.sql("update app_user set status = 'suspended' where user_id = :id")
                    .param("id", userId)
                    .update();

            assertThat(bodyOf(logIn(PASSWORD)))
                    .isEqualTo(bodyOf(logIn("wrong-but-long-enough")));
        }

        @Test
        @DisplayName("탈퇴한 계정은 로그인되지 않는다")
        void deletedAccountCannotLogIn() throws Exception {
            jdbc.sql("update app_user set deleted_at = now() where user_id = :id")
                    .param("id", userId)
                    .update();

            logIn(PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("실패는 세션을 안 만든다")
        void failureLeavesNoSession() throws Exception {
            assertThat(logIn("wrong-but-long-enough").andReturn().getRequest().getSession(false))
                    .isNull();
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("세션을 버려서 다음 요청이 다시 막힌다")
        void dropsTheSession() throws Exception {
            MockHttpSession session =
                    (MockHttpSession) logIn(PASSWORD).andReturn().getRequest().getSession(false);

            mvc.perform(post("/api/auth/logout").session(session).with(csrf()))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/orders").session(session))
                    .andExpect(status().isUnauthorized());
        }
    }

    private ResultActions logIn(String password) throws Exception {
        return logIn("login@test.local", password);
    }

    private ResultActions logIn(String email, String password) throws Exception {
        return mvc.perform(loginRequest(email, password));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            loginRequest(String password) {
        return loginRequest("login@test.local", password);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            loginRequest(String email, String password) {

        return post("/api/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email": "%s", "password": "%s"}
                        """.formatted(email, password));
    }

    private String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
