package com.projectshop.shop.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * <h2>여기만 켠다</h2>
 *
 * <p>바탕 둘({@link PostgresTestBase}·{@code HttpTestBase})이 끈 것을 되켜는 자리다.
 * 켜 두면 로그인 시험 하나가 실패를 여럿 보내면서 <b>401 을 기대한 자리에 429</b> 를 받는다.
 *
 * <h2>fork 는 이미 갈려 있다 — 막을 것은 같은 fork 안이다</h2>
 *
 * <p>{@code Q100} 이 「fork 마다 Redis 를 나눠 쓰니 열쇠에 pid 를 넣자」고 했는데
 * <b>전제가 틀렸다</b>({@code Q104}, 마무리 27차 독립 리뷰). {@code PostgresTestBase.forkRedis} 가
 * 이미 <b>fork 마다 논리 DB 를 가른다</b>({@code 2i-2}) — pid 접두어는 같은 일을 한 번 더 하는 것이고,
 * 정작 <b>같은 fork 안의 다음 클래스</b>와는 값이 같아서 실제 실패 모드를 못 막았다. 그래서 걷었다.
 *
 * <p><b>막는 것은 {@link #카운터를_치운다} 하나고, 지키는 대상은 이 클래스 안이다.</b>
 * 저장소에서 제한을 켜는 것이 여기 하나뿐이라({@code SecurityConfig} 가 꺼져 있으면 필터를
 * <b>등록 자체를 안 한다</b>) 다른 클래스는 카운터가 남아도 429 를 받을 길이 없다 —
 * 처음에는 「{@code AuthLoginTest} 가 그 자리다」라고 적었는데 <b>거짓이었다</b>
 * (마무리 28차 독립 리뷰). <b>제한을 켜는 클래스가 둘이 되는 날</b> 이 정리가 그 둘 사이를 지킨다.
 */
@TestPropertySource(properties = "shop.rate-limit.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("요청 횟수 제한")
class RateLimitFilterTest extends PostgresTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${shop.rate-limit.key-prefix}")
    private String keyPrefix;

    /**
     * <b>이것이 이 청크의 강제 지점이다</b>({@code Q104}).
     *
     * <p>앞만 지우면 이 시험이 <b>카운터를 넘긴 채 끝나고</b>, 창 1분이 남아 있는 동안
     * 같은 열쇠를 쓰는 다음 것이 429 를 받는다. 아래 {@code 다음_것은_안_막힌다} 가
     * 그 다음 것 역할을 한다 — 이 메서드를 지우면 그 시험이 빨개진다.
     */
    @AfterEach
    void 카운터를_치운다() {
        redis.delete(redis.keys(keyPrefix + "*"));
    }

    @Test
    @Order(1)
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
     * <b>앞 시험이 넘긴 카운터가 여기로 안 넘어온다.</b> 창이 1분이라 치우지 않으면
     * 이 시험이 첫 요청부터 429 를 받는다 — 실제 저장소에서는 그 다음 것이
     * {@code AuthLoginTest} 고, 거기서는 401 을 기대한 자리에 429 가 온다.
     *
     * <p>순서를 {@link Order} 로 고정한다. 앞이 먼저 돌지 않으면 아무것도 안 재는 시험이 된다 —
     * <b>이 메서드만 따로 돌리면 그렇게 된다.</b> 그래서 들어올 때 카운터가 비어 있는지를 먼저 못박는다.
     */
    @Test
    @Order(2)
    @DisplayName("앞 시험이 상한을 넘겨도 다음 것은 안 막힌다")
    void 다음_것은_안_막힌다() throws Exception {
        org.assertj.core.api.Assertions.assertThat(redis.keys(keyPrefix + "*"))
                .as("앞 시험의 @AfterEach 가 돌았어야 한다 — 이 메서드만 따로 돌리면 이 단언이 알려 준다")
                .isEmpty();

        // **401 을 기대한다.** 인증이 없어서 진입점이 끊는 자리고, 그것이 이 시험에
        // 필요한 전부다 — 요청이 제한 필터를 지나 그 뒤까지 갔다는 뜻이다.
        // `not(429)` 로 두면 500 이나 경로가 사라진 것에도 초록이라 아무것도 안 잰다.
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isUnauthorized());
    }
}
