package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.projectshop.shop.PostgresTestBase;

/**
 * 클라이언트가 CSRF 토큰을 <b>실제로 얻어서 쓸 수 있는지</b>를 본다.
 *
 * <p>다른 테스트는 {@code with(csrf())} 로 토큰을 만들어 넣는다. 그건 서버가 토큰을
 * 내려주는지를 건너뛰기 때문에, 토큰을 받을 방법이 없는 상태에서도 전부 통과한다.
 * 실제로 5-2 를 끝냈을 때 그 상태였다 — 테스트는 초록인데 curl 로는 POST 를 못 불렀다.
 *
 * <p>그래서 여기서는 토큰을 <b>응답에서 꺼내</b> 다음 요청에 실어 보낸다.
 *
 * <h2>왜 컨텍스트를 새로 띄우나</h2>
 *
 * <p>{@code AuthLoginTest} 가 먼저 돌면 여기서 토큰 쿠키가 안 온다. 인증 상태를 비워도 그대로다 —
 * 진단해 보니 요청 시점의 {@code SecurityContextHolder} 는 비어 있는데 쿠키만 안 실린다.
 * <b>원인을 공유 컨텍스트의 상태까지 좁혔고 그 이상은 못 밝혔다.</b>
 *
 * <p>기동한 서버에서는 안 나는 문제다. 실제 curl 로 토큰을 받아 가입까지 되는 것을 확인했다.
 * 그래서 프로덕션 결함이 아니라 이 층의 한계로 보고 격리했다.
 * 이 테스트가 검증하려는 것(진짜 HTTP 왕복)을 제대로 하려면 청크 {@code 35a} 가 필요하다.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = ClassMode.BEFORE_CLASS)
class CsrfTokenTest extends PostgresTestBase {

    private static final String COOKIE_NAME = "XSRF-TOKEN";
    private static final String HEADER_NAME = "X-XSRF-TOKEN";

    @Autowired
    MockMvc mvc;

    @Nested
    @DisplayName("토큰 발급")
    class Issuing {

        @Test
        @DisplayName("아무 요청에나 토큰 쿠키가 실려 온다")
        void issuesTokenOnAnyRequest() throws Exception {
            Cookie token = tokenCookie();

            assertThat(token).as("쿠키가 없으면 클라이언트는 POST 를 부를 방법이 없다").isNotNull();
            assertThat(token.getValue()).isNotBlank();
        }

        @Test
        @DisplayName("스크립트가 읽을 수 있다 — 세션 쿠키와 목적이 다르다")
        void tokenCookieIsReadableByScript() throws Exception {
            assertThat(tokenCookie().isHttpOnly())
                    .as("HttpOnly 면 스크립트가 못 읽어서 헤더에 실을 수 없다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("받은 토큰으로 POST")
    class UsingTheToken {

        @Test
        @DisplayName("쿠키에서 꺼낸 토큰을 헤더에 실으면 지나간다")
        void passesWithTokenFromCookie() throws Exception {
            Cookie token = tokenCookie();

            // 405 는 CSRF 를 지나 라우팅까지 갔다는 뜻이다. 헬스 체크에 POST 매핑이 없다.
            mvc.perform(post("/api/health")
                            .cookie(token)
                            .header(HEADER_NAME, token.getValue()))
                    .andExpect(status().isMethodNotAllowed());
        }

        @Test
        @DisplayName("실제 가입도 이 토큰으로 된다")
        void signsUpWithTokenFromCookie() throws Exception {
            Cookie token = tokenCookie();

            mvc.perform(post("/api/auth/signup")
                            .cookie(token)
                            .header(HEADER_NAME, token.getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "email": "csrf@test.local",
                                      "password": "hunter2-and-then-some",
                                      "display_name": "토큰",
                                      "consents": {"terms_of_service": true, "privacy_collect": true}
                                    }
                                    """))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("쿠키만 있고 헤더가 없으면 막힌다")
        void rejectsCookieWithoutHeader() throws Exception {
            mvc.perform(post("/api/health").cookie(tokenCookie()))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        @DisplayName("남의 값을 헤더에 넣으면 막힌다")
        void rejectsForgedHeader() throws Exception {
            mvc.perform(post("/api/health")
                            .cookie(tokenCookie())
                            .header(HEADER_NAME, "made-up-value"))
                    .andExpect(status().is4xxClientError());
        }
    }

    private Cookie tokenCookie() throws Exception {
        MvcResult result = mvc.perform(get("/api/health")).andReturn();
        return result.getResponse().getCookie(COOKIE_NAME);
    }
}
