package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 로그인 상태가 프로세스가 아니라 Redis 에 있나(`Q52`).
 *
 * <p><b>재배포마다 전원 로그아웃이던 것을 고친 자리다.</b> 세션이 서블릿 메모리에 있으면
 * 프로세스가 죽을 때 같이 사라지고, 인스턴스가 둘이면 한 대에만 있어서 로그인이 튄다.
 * 배포 창은 인스턴스를 늘리지 않아도 <b>두 대가 겹치는 구간</b>이다(`Q50` 과 같은 사실).
 *
 * <p><b>HTTP 층이라야 잰다.</b> 세션 저장소는 서블릿 필터가 끼워 넣는 것이라
 * {@code MockMvc} 로는 그 경로를 안 지난다 — 쿠키가 실제로 오가야 한다(`D15`).
 *
 * <p>둘째 테스트가 <b>후보 ② 를 고른 이유의 증거</b>다. 탈퇴가 세션 명부를 훑던 코드는
 * Redis 판 명부에서 예외가 나거나 빈 목록을 받는데, <b>빈 목록은 성공처럼 끝난다.</b>
 * 여기서 Redis 를 직접 들여다봐야 그것이 드러난다.
 */
class SessionStoreTest extends HttpTestBase {

    /** `application.yml` 의 `spring.session.redis.namespace` 와 같은 값이다 */
    private static final String SESSION_KEYS = "shop:session:sessions:*";

    private static final String PASSWORD = "hunter2-and-then-some";

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("로그인하면 세션이 Redis 에 남는다")
    void loginWritesSessionToRedis() {
        int before = sessionKeys().size();

        Session session = newSession();
        session.get("/api/health");
        signUp(session, "store");
        assertThat(logIn(session, "store").is(200)).isTrue();
        assertThat(session.get("/api/me").is(200)).isTrue();

        assertThat(sessionKeys())
                .describedAs("세션이 서블릿 메모리에 있으면 여기가 안 는다. "
                        + "spring-session-data-redis 를 빼면 이 단언이 빨개진다")
                .hasSizeGreaterThan(before);
    }

    @Test
    @DisplayName("세션 쿠키의 이름과 보호 속성이 그대로다")
    void sessionCookieKeepsItsContract() {
        Session session = newSession();
        session.get("/api/health");
        signUp(session, "cookie");

        // 로그인으로 잰다. 세션은 필요할 때만 만들어서(`IF_REQUIRED`) 열린 경로만 훑으면 안 생긴다.
        Response response = logIn(session, "cookie");
        assertThat(response.is(200)).isTrue();

        String setCookie = String.join(" | ",
                response.headers().getOrEmpty("Set-Cookie"));

        // **저장소를 갈아도 쿠키 계약은 안 바뀐다**(`D14`). Spring Session 은 자기 기본 이름
        // (`SESSION`)과 기본 속성을 쓰는데, Boot 이 `server.servlet.session.cookie` 를 넘겨 준다 —
        // **그 자리가 내장 서버가 있을 때만 걸려서** MockMvc 로는 확인이 안 된다.
        // 그래서 이 단언이 HTTP 층에 있다.
        assertThat(setCookie)
                .describedAs("이름이 바뀌면 배포 직후 모든 브라우저가 로그아웃된다")
                .contains("SHOPSESSION=");
        assertThat(setCookie)
                .describedAs("HttpOnly 가 빠지면 스크립트가 세션 쿠키를 읽는다(`D14`)")
                .contains("HttpOnly");
        assertThat(setCookie)
                .describedAs("SameSite 가 빠지면 CSRF 방어의 한 겹이 사라진다(`D14`)")
                .containsIgnoringCase("SameSite=Lax");
    }

    @Test
    @DisplayName("탈퇴하면 그 사람의 Redis 세션이 지워진다")
    void withdrawalDeletesSessionsFromRedis() {
        Session deviceA = newSession();
        deviceA.get("/api/health");
        signUp(deviceA, "gone");
        assertThat(logIn(deviceA, "gone").is(200)).isTrue();

        Session deviceB = newSession();
        deviceB.get("/api/health");
        assertThat(logIn(deviceB, "gone").is(200)).isTrue();

        int withBothDevices = sessionKeys().size();

        assertThat(deviceA.post("/api/me/withdraw",
                "{\"password\": \"%s\"}".formatted(PASSWORD)).is(204)).isTrue();

        // **만료 표시로는 이 단언이 안 선다.** 표시만 남기면 세션이 무활동 만료(30분)까지
        // Redis 에 그대로 있고 그 안에 이메일이 들어 있다 — 탈퇴는 개인정보를 거두는 자리다.
        assertThat(sessionKeys())
                .describedAs("두 기기의 세션이 둘 다 지워져야 한다. "
                        + "명부를 훑는 옛 방식은 빈 목록을 받고 성공처럼 끝난다")
                .hasSizeLessThan(withBothDevices);

        assertThat(deviceB.get("/api/me").is(401))
                .describedAs("다른 기기가 살아 있으면 탈퇴가 반쪽이다")
                .isTrue();
    }

    private Set<String> sessionKeys() {
        Set<String> keys = redis.keys(SESSION_KEYS);
        return keys == null ? Set.of() : keys;
    }

    private Response signUp(Session session, String name) {
        return session.post("/api/auth/signup", """
                {
                  "email": "%s",
                  "password": "%s",
                  "display_name": "세션",
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
        return EMAIL_PREFIX + "session-" + name + "@test.local";
    }
}
