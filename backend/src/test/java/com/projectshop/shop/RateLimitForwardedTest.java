package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 한 프록시를 지나는 손님 둘이 <b>버킷이 둘인가</b>(`Q240`).
 *
 * <p>서버 렌더 요청은 Next 서버가 새로 내서 백엔드가 보기에 주소가 Next 하나다. 화면이 손님 주소를
 * {@code X-Forwarded-For} 로 실으면 Tomcat 의 {@code RemoteIpValve} 가 믿는 프록시(시험은 루프백)의 헤더를
 * 풀어 {@code getRemoteAddr()} 를 손님 주소로 바꾸고, {@code RateLimitFilter} 가 그 주소로 센다.
 *
 * <p><b>실제 HTTP 여야 한다.</b> 밸브는 Tomcat 이 하는 일이라 MockMvc 에서는 이 헤더가 아무 일도 안 한다.
 *
 * <p><b>429 를 HTTP 로 받아 보지 않고 버킷 열쇠로 잰다.</b> 시험 클라이언트(Apache HttpClient 5)의 기본 재시도가
 * 429 를 {@code Retry-After}(60초)만큼 기다렸다 다시 보내서, 창이 닫힌 뒤 새 버킷의 첫 요청이 된다(실측 — `stack.md`).
 * 상한을 넘기면 429 인 것은 {@code RateLimitFilterTest} 가 MockMvc 로 잰다.
 */
@TestPropertySource(properties = "shop.rate-limit.enabled=true")
@DisplayName("요청 제한 — 앞단 프록시를 지나는 손님")
class RateLimitForwardedTest extends HttpTestBase {

    /** 인증 경로의 상한(`RateLimitFilter.AUTH_LIMIT`). 열쇠가 상한을 품는다 */
    private static final int AUTH_LIMIT = 20;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${shop.rate-limit.key-prefix}")
    private String keyPrefix;

    @AfterEach
    void 카운터를_치운다() {
        redis.delete(redis.keys(keyPrefix + "*"));
    }

    @Test
    @DisplayName("같은 프록시를 지나는 손님 둘은 버킷이 둘이고 프록시 주소의 버킷은 안 생긴다")
    void bucketsAreSplitByClient() {
        Session proxy = newSession();

        for (int i = 0; i < 3; i++) {
            proxy.getForwardedFrom("/api/auth/session", "198.51.100.1");
        }
        proxy.getForwardedFrom("/api/auth/session", "198.51.100.2");

        assertThat(countOf("198.51.100.1")).isEqualTo("3");
        assertThat(countOf("198.51.100.2"))
                .as("한 버킷이면 여기가 비고 앞 손님 버킷이 4 다")
                .isEqualTo("1");
        assertThat(redis.keys(keyPrefix + "*"))
                .as("프록시(루프백) 주소로 센 버킷이 없다 — 헤더를 안 실으면 이것 하나만 생긴다")
                .noneMatch(key -> key.endsWith(":127.0.0.1") || key.contains(":0:0:0:0:0:0:0:1"));
    }

    private String countOf(String clientIp) {
        return redis.opsForValue().get(keyPrefix + AUTH_LIMIT + ":" + clientIp);
    }
}
