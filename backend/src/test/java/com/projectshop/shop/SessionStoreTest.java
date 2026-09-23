package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.session.FindByIndexNameSessionRepository;

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

    /**
     * `application.yml` 의 `spring.session.data.redis.namespace` 와 같은 값이다.
     *
     * <p><b>경로가 `spring.session.redis` 가 아니다</b>(Boot 4). 그 옛 경로에 적으면 조용히 안 먹는다 —
     * 같은 청크가 설정 파일에 그 경고를 박아 뒀는데 여기 주석이 옛 이름을 부르고 있었다(마무리 17차).
     */
    private static final String SESSION_KEYS = "shop:session:sessions:*";

    /** Spring Session 이 principal 이름으로 세션을 찾을 때 쓰는 색인 이름 */
    private static final String PRINCIPAL_INDEX =
            FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME;

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

        // **그 사람의 색인을 직접 센다.** 전체 열쇠 수가 줄었나로 재면 **하나만 지워져도 통과한다**
        // (마무리 17차 독립 리뷰). 뒤따르는 401 도 그 구멍을 못 메운다 —
        // `AccountLivenessFilter` 가 `deleted_at` 만 보고 401 을 주므로
        // **세션이 Redis 에 그대로 남아 있어도 401 이다.** 두 겹을 갈라야 각 겹을 잰다(`D14`).
        //
        // **전에는 두 기기로 쟀다**(`Q63` 이전). 동시접속이 1개가 되면서 둘째 로그인이 첫째를
        // 끊으므로 그 시나리오가 성립하지 않는다 — **여러 개를 다 지우나는 이제 제한이 대신 답한다**:
        // 애초에 여러 개가 안 생긴다. 여기서는 남은 한 겹, 「표시가 아니라 삭제인가」를 잰다.
        assertThat(sessionsOf("gone"))
                .describedAs("로그인한 세션이 색인에 잡혀야 지워진 것을 잴 수 있다")
                .isEqualTo(1);

        assertThat(deviceA.post("/api/me/withdraw",
                "{\"password\": \"%s\"}".formatted(PASSWORD)).is(204)).isTrue();

        // **만료 표시로는 이 단언이 안 선다.** 표시만 남기면 세션이 무활동 만료(30분)까지
        // Redis 에 그대로 있고 그 안에 이메일이 들어 있다 — 탈퇴는 개인정보를 거두는 자리다.
        assertThat(sessionsOf("gone"))
                .describedAs("세션이 실제로 지워져야 한다. "
                        + "명부를 훑는 옛 방식은 빈 목록을 받고 성공처럼 끝난다")
                .isZero();

        assertThat(deviceA.get("/api/me").is(401))
                .describedAs("지운 세션으로는 아무것도 못 한다")
                .isTrue();
    }

    /**
     * 그 사람 앞으로 Redis 에 남아 있는 세션 수.
     *
     * <p>Spring Session 의 principal 색인을 직접 읽는다. 열쇠가 principal 이름이고
     * 이 저장소에서는 그것이 이메일이다({@code ShopUser.getUsername()}).
     */
    private long sessionsOf(String name) {
        Long size = redis.opsForSet().size(
                "shop:session:index:" + PRINCIPAL_INDEX + ":" + email(name));
        return size == null ? 0 : size;
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
                  "birth_date": "1990-01-01",
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
