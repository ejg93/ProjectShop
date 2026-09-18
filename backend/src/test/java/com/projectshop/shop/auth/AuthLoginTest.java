package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.charset.StandardCharsets;
import java.util.Base64;


import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import jakarta.servlet.http.Cookie;
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
class AuthLoginTest extends PostgresTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    /**
     * <b>여기서는 Spring Session 의 기본 이름이다</b>(`Q52`). `application.yml` 은 `SHOPSESSION` 으로
     * 정해 뒀는데, Boot 이 그 값을 넘겨주는 자리가 <b>내장 서버가 있을 때만 걸린다</b> —
     * 이 층은 MockMvc 라 서버가 없어서 기본 이름이 그대로 나온다.
     *
     * <b>실물 이름과 보호 속성은 HTTP 층이 잰다</b>({@code SessionStoreTest}). 거기가 서버를 띄운다.
     */
    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    FindByIndexNameSessionRepository<? extends Session> sessions;

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
            Session session = sessionOf(logIn(PASSWORD));

            // 저장소에서 직접 꺼낸다. SecurityContextHolder 는 요청이 끝나면 비워져서
            // "세션에 무엇이 남았나" 를 못 본다 — 그게 이 테스트가 묻는 것이다.
            SecurityContext context = session.getAttribute(
                    HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            ShopUser principal = (ShopUser) context.getAuthentication().getPrincipal();

            assertThat(principal.getPassword())
                    .as("세션이 사는 내내 비밀번호 해시가 메모리에 남으면 안 된다")
                    .isNull();
        }

        @Test
        @DisplayName("다음 요청에도 인증이 남는다")
        void authenticationSurvivesToTheNextRequest() throws Exception {
            String sessionId = sessionIdOf(logIn(PASSWORD));

            assertThat(sessionId).isNotNull();

            // 잠긴 경로가 지나가면 세션에 인증이 실제로 저장된 것이다.
            //
            // 실제로 있는 경로를 쓴다. 없는 경로의 404 로 확인하면 그 자리에 API 가 생기는 순간
            // 뜻이 바뀐다 — 청크 10-2 가 `/api/orders` 를 만들면서 404 가 405 로 변해 이 테스트가 깨졌다.
            //
            // 401 이 아니라는 것이 곧 "인증이 남았다" 다. 그 뒤의 권한 판정은 다른 축이라
            // 200 을 기대하면 이 계정에 역할을 주는 준비가 붙고, 그건 이 테스트가 볼 것이 아니다.
            mvc.perform(get("/api/me").cookie(sessionCookie(sessionId)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("세션에 인증이 저장됐으면 인증 필터를 지나간다")
                            .isNotEqualTo(401));
        }

        @Test
        @DisplayName("세션 ID 를 갈아 끼운다 — 세션 고정 공격을 막는다")
        void changesSessionId() throws Exception {
            // 먼저 익명 세션을 하나 만들어 그 ID 를 손에 쥔다. 공격자가 심어 두는 것이 이것이다.
            String plantedId = sessionIdOf(mvc.perform(get("/api/health")));
            assertThat(plantedId).isNotNull();

            String after = sessionIdOf(
                    mvc.perform(loginRequest(PASSWORD).cookie(sessionCookie(plantedId))));

            assertThat(after)
                    .as("심어 둔 ID 가 그대로면 공격자가 그 ID 로 인증된 세션을 얻는다")
                    .isNotNull()
                    .isNotEqualTo(plantedId);
        }

        @Test
        @DisplayName("세션이 레지스트리에 등록된다 — 탈퇴가 이걸 보고 끊는다")
        void registersTheSession() throws Exception {
            logIn(PASSWORD);

            // **색인으로 찾는다**(`Q52`). 열쇠는 principal 이름, 즉 이메일이다 —
            // 등록된 사람을 전부 받아 훑는 물음은 Redis 판 명부에 없다.
            assertThat(sessions.findByPrincipalName("login@test.local"))
                    .as("색인이 비면 5g 의 세션 만료가 대상 세션을 못 찾는다")
                    .isNotEmpty();
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
            assertThat(sessionIdOf(logIn("wrong-but-long-enough")))
                    .as("실패한 로그인이 세션을 만들면 빈 세션이 Redis 에 쌓인다")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("로그아웃")
    class Logout {

        @Test
        @DisplayName("세션을 버려서 다음 요청이 다시 막힌다")
        void dropsTheSession() throws Exception {
            String sessionId = sessionIdOf(logIn(PASSWORD));

            mvc.perform(post("/api/auth/logout").cookie(sessionCookie(sessionId)).with(csrf()))
                    .andExpect(status().isNoContent());

            mvc.perform(get("/api/orders").cookie(sessionCookie(sessionId)))
                    .andExpect(status().isUnauthorized());
        }
    }


    /**
     * 동시접속이 1개로 제한되나(`Q63`, `D14` 의 「규제」 등급).
     *
     * <p><b>부품이 아무것도 안 막던 자리다.</b> 탈퇴가 세션 저장소를 직접 지우게 되면서(`Q52`)
     * 「만료 표시를 남기고 필터가 읽는다」 경로가 사라졌고, 명부·필터·전략 셋이 쓰는 곳 없이 남았다.
     * {@code maximumSessions} 는 안 켜져 있어서 <b>동시 로그인이 무제한</b>이었다.
     *
     * <p>여기서 재는 것은 <b>설정이 켜졌나</b>가 아니라 <b>실제로 끊기나</b>다 — 켜 두고 안 걸리는 것이
     * 이 청크가 없애려는 바로 그 상태다.
     */
    @Nested
    @DisplayName("동시접속 제한은")
    class ConcurrentSessions {

        @Test
        @DisplayName("둘째 로그인이 첫째 세션을 끊는다")
        void expiresTheOlderSession() throws Exception {
            String first = sessionIdOf(logIn(PASSWORD).andExpect(status().isOk()));

            logIn(PASSWORD).andExpect(status().isOk());

            // **401 인 것이 곧 「끊겼다」다.** 인가까지 가면 403 이 나오므로(이 계정엔 역할이 없다)
            // 401 과 403 을 가르는 것이 여기서 재는 것이다 — 위 `changesSessionId` 와 반대 방향이다.
            mvc.perform(get("/api/me").cookie(sessionCookie(first)))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * <b>본문까지 잰다</b>({@code Q98}). {@code Q84} 전에는 {@code ConcurrentSessionFilter} 의
         * 콜백이 {@code setStatus} 로 끝내서 <b>{@code trace_id} 도 {@code type} 도 없었고</b>,
         * 그 고침을 재는 자리가 없어서 <b>되돌려도 초록이었다</b>.
         *
         * <p>슬러그가 {@code session-superseded} 인 것이 중요하다 — 받는 쪽이
         * <b>다시 로그인하면 되는 상황</b>과 죽은 계정을 갈라 대응한다.
         */
        @Test
        @DisplayName("끊긴 세션의 401 본문이 Problem Details 다")
        void supersededSessionHasProblemBody() throws Exception {
            String first = sessionIdOf(logIn(PASSWORD).andExpect(status().isOk()));

            logIn(PASSWORD).andExpect(status().isOk());

            mvc.perform(get("/api/me").cookie(sessionCookie(first)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(
                            MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.type")
                            .value("tag:projectshop.example,2026:error:session-superseded"))
                    .andExpect(jsonPath("$.trace_id").isNotEmpty());
        }

        /**
         * <b>새 로그인은 막지 않는다</b>({@code maxSessionsPreventsLogin(false)}).
         *
         * <p>반대로 두면 브라우저를 그냥 닫아 세션이 매달린 사람이 타임아웃까지 자기 계정에
         * 못 들어온다 — <b>막는 것이 남이 아니라 본인</b>이 된다.
         */
        @Test
        @DisplayName("둘째 로그인 자체는 지나간다")
        void letsTheNewerLoginThrough() throws Exception {
            logIn(PASSWORD).andExpect(status().isOk());

            String second = sessionIdOf(logIn(PASSWORD).andExpect(status().isOk()));

            // 401 이 아니면 인증이 남은 것이다. 그 뒤 권한 판정은 다른 축이라 여기서 안 본다.
            mvc.perform(get("/api/me").cookie(sessionCookie(second)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("새 세션은 살아 있어야 한다")
                            .isNotEqualTo(401));
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

    /**
     * 응답이 내려준 세션 쿠키의 ID. 세션을 안 만들었으면 null 이다.
     *
     * <p><b>서블릿 세션 객체를 안 읽는다</b>(`Q52`). 세션이 Redis 로 가면서 요청 객체에
     * 세션이 안 붙는다 — {@code SessionRepositoryFilter} 가 감싼 쪽이 들고 있어서,
     * {@code getRequest().getSession(false)} 가 <b>로그인에 성공해도 null 을 준다.</b>
     * 밖에서 보이는 사실은 쿠키 하나뿐이고 그것이 이 층이 볼 것이다.
     */
    private String sessionIdOf(ResultActions actions) throws Exception {
        Cookie cookie = actions.andReturn().getResponse().getCookie(SESSION_COOKIE);
        return cookie == null ? null : cookie.getValue();
    }

    /**
     * 그 응답이 만든 세션을 저장소에서 꺼낸다.
     *
     * <p><b>쿠키 값이 저장소의 열쇠가 아니다.</b> Spring Session 이 세션 ID 를 Base64 로 싸서 내린다 —
     * 쿠키 값을 그대로 {@code findById} 에 넣으면 <b>없는 것으로 나온다</b>(`Q52` 실측).
     */
    private Session sessionOf(ResultActions actions) throws Exception {
        String cookieValue = sessionIdOf(actions);
        assertThat(cookieValue).as("로그인이 세션 쿠키를 안 내렸다").isNotNull();
        String id = new String(Base64.getDecoder().decode(cookieValue), StandardCharsets.UTF_8);
        Session session = sessions.findById(id);
        assertThat(session).as("쿠키가 가리키는 세션이 저장소에 없다").isNotNull();
        return session;
    }

    private static Cookie sessionCookie(String id) {
        return new Cookie(SESSION_COOKIE, id);
    }

    private String bodyOf(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
