package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.projectshop.shop.PostgresTestBase;

/**
 * 로그인 실패를 세고 잠그는가(`D14`).
 *
 * <p>여기서 보는 것은 <b>(계정, IP) 조합이 단위</b>라는 것이다. 계정만 세면 남의 계정에
 * 일부러 틀려서 잠그는 공격이 열리고, IP 만 세면 공용 IP 뒤의 사람들이 같이 막힌다.
 */
@DisplayName("로그인 실패 제한")
class LoginAttemptTest extends PostgresTestBase {

    private static final String EMAIL = "attempt@test.local";
    private static final String IP = "203.0.113.10";

    @Autowired
    private LoginAttemptService attempts;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearCounters() {
        // 트랜잭션 롤백이 Redis 를 안 되돌린다. 앞 테스트가 남긴 키를 직접 지운다.
        redis.delete(redis.keys("login:fail:*"));
    }

    @Nested
    @DisplayName("한도까지는")
    class WithinLimit {

        @Test
        @DisplayName("네 번 틀려도 안 잠긴다")
        void staysOpen() {
            fail(4);

            assertThat(attempts.isBlocked(EMAIL, IP)).isFalse();
        }

        @Test
        @DisplayName("성공하면 카운터가 지워진다")
        void resetsOnSuccess() {
            fail(4);
            attempts.reset(EMAIL, IP);
            fail(4);

            assertThat(attempts.isBlocked(EMAIL, IP))
                    .as("성공 뒤에도 옛 실패가 남아 있으면 정상 사용자가 갑자기 잠긴다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("한도를 넘으면")
    class OverLimit {

        @Test
        @DisplayName("다섯 번째에 잠긴다")
        void blocks() {
            fail(5);

            assertThat(attempts.isBlocked(EMAIL, IP)).isTrue();
        }

        @Test
        @DisplayName("저절로 풀리도록 만료가 걸려 있다")
        void expiresOnItsOwn() {
            fail(5);

            assertThat(redis.getExpire("login:fail:" + EMAIL + ":" + IP))
                    .as("관리자 해제 화면을 안 만든다. 시간이 지나면 풀리므로 해제할 것이 없다(`D14`)")
                    .isPositive();
        }

        @Test
        @DisplayName("계속 틀려도 차단 창이 안 밀린다")
        void doesNotSlideTheWindow() {
            fail(5);
            long afterFive = redis.getExpire("login:fail:" + EMAIL + ":" + IP);

            fail(5);
            long afterTen = redis.getExpire("login:fail:" + EMAIL + ":" + IP);

            assertThat(afterTen)
                    .as("실패마다 만료를 다시 걸면 15분이 '마지막 실패로부터 15분' 이 된다")
                    .isLessThanOrEqualTo(afterFive);
        }
    }

    @Nested
    @DisplayName("세는 단위는")
    class Scope {

        @Test
        @DisplayName("다른 IP 는 따로 센다")
        void isPerIp() {
            fail(5);

            assertThat(attempts.isBlocked(EMAIL, "198.51.100.20"))
                    .as("계정만 보고 잠그면 남의 계정을 일부러 잠그는 공격이 열린다")
                    .isFalse();
        }

        @Test
        @DisplayName("다른 계정은 따로 센다")
        void isPerAccount() {
            fail(5);

            assertThat(attempts.isBlocked("other@test.local", IP))
                    .as("IP 만 보고 잠그면 공용 IP 뒤의 사람들이 같이 막힌다")
                    .isFalse();
        }
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            attempts.recordFailure(EMAIL, IP);
        }
    }
}
