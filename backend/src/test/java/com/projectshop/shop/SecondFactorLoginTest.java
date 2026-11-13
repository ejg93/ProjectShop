package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.auth.SecondFactorLogin;
import com.projectshop.shop.auth.SecondFactorService;

/**
 * 셀러·관리자·감사자 로그인이 코드까지 맞아야 세션이 서는지 잰다(`Q452`, 안전성 확보조치 기준 제6조② `D2` R45).
 *
 * <p><b>비밀번호만 맞은 대상 계정의 세션은 인증 상태가 아니다.</b> 그 세션으로 {@code /api/me} 가 401 이어야 한다 —
 * 200 이면 2차 인증은 화면에만 있는 단계다. 실제 HTTP 를 지난다 — 세션 속성이 Redis 직렬화를 지나는지까지 본다.
 */
@DisplayName("2차 인증 로그인")
class SecondFactorLoginTest extends HttpTestBase {

    private static final String PASSWORD = "second-factor-login-pw";
    private static final Pattern SECRET = Pattern.compile("\"secret\":\"([A-Z2-7]+)\"");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    SecondFactorService secondFactors;

    @Test
    @DisplayName("고객은 비밀번호만으로 들어간다")
    void customerLogsInDirectly() {
        Session session = newSession();
        signUp(session, "sf-customer");

        Response login = logIn(session, "sf-customer");

        assertThat(login.body()).contains("\"step\":\"DONE\"");
        assertThat(session.get("/api/me").is(200)).isTrue();
    }

    @Test
    @DisplayName("등록된 관리자는 코드 전에는 인증 상태가 아니고 맞는 코드 뒤에 들어간다")
    void enrolledAdminNeedsCode() {
        Session session = newSession();
        long userId = admin(session, "sf-admin");
        SecondFactorLogin.enroll(secondFactors, userId);

        Response login = logIn(session, "sf-admin");

        assertThat(login.is(200)).isTrue();
        assertThat(login.body()).contains("\"step\":\"CODE_REQUIRED\"");
        assertThat(session.get("/api/me").is(401))
                .as("비밀번호만 맞은 대상 계정의 세션이 인증되면 2차 인증이 없는 것과 같다")
                .isTrue();

        Response confirmed = confirm(session, SecondFactorLogin.codeFor(jdbc, userId));

        assertThat(confirmed.is(200)).isTrue();
        assertThat(confirmed.body()).contains("\"step\":\"DONE\"");
        assertThat(session.get("/api/me").is(200)).isTrue();
    }

    @Test
    @DisplayName("틀린 코드는 401 이고 다섯 번 틀리면 로그인이 잠긴다")
    void wrongCodeCountsAsFailure() {
        Session session = newSession();
        long userId = admin(session, "sf-wrong");
        SecondFactorLogin.enroll(secondFactors, userId);
        logIn(session, "sf-wrong");
        String wrong = wrongCode(SecondFactorLogin.codeFor(jdbc, userId));

        Response first = confirm(session, wrong);
        assertThat(first.is(401)).isTrue();
        assertThat(first.body()).contains("second-factor-code-invalid");

        for (int i = 0; i < 4; i++) {
            confirm(session, wrong);
        }
        Response blocked = confirm(session, SecondFactorLogin.codeFor(jdbc, userId));
        assertThat(blocked.body())
                .as("코드 실패도 잠금이 센다 — 고시 제5조⑥")
                .contains("login-failed");
    }

    @Test
    @DisplayName("미등록 감사자는 등록을 거쳐 들어가고 등록을 두 번 불러도 같은 비밀이다")
    void unenrolledAuditorEnrolls() {
        Session session = newSession();
        long userId = signUpWithRole(session, "sf-auditor", "auditor");

        Response login = logIn(session, "sf-auditor");
        assertThat(login.body()).contains("\"step\":\"ENROLLMENT_REQUIRED\"");

        Response enrollment = session.post("/api/auth/login/second-factor/enrollment", "{}");
        Response again = session.post("/api/auth/login/second-factor/enrollment", "{}");
        assertThat(enrollment.is(200)).isTrue();
        assertThat(enrollment.body()).contains("otpauth://totp/");
        String secret = secretOf(enrollment);
        assertThat(secretOf(again)).as("새로 고침마다 비밀이 바뀌면 앱에 넣은 것과 갈린다").isEqualTo(secret);

        Response confirmed = confirm(session, SecondFactorLogin.codeForBase32(secret));

        assertThat(confirmed.is(200)).isTrue();
        assertThat(session.get("/api/me").is(200)).isTrue();
        assertThat(secondFactors.enrolled(userId)).isTrue();
    }

    @Test
    @DisplayName("대기 없이 코드를 내면 401 이다")
    void confirmWithoutPending() {
        Session session = newSession();
        session.get("/api/health");

        Response response = confirm(session, "123456");

        assertThat(response.is(401)).isTrue();
        assertThat(response.body()).contains("second-factor-not-pending");
    }

    private long admin(Session session, String name) {
        return signUpWithRole(session, name, "admin");
    }

    private long signUpWithRole(Session session, String name, String role) {
        signUp(session, name);
        long userId = jdbc.sql("select user_id from app_user where email = :email")
                .param("email", email(name))
                .query(Long.class)
                .single();
        jdbc.sql("""
                        insert into user_role (user_id, role_id)
                        select :userId, role_id from role where code = :role
                        """)
                .param("userId", userId)
                .param("role", role)
                .update();
        return userId;
    }

    private void signUp(Session session, String name) {
        session.get("/api/health");
        Response response = session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "http",
                  "birth_date": "1990-01-01",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(email(name), PASSWORD));
        assertThat(response.is(201)).isTrue();
    }

    private Response logIn(Session session, String name) {
        return session.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email(name), PASSWORD));
    }

    private static Response confirm(Session session, String code) {
        return session.post("/api/auth/login/second-factor", """
                {"code": "%s"}
                """.formatted(code));
    }

    private static String secretOf(Response response) {
        Matcher matcher = SECRET.matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }

    private static String wrongCode(String right) {
        return right.equals("000000") ? "000001" : "000000";
    }

    private static String email(String name) {
        return EMAIL_PREFIX + name + "@test.local";
    }
}
