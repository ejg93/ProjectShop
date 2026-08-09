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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

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

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    long userId;

    /**
     * 실패 카운터를 비운다.
     *
     * <p><b>`@Transactional` 롤백이 Redis 를 안 되돌린다.</b> 이 클래스는 일부러 로그인을
     * 여러 번 실패시키는 테스트가 많고, 전부 같은 (계정, IP) 조합이라
     * 안 지우면 앞 테스트가 쌓아 둔 실패로 뒤 테스트가 차단된 채 시작한다.
     */
    @BeforeEach
    void clearLoginAttempts() {
        java.util.Set<String> keys = redis.keys("login:fail:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

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
        @DisplayName("세션에 앉는 principal 에서 비밀번호 해시가 지워진다")
        void erasesPasswordHashFromPrincipal() throws Exception {
            HttpSession session = logIn(PASSWORD).andReturn().getRequest().getSession(false);

            // 세션에서 직접 꺼낸다. SecurityContextHolder 는 요청이 끝나면 비워져서
            // "세션에 무엇이 남았나" 를 못 본다 — 그게 이 테스트가 묻는 것이다.
            SecurityContext context = (SecurityContext) session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            ShopUser principal = (ShopUser) context.getAuthentication().getPrincipal();

            assertThat(principal.getPassword())
                    .as("세션이 사는 내내 비밀번호 해시가 메모리에 남으면 안 된다")
                    .isNull();
        }

        @Test
        @DisplayName("다음 요청에도 인증이 남는다")
        void authenticationSurvivesToTheNextRequest() throws Exception {
            HttpSession session = logIn(PASSWORD).andReturn().getRequest().getSession(false);

            assertThat(session).isNotNull();

            // 잠긴 경로가 지나가면 세션에 인증이 실제로 저장된 것이다.
            //
            // 실제로 있는 경로를 쓴다. 없는 경로의 404 로 확인하면 그 자리에 API 가 생기는 순간
            // 뜻이 바뀐다 — 청크 10-2 가 `/api/orders` 를 만들면서 404 가 405 로 변해 이 테스트가 깨졌다.
            //
            // 401 이 아니라는 것이 곧 "인증이 남았다" 다. 그 뒤의 권한 판정은 다른 축이라
            // 200 을 기대하면 이 계정에 역할을 주는 준비가 붙고, 그건 이 테스트가 볼 것이 아니다.
            mvc.perform(get("/api/me").session((MockHttpSession) session))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("세션에 인증이 저장됐으면 인증 필터를 지나간다")
                            .isNotEqualTo(401));
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

    /**
     * 응답에서 <b>실패 원인을 드러내는 부분</b>만 뽑는다.
     *
     * <p>본문을 통째로 비교하던 것을 바꿨다. 오류 본문에 {@code trace_id} 가 들어가면서
     * <b>같은 실패도 요청마다 본문이 달라진다</b> — 통째 비교는 언제나 실패하는 단언이 된다.
     *
     * <p>여기서 봐야 하는 것은 "두 실패를 구분할 수 있는가" 다.
     * {@code type} 과 {@code detail} 이 같으면 클라이언트가 둘을 가를 방법이 없다.
     */
    private String identityOf(ResultActions actions) throws Exception {
        String body = bodyOf(actions);
        return jsonValue(body, "type") + "|" + jsonValue(body, "detail");
    }

    private String jsonValue(String body, String field) {
        return com.jayway.jsonpath.JsonPath.read(body, "$." + field);
    }

    @Nested
    @DisplayName("다섯 번 틀리면")
    class Blocked {

        @Test
        @DisplayName("맞는 비밀번호로도 안 들어간다")
        void rejectsEvenTheRightPassword() throws Exception {
            failFiveTimes();

            // 차단이 비밀번호 대조보다 앞에 있어야 무차별 대입이 실제로 막힌다.
            // ResultActions 에 AssertJ 의 .as() 를 붙이면 컴파일이 깨진다(`stack.md`).
            logIn(PASSWORD).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("차단됐다는 것을 문구로 흘리지 않는다")
        void looksLikeAnOrdinaryFailure() throws Exception {
            failFiveTimes();

            assertThat(identityOf(logIn(PASSWORD)))
                    .as("잠겼다고 알려 주면 그 계정이 존재한다는 뜻이 된다(`D14`)")
                    .isEqualTo(identityOf(logIn("nobody@test.local", PASSWORD)));
        }

        private void failFiveTimes() throws Exception {
            for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
                logIn("wrong-but-long-enough");
            }
        }
    }

    @Nested
    @DisplayName("로그인이 안 되면")
    class Failure {

        @Test
        @DisplayName("틀린 비밀번호와 없는 계정이 같은 문구를 받는다")
        void tellsNothingApart() throws Exception {
            assertThat(identityOf(logIn("wrong-but-long-enough")))
                    .as("문구가 갈리면 가입 여부를 물어보는 도구가 된다")
                    .isEqualTo(identityOf(logIn("nobody@test.local", PASSWORD)));
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

            assertThat(identityOf(logIn(PASSWORD)))
                    .isEqualTo(identityOf(logIn("wrong-but-long-enough")));
        }

        /**
         * 응답에서 <b>실패 원인을 드러내는 부분</b>만 뽑는다.
         *
         * <p>본문을 통째로 비교하던 것을 바꿨다. 오류 본문에 {@code trace_id} 가 들어가면서
         * <b>같은 실패도 요청마다 본문이 달라진다</b> — 통째 비교는 언제나 실패하는 단언이 된다.
         *
         * <p>여기서 봐야 하는 것은 "두 실패를 구분할 수 있는가" 다.
         * {@code type} 과 {@code detail} 이 같으면 클라이언트가 둘을 가를 방법이 없다.
         */
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
