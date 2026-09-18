package com.projectshop.shop.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * <p>인증 경로를 쓰는 이유는 상한이 스물이라 <b>시험이 짧아서</b>다.
 */
/** 여기만 켠다. 바탕이 끈 것을 되켜는 자리라 켜는 이유가 이 한 줄에 있다 */
@TestPropertySource(properties = "shop.rate-limit.enabled=true")
@DisplayName("요청 횟수 제한")
class RateLimitFilterTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    @DisplayName("상한을 넘기면 429 와 Retry-After 가 나간다")
    void 상한을_넘기면_429_다() throws Exception {
        // 앞선 시험이 남긴 값을 지운다. 창이 1분이라 안 지우면 회차끼리 서로 밟는다.
        redis.delete(redis.keys("rate:*"));

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
}
