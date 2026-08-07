package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 요청 하나가 인증·판정·감사를 실제로 지나가는지를 <b>진짜 HTTP 로</b> 본다.
 *
 * <p>지금까지 이 확인은 청크마다 손으로 {@code curl} 을 걸어서 했다. 값을 했지만 —
 * 세 번의 실제 결함을 그렇게 잡았다 — 자동이 아니라서 <b>다음 청크가 깨뜨려도 모른다.</b>
 * 여기가 그 손 검증을 대체한다.
 */
class HttpFlowTest extends HttpTestBase {

    private static final String PASSWORD = "hunter2-and-then-some";

    @Autowired
    JdbcClient jdbc;

    @Nested
    @DisplayName("CSRF 토큰")
    class Csrf {

        @Test
        @DisplayName("아무 GET 에나 토큰 쿠키가 실려 온다")
        void issuesTokenCookie() {
            Session session = newSession();
            session.get("/api/health");

            assertThat(session.cookie("XSRF-TOKEN"))
                    .as("토큰을 못 받으면 클라이언트는 POST 를 아예 못 부른다")
                    .isNotBlank();
        }

        @Test
        @DisplayName("토큰 없는 POST 는 401 이다")
        void rejectsPostWithoutToken() {
            Session session = newSession();
            session.get("/api/health");

            Response response = session.postWithoutToken("/api/auth/signup", "{}");

            // MockMvc 에서는 같은 요청이 403 으로 나온다. 실제 서버의 답은 이쪽이다.
            // 어느 쪽이 진짜인지 여기서 확정한다.
            assertThat(response.is(401))
                    .as("실제 상태 코드는 %s 였다", response.status())
                    .isTrue();
        }

        @Test
        @DisplayName("받은 토큰을 실으면 지나간다")
        void passesWithToken() {
            Session session = newSession();
            session.get("/api/health");

            assertThat(signUp(session, "csrf").is(201)).isTrue();
        }

        @Test
        @DisplayName("쿠키만 있고 헤더가 없으면 막힌다")
        void cookieAloneIsNotEnough() {
            Session session = newSession();
            session.get("/api/health");

            // 쿠키는 브라우저가 자동으로 붙인다. 그것만으로 통과하면 남의 사이트에서 쏜
            // 요청도 지나가고 CSRF 방어가 없는 것과 같아진다.
            assertThat(session.postWithoutToken("/api/auth/signup", "{}").is(401)).isTrue();
        }

        @Test
        @DisplayName("남의 값을 헤더에 넣으면 막힌다")
        void forgedTokenIsRejected() {
            Session session = newSession();
            session.get("/api/health");

            assertThat(session.postWithForgedToken("/api/auth/signup", "{}").is(401)).isTrue();
        }
    }

    @Nested
    @DisplayName("가입부터 감사까지")
    class Vertical {

        @Test
        @DisplayName("가입하면 계정·역할·동의·감사가 함께 남는다")
        void signUpWritesEverything() {
            Session session = newSession();
            session.get("/api/health");

            Response signUp = signUp(session, "vertical");

            assertThat(signUp.is(201)).isTrue();
            long userId = userIdOf("vertical");

            assertThat(count("select count(*) from user_role where user_id = " + userId))
                    .as("역할이 없으면 로그인해도 할 수 있는 것이 없다").isEqualTo(1);
            assertThat(count("select count(*) from user_consent where user_id = " + userId))
                    .isEqualTo(2);
            assertThat(count("select count(*) from audit_log where actor_user_id = " + userId))
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("로그인하면 세션이 생기고 권한 목록이 열린다")
        void loginOpensPermissions() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "login");

            assertThat(logIn(session, "login").is(200)).isTrue();
            assertThat(session.cookie("SHOPSESSION")).isNotBlank();

            Response permissions = session.get("/api/me/permissions");

            assertThat(permissions.is(200)).isTrue();
            assertThat(permissions.body())
                    .as("응답은 snake_case 다(D5)")
                    .contains("\"user_id\"")
                    .contains("\"visible_field_groups\"");
        }

