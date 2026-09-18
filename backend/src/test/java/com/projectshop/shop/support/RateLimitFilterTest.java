package com.projectshop.shop.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.projectshop.shop.PostgresTestBase;

/**
 * 요청 횟수 제한({@code 71}, {@code D14}).
 *
 * <p><b>상태 코드와 헤더가 계약이다.</b> RFC 6585 가 429 를 정하고 RFC 9110 이
 * {@code Retry-After} 를 이 상태에 둔다 — 값이 없으면 받는 쪽이 언제 다시 걸지 몰라
 * <b>즉시 재시도한다</b>. 그러면 제한이 붐빔을 더 키운다.
 *
 * <h2>여기만 켠다</h2>
 *
 * <p>바탕 둘({@link PostgresTestBase}·{@code HttpTestBase})이 끈 것을 되켜는 자리다.
 * 켜 두면 로그인 시험 하나가 실패를 여럿 보내면서 <b>401 을 기대한 자리에 429</b> 를 받는다.
 *
 * <h2>열쇠를 이 fork 만의 것으로 가른다</h2>
 *
 * <p>느린 레인은 fork 가 여럿이고 Redis 는 하나다({@code Q100}). 접두어가 고정이면
 * <b>시험 클래스들이 카운터 하나를 나눠 쓰고</b>, 이 시험이 카운터를 넘긴 채 끝나면
 * 창 1분 안에 {@code /api/auth/*} 를 치는 다음 클래스가 429 를 받는다.
 * {@code KafkaTestBase} 가 토픽 이름에 pid 를 넣은 것과 같은 수다.
 */
@TestPropertySource(properties = "shop.rate-limit.enabled=true")
@DisplayName("요청 횟수 제한")
class RateLimitFilterTest extends PostgresTestBase {

    /** fork 마다 하나. 같은 Redis 를 쓰는 다른 fork 와 안 겹친다 */
    private static final String KEY_PREFIX = "rate-test-" + ProcessHandle.current().pid() + ":";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void rateLimit(DynamicPropertyRegistry registry) {
        registry.add("shop.rate-limit.key-prefix", () -> KEY_PREFIX);
    }

    /**
     * <b>끝날 때도 지운다.</b> 앞만 지우면 이 시험이 카운터를 넘긴 채 끝나고,
     * 창 1분이 남아 있는 동안 같은 열쇠를 쓰는 것이 걸린다({@code Q100}).
     */
    @AfterEach
    void 카운터를_치운다() {
        redis.delete(redis.keys(KEY_PREFIX + "*"));
    }

    @Test
    @DisplayName("상한을 넘기면 429 와 Retry-After 가 나간다")
    void 상한을_넘기면_429_다() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(get("/api/auth/session"));
        }

        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.type")
                        .value("tag:projectshop.example,2026:error:too-many-requests"))
                .andExpect(jsonPath("$.trace_id").isNotEmpty());
    }

    /**
     * <b>이것이 {@code Q100} 의 통과 기준이다.</b> 이 시험이 끝난 뒤 같은 fork 의 다른
     * 클래스가 인증 경로를 쳐도 안 막힌다 — 열쇠가 갈렸고 뒤도 치웠기 때문이다.
     */
    @Test
    @DisplayName("치운 뒤에는 기본 접두어 열쇠가 남지 않는다")
    void 기본_접두어_열쇠를_안_남긴다() throws Exception {
        for (int i = 0; i < 21; i++) {
            mvc.perform(get("/api/auth/session"));
        }

        org.assertj.core.api.Assertions.assertThat(redis.keys("rate:*")).isEmpty();
    }
}
