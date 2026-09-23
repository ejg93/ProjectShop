package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

import jakarta.servlet.http.Cookie;

/**
 * 관리자의 대행 보기(`16b`).
 *
 * <p><b>세션을 실제로 잇는다.</b> 대행은 세션의 인증을 바꿔 끼우는 것이라, 요청 하나에 인증을 얹는
 * {@code with(user(...))} 로는 「다음 요청이 그 사람으로 보이나」를 못 잰다. 로그인해서 받은 세션 쿠키로
 * 다음 요청을 보낸다({@code AuthLoginTest} 와 같은 방법 — MockMvc 라 쿠키 이름은 기본값 {@code SESSION} 이다).
 */
@DisplayName("관리자 대행 보기")
class ImpersonationTest extends PostgresTestBase {

    private static final String PASSWORD = "tangerine-kite7-violet";

    private static final String SESSION_COOKIE = "SESSION";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PermissionRuleLoader ruleLoader;

    private long adminId;
    private long customerId;
    private long otherAdminId;

    @BeforeEach
    void setUp() {
        AuthFixture fixture = new AuthFixture(jdbc);
        adminId = insertUser("imp-admin@test.local");
        fixture.grantGlobal(adminId, "admin");
        customerId = insertUser("imp-customer@test.local");
        fixture.grantGlobal(customerId, "customer");
        otherAdminId = insertUser("imp-admin2@test.local");
        fixture.grantGlobal(otherAdminId, "admin");
    }

    /**
     * 대행의 한 바퀴. <b>그 사람으로 보이고, 쓰기는 막히고, 끝내면 돌아온다</b> — 셋 중 하나라도 빠지면
     * 대행이 그냥 권한 우회가 되거나(쓰기) 빠져나올 길이 없다(끝내기).
     */
    @Test
    @DisplayName("대행하면 그 사람으로 보이고 쓰기는 막히며 끝내면 관리자로 돌아온다")
    void impersonatesReadOnlyAndEnds() throws Exception {
        Cookie session = logIn("imp-admin@test.local");

        mvc.perform(startRequest(customerId).cookie(session)).andExpect(status().isNoContent());

        mvc.perform(get("/api/me/permissions").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id").value(customerId))
                .andExpect(jsonPath("$.impersonated_by").value(adminId));

        // 탈퇴가 통과하면 관리자가 그 사람 이름으로 계정을 없앤다 — 가장 나쁜 쓰기로 잰다.
        mvc.perform(post("/api/me/withdraw").cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"%s\"}".formatted(PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:impersonation-read-only"));
        assertThat(jdbc.sql("select deleted_at is null from app_user where user_id = :id")
                .param("id", customerId).query(Boolean.class).single()).isTrue();

        mvc.perform(post("/api/admin/impersonation/end").cookie(session).with(csrf()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/me/permissions").cookie(session))
                .andExpect(jsonPath("$.user_id").value(adminId))
                .andExpect(jsonPath("$.impersonated_by").doesNotExist());

        assertThat(jdbc.sql("""
                        select count(*) from audit_log
                         where actor_user_id = :admin and target_id = :customer
                           and event_type in ('user.impersonation_started', 'user.impersonation_ended')
                        """)
                .param("admin", adminId).param("customer", customerId).query(Long.class).single())
                .as("시작과 끝이 관리자 이름으로 남는다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("관리자는 대행하지 않는다")
    void cannotImpersonateAnotherAdmin() throws Exception {
        Cookie session = logIn("imp-admin@test.local");

        mvc.perform(startRequest(otherAdminId).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:impersonation-forbidden"));
    }

    @Test
    @DisplayName("관리자가 아니면 대행을 못 연다")
    void customerCannotStart() throws Exception {
        Cookie session = logIn("imp-customer@test.local");

        mvc.perform(startRequest(otherAdminId).cookie(session))
                .andExpect(status().isForbidden());
    }

    /**
     * 대행 세션은 대행자가 대행할 수 있는 동안만 산다(`Q195`). 주인이 대상 사용자라 생존 확인이 대상만 보던 동안에는
     * 권한을 거둬도 절대 만료까지 대상 화면이 열려 있었다(마무리 45차 독립 리뷰).
     */
    @Test
    @DisplayName("대행 중 관리자 역할을 거두면 다음 요청에서 대행이 끝난다")
    void endsWhenTheImpersonatorLosesTheRole() throws Exception {
        Cookie session = logIn("imp-admin@test.local");
        mvc.perform(startRequest(customerId).cookie(session)).andExpect(status().isNoContent());

        jdbc.sql("""
                        delete from user_role
                         where user_id = :id and role_id = (select role_id from role where code = 'admin')
                        """)
                .param("id", adminId)
                .update();
        ruleLoader.evict(adminId);

        mvc.perform(get("/api/me/permissions").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("tag:projectshop.example,2026:error:impersonation-revoked"));
    }

    @Test
    @DisplayName("대행 중 관리자 계정이 죽으면 다음 요청에서 대행이 끝난다")
    void endsWhenTheImpersonatorIsGone() throws Exception {
        Cookie session = logIn("imp-admin@test.local");
        mvc.perform(startRequest(customerId).cookie(session)).andExpect(status().isNoContent());

        jdbc.sql("update app_user set deleted_at = now() where user_id = :id").param("id", adminId).update();
        ruleLoader.evict(adminId);

        mvc.perform(get("/api/me/permissions").cookie(session))
                .andExpect(status().isUnauthorized());
    }

    /**
     * `V101` 의 검사 블록은 적용 때 한 번만 돈다 — 뒤의 마이그레이션이 이 권한을 다른 역할에 줘도 다시 안 본다.
     * 그래서 지금 DB 의 부여를 매번 잰다(마무리 45차 독립 리뷰).
     */
    @Test
    @DisplayName("대행 권한은 관리자에게만 열려 있다")
    void onlyAdminHoldsImpersonation() {
        assertThat(jdbc.sql("""
                        select r.code
                          from role_permission rp
                          join role r on r.role_id = rp.role_id
                          join permission p on p.permission_id = rp.permission_id
                         where p.resource = 'user' and p.action = 'impersonate' and rp.effect = 'allow'
                         order by 1
                        """)
                .query(String.class)
                .list())
                .containsExactly("admin");
    }

    private long insertUser(String email) {
        return jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values (:email, :hash, '대행 시험')
                        returning user_id
                        """)
                .param("email", email)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .query(Long.class)
                .single();
    }

    private Cookie logIn(String email) throws Exception {
        Cookie cookie = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getCookie(SESSION_COOKIE);
        assertThat(cookie).as("로그인이 세션 쿠키를 안 내렸다").isNotNull();
        return new Cookie(SESSION_COOKIE, cookie.getValue());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            startRequest(long userId) {
        return post("/api/admin/impersonation").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\": %d}".formatted(userId));
    }
}
