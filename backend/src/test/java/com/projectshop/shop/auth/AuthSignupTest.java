package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import com.projectshop.shop.PostgresTestBase;

import tools.jackson.databind.ObjectMapper;

/**
 * 가입이 <b>계정과 동의를 같이</b> 남기는지를 본다.
 *
 * <p>여기서 잡으려는 실패는 계정만 생기고 동의가 빠지는 쪽이다.
 * 그 상태는 화면에 아무 증상이 없고, 나중에 "동의받았느냐" 를 물었을 때만 드러난다.
 */
@AutoConfigureMockMvc
class AuthSignupTest extends PostgresTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Nested
    @DisplayName("가입이 되면")
    class Success {

        @Test
        @DisplayName("계정과 동의가 같이 남는다")
        void createsUserAndConsents() throws Exception {
            long userId = signUpOk("a@test.local", required(true));

            Map<String, Object> user = jdbc.sql(
                            "select email, display_name, status from app_user where user_id = :id")
                    .param("id", userId)
                    .query()
                    .singleRow();

            assertThat(user).containsEntry("email", "a@test.local");
            assertThat(user).containsEntry("status", "active");

            List<String> consented = jdbc.sql("""
                            select item_code from current_consent
                             where user_id = :id and granted order by item_code
                            """)
                    .param("id", userId)
                    .query(String.class)
                    .list();

            assertThat(consented).containsExactly("privacy_collect", "terms_of_service");
        }

        @Test
        @DisplayName("비밀번호는 해시로만 저장된다")
        void storesOnlyTheHash() throws Exception {
            long userId = signUpOk("b@test.local", required(true));

            String stored = jdbc.sql("select password_hash from app_user where user_id = :id")
                    .param("id", userId)
                    .query(String.class)
                    .single();

            assertThat(stored).doesNotContain("hunter2-and-then-some");
            assertThat(stored).startsWith("{bcrypt}");
            assertThat(passwordEncoder.matches("hunter2-and-then-some", stored)).isTrue();
        }

        @Test
        @DisplayName("기본 역할이 붙는다")
        void grantsDefaultRole() throws Exception {
            long userId = signUpOk("c@test.local", required(true));

            List<String> roles = jdbc.sql("""
                            select r.code from user_role ur
                              join role r on r.role_id = ur.role_id
                             where ur.user_id = :id
                            """)
                    .param("id", userId)
                    .query(String.class)
                    .list();

            assertThat(roles)
                    .as("역할이 없으면 로그인해도 할 수 있는 것이 없다")
                    .containsExactly("customer");
        }

        @Test
        @DisplayName("감사 로그에 사건이 남는다")
        void writesAuditLog() throws Exception {
            long userId = signUpOk("d@test.local", required(true));

            String event = jdbc.sql(
                            "select event_type from audit_log where actor_user_id = :id")
                    .param("id", userId)
                    .query(String.class)
                    .single();

            assertThat(event).isEqualTo("user.signed_up");
        }

        @Test
        @DisplayName("응답 필드가 snake_case 로 나간다")
        void respondsInSnakeCase() throws Exception {
            signUp("e@test.local", required(true))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user_id").isNumber());
        }
    }

    @Nested
    @DisplayName("동의 규칙")
    class Consents {

        @Test
        @DisplayName("필수 항목을 거부하면 가입이 안 된다")
        void rejectsWhenRequiredIsDenied() throws Exception {
            Map<String, Boolean> consents = required(true);
            consents.put("privacy_collect", false);

            signUp("f@test.local", consents).andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("필수 항목이 빠져도 가입이 안 된다")
        void rejectsWhenRequiredIsMissing() throws Exception {
            Map<String, Boolean> consents = new LinkedHashMap<>();
            consents.put("terms_of_service", true);

            signUp("g@test.local", consents).andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("야간 수신만 켜면 거부한다")
        void rejectsNightWithoutItsChannel() throws Exception {
            Map<String, Boolean> consents = required(true);
            consents.put("marketing_night", true);

            signUp("h@test.local", consents).andExpect(status().isUnprocessableContent());
        }

        @Test
        @DisplayName("이메일 수신과 같이 켜면 야간도 받는다")
        void acceptsNightWithItsChannel() throws Exception {
            Map<String, Boolean> consents = required(true);
            consents.put("marketing_email", true);
            consents.put("marketing_night", true);

            long userId = signUpOk("i@test.local", consents);

            assertThat(grantedCodes(userId))
                    .contains("marketing_email", "marketing_night");
        }

        @Test
        @DisplayName("선택 항목을 거부하면 가입은 되고 거부가 기록된다")
        void recordsOptionalDenial() throws Exception {
            Map<String, Boolean> consents = required(true);
            consents.put("marketing_email", false);

            long userId = signUpOk("j@test.local", consents);

            Boolean granted = jdbc.sql("""
                            select granted from current_consent
                             where user_id = :id and item_code = 'marketing_email'
                            """)
                    .param("id", userId)
                    .query(Boolean.class)
                    .single();

            assertThat(granted)
                    .as("거부는 행으로 남는다. 안 건드린 것과 갈라야 한다")
                    .isFalse();
        }

        @Test
        @DisplayName("안 보낸 선택 항목은 행이 안 생긴다")
        void leavesUntouchedItemsAbsent() throws Exception {
            long userId = signUpOk("k@test.local", required(true));

            assertThat(jdbc.sql("""
                            select count(*) from current_consent
                             where user_id = :id and item_code = 'marketing_sms'
                            """)
                    .param("id", userId)
                    .query(Long.class)
                    .single())
                    .isZero();
        }

        @Test
        @DisplayName("모르는 항목 코드는 거부한다")
        void rejectsUnknownItem() throws Exception {
            Map<String, Boolean> consents = required(true);
            consents.put("sell_my_soul", true);

            signUp("l@test.local", consents).andExpect(status().isUnprocessableContent());
        }
    }

    @Nested
    @DisplayName("입력 검증")
    class Validation {

        /**
         * <b>경계를 짚는다.</b> 14자와 15자를 같이 보므로 최소 길이가 밀리면 한쪽이 깨진다.
         *
         * <p>15자인 이유는 NIST SP 800-63B Rev 4 다 — 비밀번호가 단독 인증수단이면
         * 그것이 {@code SHALL} 이고, 8자는 MFA 가 있을 때만 허용된다(`D14`).
         */
        @Test
        @DisplayName("14자를 막고 15자를 받는다")
        void enforcesMinimumLength() throws Exception {
            signUp("m@test.local", required(true), "a".repeat(14))
                    .andExpect(status().isBadRequest());

            signUp("m15@test.local", required(true), "a".repeat(15))
                    .andExpect(status().isCreated());
        }

        /**
         * <b>길이로 떨어지지 않게 15자를 넘긴다.</b> 짧은 한글을 쓰면 문자 집합이 아니라
         * 길이에 걸려서, 이 테스트가 무엇을 막는지 알 수 없게 된다.
         */
        @Test
        @DisplayName("한글 비밀번호를 막는다 — bcrypt 72바이트 절단 구간을 안 만든다")
        void rejectsNonAsciiPassword() throws Exception {
            signUp("n@test.local", required(true), "비밀번호입니다열다섯자넘음")
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("이메일 형식을 본다")
        void rejectsMalformedEmail() throws Exception {
            signUp("not-an-email", required(true)).andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("대소문자만 다른 이메일로 두 번 가입할 수 없다")
        void rejectsDuplicateEmailIgnoringCase() throws Exception {
            signUpOk("dup@test.local", required(true));

            signUp("DUP@test.local", required(true)).andExpect(status().isConflict());
        }

        @Test
        @DisplayName("가입 실패는 계정도 동의도 안 남긴다")
        void rollsBackEverythingOnFailure() throws Exception {
            Map<String, Boolean> consents = required(true);
            consents.put("marketing_night", true);

            signUp("rollback@test.local", consents).andExpect(status().isUnprocessableContent());

            assertThat(jdbc.sql("select count(*) from app_user where email = :email")
                    .param("email", "rollback@test.local")
                    .query(Long.class)
                    .single())
                    .isZero();
        }
    }

    private Map<String, Boolean> required(boolean granted) {
        Map<String, Boolean> consents = new LinkedHashMap<>();
        consents.put("terms_of_service", granted);
        consents.put("privacy_collect", granted);
        return consents;
    }

    private List<String> grantedCodes(long userId) {
        return jdbc.sql("""
                        select item_code from current_consent
                         where user_id = :id and granted order by item_code
                        """)
                .param("id", userId)
                .query(String.class)
                .list();
    }

    private ResultActions signUp(String email, Map<String, Boolean> consents) throws Exception {
        return signUp(email, consents, "hunter2-and-then-some");
    }

    private ResultActions signUp(String email, Map<String, Boolean> consents, String password)
            throws Exception {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);
        body.put("display_name", "가입자");
        body.put("consents", consents);

        return mvc.perform(post("/api/auth/signup")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long signUpOk(String email, Map<String, Boolean> consents) throws Exception {
        String response = signUp(email, consents)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(response).get("user_id").asLong();
    }
}
