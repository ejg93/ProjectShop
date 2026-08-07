package com.projectshop.shop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;

/**
 * 진짜 서블릿 컨테이너를 띄우고 실제 HTTP 로 요청을 보내는 바탕.
 *
 * <p>MockMvc 는 서블릿 컨테이너를 안 띄운다. 그래서 갈리는 자리가 실제로 있었다 —
 * 5-1 은 거부 코드가 달랐고, 5-2 는 테스트 17개가 전부 통과하는데 아무도 가입을 못 했으며,
 * 5 에서는 손대지 않은 테스트가 깨졌다. 셋 다 손으로 {@code curl} 을 걸어서 잡았다.
 * 이 바탕은 그 손 검증을 테스트로 옮긴 것이다.
 *
 * <h2>롤백이 없다</h2>
 *
 * <p>요청이 별도 스레드에서 자기 트랜잭션으로 돌기 때문에 {@code @Transactional} 이 안 먹는다.
 * 테스트가 만든 데이터는 <b>직접 지운다.</b> 그래서 이 층의 테스트는
 * 계정 이메일을 {@link #EMAIL_PREFIX} 로 시작하게 만들고, 아래가 그것만 지운다.
 *
 * <p>이 제약 때문에 여기 둘 것을 고른다. <b>관통하는 흐름</b>만 여기서 보고,
 * 규칙 하나하나는 롤백이 도는 통합 층({@link PostgresTestBase})에 둔다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestBase.Containers.class)
public abstract class HttpTestBase {

    /** 이 층이 만든 계정임을 알아보는 표시. 정리가 이것만 지운다. */
    protected static final String EMAIL_PREFIX = "http-test-";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient jdbc;

    /**
     * 이 층이 남긴 것만 지운다.
     *
     * <p>{@code audit_log} 는 계정에 외래키가 없어서(V10) 같이 안 지워진다. 따로 지운다 —
     * 안 지우면 감사 조회 테스트가 남의 층이 남긴 행을 세게 된다.
     */
    @AfterEach
    protected void cleanUp() {
        jdbc.sql("""
                delete from audit_log
                 where actor_user_id in (select id from app_user where email like :prefix)
                """).param("prefix", EMAIL_PREFIX + "%").update();

        jdbc.sql("delete from app_user where email like :prefix")
                .param("prefix", EMAIL_PREFIX + "%")
                .update();
    }

    /** 한 사람이 브라우저 하나로 하는 일. 쿠키를 들고 다닌다. */
    protected Session newSession() {
        return new Session(RestClient.create("http://localhost:" + port));
    }

    protected record Response(HttpStatusCode status, String body, HttpHeaders headers) {

        public boolean is(int code) {
            return status.value() == code;
        }
    }

    /**
     * 쿠키를 손으로 나른다.
     *
     * <p>브라우저가 하는 일을 흉내 내는 것이 이 층의 핵심이다. 세션 쿠키와 CSRF 토큰이
     * 실제로 오가는지를 보려는 것이라, 자동으로 붙여 주는 클라이언트를 쓰면 검증이 사라진다.
     */
    protected static final class Session {

        private static final String CSRF_COOKIE = "XSRF-TOKEN";
        private static final String CSRF_HEADER = "X-XSRF-TOKEN";

        private final RestClient client;
        private final Map<String, String> cookies = new LinkedHashMap<>();

        private Session(RestClient client) {
            this.client = client;
        }

        public Response get(String path) {
            return exchange(client.get().uri(path));
        }

        /** CSRF 토큰을 자동으로 싣는다. 토큰 없이 보내는 경우는 {@link #postWithoutToken} 이다. */
        public Response post(String path, String json) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON);

            String token = cookies.get(CSRF_COOKIE);
            if (token != null) {
                spec = spec.header(CSRF_HEADER, token);
            }
            return exchange(json == null ? spec : spec.body(json));
        }

        public Response postWithoutToken(String path, String json) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON);
            return exchange(json == null ? spec : spec.body(json));
        }

        /** 쿠키는 제대로 들고 있는데 헤더 값만 남의 것인 경우 */
        public Response postWithForgedToken(String path, String json) {
            RestClient.RequestBodySpec spec = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CSRF_HEADER, "made-up-value");
            return exchange(json == null ? spec : spec.body(json));
        }

        public String cookie(String name) {
            return cookies.get(name);
        }

        private Response exchange(RestClient.RequestHeadersSpec<?> spec) {
            if (!cookies.isEmpty()) {
                spec = spec.header(HttpHeaders.COOKIE, joinCookies());
            }
            return spec.exchange((request, response) -> {
                HttpHeaders headers = response.getHeaders();
                storeCookies(headers.get(HttpHeaders.SET_COOKIE));
                return new Response(response.getStatusCode(),
                        new String(response.getBody().readAllBytes()), headers);
            }, false);
        }

        private String joinCookies() {
            return cookies.entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("");
        }

        private void storeCookies(List<String> setCookies) {
            if (setCookies == null) {
                return;
            }
            for (String raw : setCookies) {
                String pair = raw.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq);
                String value = pair.substring(eq + 1);
                // 값이 빈 Set-Cookie 는 삭제 지시다. 로그아웃이 세션 쿠키를 이렇게 지운다.
                if (value.isEmpty()) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
        }
    }
}