        @Test
        @DisplayName("권한 없는 조회가 거부되고 그 거부가 기록된다")
        void deniedAccessIsRecorded() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "denied");
            logIn(session, "denied");

            Response denied = session.get("/api/audit-logs");

            assertThat(denied.is(403)).isTrue();
            assertThat(denied.body())
                    .as("오류 본문은 RFC 9457 형식이다(D5)")
                    .contains("\"status\":403")
                    .contains("\"title\"");

            long userId = userIdOf("denied");
            assertThat(count("""
                    select count(*) from audit_log
                     where actor_user_id = %d and event_type = 'permission.denied'
                    """.formatted(userId)))
                    .as("거부가 안 남으면 그 경로가 감사에서 통째로 사라진다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("감사자는 쌓인 기록을 꺼내 본다")
        void auditorReadsTheLog() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "auditor");
            grantAuditor("auditor");
            logIn(session, "auditor");

            Response logs = session.get("/api/audit-logs?size=5");

            assertThat(logs.is(200)).isTrue();
            assertThat(logs.body())
                    .contains("\"items\"")
                    .contains("\"total\"")
                    .as("자기 가입 기록이 보여야 한다")
                    .contains("user.signed_up");
        }

        @Test
        @DisplayName("로그아웃하면 다시 막힌다")
        void logoutCloses() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "logout");
            logIn(session, "logout");

            assertThat(session.get("/api/me/permissions").is(200)).isTrue();
            assertThat(session.post("/api/auth/logout", null).is(204)).isTrue();
            assertThat(session.get("/api/me/permissions").is(401))
                    .as("로그아웃 뒤에도 열려 있으면 세션이 안 끊긴 것이다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("계정 관리")
    class AccountManagement {

        @Test
        @DisplayName("자기 계정을 보면 볼 수 있는 그룹이 같이 온다")
        void readsOwnAccount() {
            Session session = loggedIn("account");

            Response response = session.get("/api/me");

            assertThat(response.is(200)).isTrue();
            assertThat(response.body())
                    .contains("\"email\"")
                    .contains("\"_visible_field_groups\"");
        }

        @Test
        @DisplayName("채널을 거두면 야간 수신도 같이 거둬진다")
        void revokingChannelCascades() {
            Session session = loggedIn("consent");
            assertThat(session.post("/api/me/consents/marketing_email/grant", null).is(204)).isTrue();
            assertThat(session.post("/api/me/consents/marketing_night/grant", null).is(204)).isTrue();

            assertThat(session.post("/api/me/consents/marketing_email/revoke", null).is(204))
                    .isTrue();

            assertThat(session.get("/api/me/consents").body())
                    .as("채널 없는 야간 동의가 남으면 나중에 채널만 켜질 때 야간까지 열린다")
                    .contains("\"code\":\"marketing_night\",\"title\":\"야간 광고성 정보 수신 (21시~08시)\","
                            + "\"required\":false,\"granted\":false");
        }

        @Test
        @DisplayName("필수 동의는 거둘 수 없다")
        void requiredConsentCannotBeRevoked() {
            Session session = loggedIn("required");

            Response response = session.post("/api/me/consents/terms_of_service/revoke", null);

            assertThat(response.is(422)).isTrue();
            assertThat(response.body()).contains("\"status\":422");
        }

        /**
         * `ADR 0010` 의 핵심이다. 로그인 시점 검사만으로는 <b>이미 열린 다른 기기</b>가 안 막힌다.
         *
         * <p>이 시나리오는 손 {@code curl} 로만 확인돼 있었다. 2차 점검이 그걸 부채로 잡았다.
         */
        @Test
        @DisplayName("한 기기에서 탈퇴하면 다른 기기 세션도 끊긴다")
        void withdrawalKillsOtherDevices() {
            Session deviceA = loggedIn("twodev");

            Session deviceB = newSession();
            deviceB.get("/api/health");
            assertThat(logIn(deviceB, "twodev").is(200)).isTrue();

            assertThat(deviceA.get("/api/me").is(200)).isTrue();
            assertThat(deviceB.get("/api/me").is(200)).isTrue();

            assertThat(deviceA.post("/api/me/withdraw",
                    "{\"password\": \"%s\"}".formatted(PASSWORD)).is(204)).isTrue();

            assertThat(deviceA.get("/api/me").is(401)).isTrue();
            assertThat(deviceB.get("/api/me").is(401))
                    .as("다른 기기가 살아 있으면 탈퇴가 반쪽이다")
                    .isTrue();
        }

        @Test
        @DisplayName("탈퇴하면 수명만 끊기고 동의는 거둬진다")
        void withdrawalEndsLifetimeAndConsents() {
            Session session = loggedIn("bye");

            assertThat(session.post("/api/me/withdraw",
                    "{\"password\": \"%s\"}".formatted(PASSWORD)).is(204)).isTrue();

            long userId = userIdOf("bye");
            assertThat(count("select count(*) from app_user where user_id = " + userId))
                    .as("주문 기록이 5년 남아야 해서 행은 지우지 않는다(D13)")
                    .isEqualTo(1);
            assertThat(count("""
                    select count(*) from current_consent where user_id = %d and granted
                    """.formatted(userId)))
                    .as("계약이 끝났는데 동의가 유효한 채로 남으면 안 된다")
                    .isZero();
        }

        @Test
        @DisplayName("비밀번호가 틀리면 탈퇴가 안 된다")
        void withdrawalNeedsThePassword() {
            Session session = loggedIn("guard");

            assertThat(session.post("/api/me/withdraw",
                    "{\"password\": \"wrong-but-long-enough\"}").is(422)).isTrue();
            assertThat(session.get("/api/me").is(200))
                    .as("되돌릴 수 없는 조작이라 세션만으로는 부족하다")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("인증 실패")
    class Failures {

        @Test
        @DisplayName("로그인 없이 잠긴 경로는 401 이고 리다이렉트가 없다")
        void unauthenticatedIsUnauthorized() {
            Response response = newSession().get("/api/me/permissions");

            assertThat(response.is(401)).isTrue();
            assertThat(response.headers().getFirst("Location"))
                    .as("폼 로그인이 켜져 있으면 여기에 /login 이 들어온다")
                    .isNull();
        }

        @Test
        @DisplayName("탈퇴한 계정은 로그인되지 않는다")
        void deletedAccountCannotLogIn() {
            Session session = newSession();
            session.get("/api/health");
            signUp(session, "gone");

            jdbc.sql("update app_user set deleted_at = now() where email = :email")
                    .param("email", email("gone"))
                    .update();

            assertThat(logIn(session, "gone").is(401)).isTrue();
        }
    }

    /** 토큰을 받고 가입해서 로그인까지 마친 세션. 계정 관리 테스트가 매번 밟는 준비 단계다. */
    private Session loggedIn(String name) {
        Session session = newSession();
        session.get("/api/health");
        signUp(session, name);
        logIn(session, name);
        return session;
    }

    private Response signUp(Session session, String name) {
        return session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "http",
                  "consents": {"terms_of_service": true, "privacy_collect": true}
                }
                """.formatted(email(name), PASSWORD));
    }

    private Response logIn(Session session, String name) {
        return session.post("/api/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email(name), PASSWORD));
    }

    private static String email(String name) {
        return EMAIL_PREFIX + name + "@test.local";
    }

    private void grantAuditor(String name) {
        jdbc.sql("""
                insert into user_role (user_id, role_id)
                select u.user_id, r.role_id from app_user u, role r
                 where u.email = :email and r.code = 'auditor'
                """).param("email", email(name)).update();
    }

    private long userIdOf(String name) {
        return jdbc.sql("select user_id from app_user where email = :email")
                .param("email", email(name))
                .query(Long.class)
                .single();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
