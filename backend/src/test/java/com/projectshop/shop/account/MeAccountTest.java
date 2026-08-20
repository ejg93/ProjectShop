package com.projectshop.shop.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.AuthFixture;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 자기 계정을 보고 고치는 경로. <b>필드 마스킹(4d)이 실제 응답에 걸리는 첫 자리다.</b>
 *
 * <p>지금까지 판정은 허용 여부만 쓰였다. 여기서부터 {@code visibleFieldGroups} 가
 * 응답 모양을 바꾼다 — 못 보는 필드는 null 이 아니라 <b>키 자체가 없다</b>.
 */
@AutoConfigureMockMvc
class MeAccountTest extends PostgresTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    AuthFixture fixture;
    long customer;
    long auditor;

    @BeforeEach
    void setUp() {
        fixture = new AuthFixture(jdbc);

        customer = insertUser("me-customer@test.local", "고객");
        fixture.grantGlobal(customer, "customer");

        auditor = insertUser("me-auditor@test.local", "감사자");
        fixture.grantGlobal(auditor, "auditor");
    }

    @Nested
    @DisplayName("조회")
    class Read {

        @Test
        @DisplayName("고객은 자기 이메일까지 본다")
        void customerSeesContact() throws Exception {
            mvc.perform(get("/api/me").with(user(principal(customer, "me-customer@test.local"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user_id").value(customer))
                    .andExpect(jsonPath("$.display_name").value("고객"))
                    .andExpect(jsonPath("$.email").value("me-customer@test.local"))
                    .andExpect(jsonPath("$._visible_field_groups").isArray());
        }

        @Test
        @DisplayName("감사자는 이메일 필드가 아예 안 온다")
        void auditorGetsNoContactField() throws Exception {
            // V7 이 감사자의 user:read 를 basic 으로만 묶었다. 마스킹이 실제로 도는지가 여기서 갈린다.
            mvc.perform(get("/api/me").with(user(principal(auditor, "me-auditor@test.local"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.display_name").value("감사자"))
                    .andExpect(jsonPath("$.email").doesNotExist())
                    .andExpect(jsonPath("$._visible_field_groups").value("basic"));
        }

        @Test
        @DisplayName("무엇이 보이는지를 응답이 스스로 알린다")
        void tellsWhichGroupsAreVisible() throws Exception {
            // null 로 내리면 "값이 없다" 와 "볼 수 없다" 가 같아 보인다. 이 필드가 그걸 가른다.
            mvc.perform(get("/api/me").with(user(principal(customer, "me-customer@test.local"))))
                    .andExpect(jsonPath("$._visible_field_groups", org.hamcrest.Matchers.hasItems(
                            "basic", "contact")));
        }

        /**
         * 제한이 없을 때 <b>빈 배열이 아니라 전부</b>가 실린다(`13b`, 사용자 선택).
         *
         * <p>그전에는 제한이 없으면 빈 배열이었다. 그러면 같은 `[]` 가
         * <b>「다 보인다」와 「아무것도 안 보인다」 둘</b>을 뜻해서, 응답만 봐서는 안 갈렸다 —
         * 「빈 값에 뜻을 싣지 않는다」(`D23`)에 걸리던 자리다.
         *
         * <p>판정 엔진 안에서는 안 모호했다. {@code Allowed} 가 타입으로 갈라 두는데
         * <b>직렬화하면서 뭉개졌다.</b>
         */
        @Test
        @DisplayName("제한이 없으면 그 자원의 그룹이 전부 실린다")
        void unrestrictedListsEveryGroup() throws Exception {
            long admin = insertUser("me-admin@test.local", "관리자");
            fixture.grantGlobal(admin, "admin");

            mvc.perform(get("/api/me").with(user(principal(admin, "me-admin@test.local"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$._visible_field_groups", org.hamcrest.Matchers.hasItems(
                            "basic", "contact")))
                    // 빈 배열이면 이제 「아무것도 못 본다」 하나만 뜻한다.
                    .andExpect(jsonPath("$._visible_field_groups.length()").value(2));
        }

        @Test
        @DisplayName("로그인 없이는 못 본다")
        void requiresLogin() throws Exception {
            mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("표시 이름 변경")
    class Rename {

        @Test
        @DisplayName("바꾸면 바뀐 계정이 돌아온다")
        void changesAndReturns() throws Exception {
            mvc.perform(patch("/api/me")
                            .with(user(principal(customer, "me-customer@test.local")))
                            .with(csrf())
                            .contentType("application/merge-patch+json")
                            .content("{\"display_name\": \"바꾼이름\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.display_name").value("바꾼이름"));
        }

        @Test
        @DisplayName("빈 이름은 막는다")
        void rejectsBlank() throws Exception {
            mvc.perform(patch("/api/me")
                            .with(user(principal(customer, "me-customer@test.local")))
                            .with(csrf())
                            .contentType("application/merge-patch+json")
                            .content("{\"display_name\": \"  \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("감사자는 자기 이름도 못 바꾼다")
        void auditorCannotUpdate() throws Exception {
            // V5 가 감사자에게 읽기 아닌 것을 전부 거부했다. 자기 계정도 예외가 아니다.
            mvc.perform(patch("/api/me")
                            .with(user(principal(auditor, "me-auditor@test.local")))
                            .with(csrf())
                            .contentType("application/merge-patch+json")
                            .content("{\"display_name\": \"바꿔보기\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("비밀번호 변경")
    class ChangePassword {

        @Test
        @DisplayName("현재 비밀번호를 맞히면 바뀐다")
        void changesWithCurrentPassword() throws Exception {
            mvc.perform(passwordRequest(PASSWORD, "brand-new-secret-1"))
                    .andExpect(status().isNoContent());

            String stored = jdbc.sql("select password_hash from app_user where user_id = :id")
                    .param("id", customer)
                    .query(String.class)
                    .single();

            assertThat(passwordEncoder.matches("brand-new-secret-1", stored)).isTrue();
        }

        @Test
        @DisplayName("현재 비밀번호가 틀리면 안 바뀐다")
        void rejectsWrongCurrentPassword() throws Exception {
            mvc.perform(passwordRequest("wrong-but-long-enough", "brand-new-secret-1"))
                    .andExpect(status().isUnprocessableContent());

            String stored = jdbc.sql("select password_hash from app_user where user_id = :id")
                    .param("id", customer)
                    .query(String.class)
                    .single();

            assertThat(passwordEncoder.matches(PASSWORD, stored))
                    .as("세션을 훔친 사람이 비밀번호까지 바꾸면 주인이 계정을 영영 잃는다")
                    .isTrue();
        }

        @Test
        @DisplayName("새 비밀번호도 가입과 같은 규칙을 탄다")
        void newPasswordFollowsTheSameRules() throws Exception {
            mvc.perform(passwordRequest(PASSWORD, "한글이라막힌다열다섯자넘김"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("바꾼 사실이 감사에 남는다")
        void recordsTheChange() throws Exception {
            mvc.perform(passwordRequest(PASSWORD, "brand-new-secret-1"))
                    .andExpect(status().isNoContent());

            String detail = jdbc.sql("""
                            select detail::text from audit_log
                             where actor_user_id = :id and event_type = 'user.password_changed'
                            """)
                    .param("id", customer)
                    .query(String.class)
                    .single();

            assertThat(detail)
                    .as("무엇으로 바꿨는지는 남기지 않는다(D16)")
                    .doesNotContain("brand-new-secret-1");
        }
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            passwordRequest(String current, String next) {

        return post("/api/me/password")
                .with(user(principal(customer, "me-customer@test.local")))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"current_password": "%s", "new_password": "%s"}
                        """.formatted(current, next));
    }

    private long insertUser(String email, String displayName) {
        return jdbc.sql("""
                        insert into app_user (email, password_hash, display_name)
                        values (:email, :hash, :name)
                        returning user_id
                        """)
                .param("email", email)
                .param("hash", passwordEncoder.encode(PASSWORD))
                .param("name", displayName)
                .query(Long.class)
                .single();
    }

    private static ShopUser principal(long id, String email) {
        return new ShopUser(id, email, "{noop}x", true);
    }
}
