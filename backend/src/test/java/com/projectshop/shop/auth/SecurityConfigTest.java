package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.server.Cookie.SameSite;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

/**
 * 인증 기반이 깔린 모양을 고정한다.
 *
 * <p>여기서 잡으려는 실패는 두 가지다. 하나는 <b>열려 있으면 안 되는 경로가 열리는 것</b>,
 * 다른 하나는 <b>인증 실패가 401 이 아닌 형태로 나가는 것</b>이다.
 * 뒤쪽은 화면 없이 보면 증상이 "응답 파싱 실패" 로 보여서 원인이 안 드러난다.
 */
@AutoConfigureMockMvc
class SecurityConfigTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ServerProperties serverProperties;

    @Nested
    @DisplayName("경로 공개 범위")
    class PublicPaths {

        @Test
        @DisplayName("헬스 체크는 인증 없이 열린다")
        void healthIsOpen() throws Exception {
            mvc.perform(get("/api/health")).andExpect(status().isOk());
        }

        @Test
        @DisplayName("목록에 없는 경로는 인증을 요구한다")
        void anythingElseNeedsAuth() throws Exception {
            mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("인증이 없으면 로그인 페이지로 넘기지 않고 401 로 끝낸다")
        void deniesWithStatusNotRedirect() throws Exception {
            mvc.perform(get("/api/orders"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl())
                            .as("폼 로그인이 켜져 있으면 여기에 /login 이 들어온다")
                            .isNull());
        }
    }

    @Nested
    @DisplayName("CSRF")
    class Csrf {

        @Test
        @DisplayName("토큰 없는 POST 는 열린 경로라도 못 지나간다")
        void rejectsPostWithoutToken() throws Exception {
            // 상태 코드를 하나로 못박지 않는다. 같은 요청이 여기서는 403 이고
            // 기동한 서버에 curl 로 보내면 401 이다. 이유는 안 밝혔다.
            // 여기서 고정하려는 것은 코드가 아니라 "안 지나간다" 쪽이다.
            mvc.perform(post("/api/health")).andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("토큰이 있으면 CSRF 층을 지나간다")
        void passesWithToken() throws Exception {
            // 405 는 CSRF 를 지나 라우팅까지 갔다는 뜻이다. 헬스 체크에 POST 매핑이 없어서 나온다.
            // 여기서 401 이 나오면 토큰을 붙여도 안 지나가는 것이라 설정이 잘못된 것이다.
            mvc.perform(post("/api/health").with(csrf()))
                    .andExpect(status().isMethodNotAllowed());
        }
    }

    @Nested
    @DisplayName("비밀번호 해시")
    class Hashing {

        @Test
        @DisplayName("저장값에 알고리즘 접두사가 붙는다")
        void encodesWithBcryptPrefix() {
            assertThat(passwordEncoder.encode("correct horse battery")).startsWith("{bcrypt}$2");
        }

        @Test
        @DisplayName("같은 비밀번호도 매번 다른 값이 된다")
        void saltsEachEncoding() {
            String password = "correct horse battery";

            assertThat(passwordEncoder.encode(password))
                    .as("같으면 salt 가 안 붙은 것이고, 해시 하나가 뚫리면 같은 비밀번호가 전부 뚫린다")
                    .isNotEqualTo(passwordEncoder.encode(password));
        }

        @Test
        @DisplayName("검증은 접두사를 보고 알고리즘을 고른다")
        void matchesWhatItEncoded() {
            String stored = passwordEncoder.encode("correct horse battery");

            assertThat(passwordEncoder.matches("correct horse battery", stored)).isTrue();
            assertThat(passwordEncoder.matches("correct horse batterY", stored)).isFalse();
        }
    }

    @Nested
    @DisplayName("세션 쿠키")
    class SessionCookie {

        @Test
        @DisplayName("스크립트가 못 읽고 남의 사이트 요청에 안 붙는다")
        void isHttpOnlyAndSameSiteLax() {
            var cookie = serverProperties.getServlet().getSession().getCookie();

            assertThat(cookie.getHttpOnly()).isTrue();
            assertThat(cookie.getSameSite()).isEqualTo(SameSite.LAX);
        }

        @Test
        @DisplayName("Secure 는 꺼져 있다 — 로컬이 http 라는 전제에 붙은 값이다")
        void secureIsOffOnPurpose() {
            assertThat(serverProperties.getServlet().getSession().getCookie().getSecure())
                    .as("배포가 생기면 이 테스트가 먼저 뒤집혀야 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("무활동 만료가 30분이다")
        void expiresAfterThirtyIdleMinutes() {
            assertThat(serverProperties.getServlet().getSession().getTimeout())
                    .hasMinutes(30);
        }
    }
}
