package com.projectshop.shop.support;

import java.io.IOException;
import java.time.Duration;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 한 주소가 창 하나에 보낼 수 있는 요청 수를 막는다({@code 71}, {@code D14}).
 *
 * <h2>로그인 시도 제한을 일반화한 것이다</h2>
 *
 * <p>{@code LoginAttemptService} 는 <b>한 계정</b>의 실패를 센다. 여기는 <b>한 주소</b>의
 * 요청 전부를 센다 — 계정을 바꿔 가며 두드리는 것은 그쪽이 못 막고, 이쪽이 막는다.
 *
 * <h2>인증 경로를 더 좁게 잡는다</h2>
 *
 * <p>로그인·가입·재설정은 <b>한 번이 비싼 자리</b>고 사람이 분당 스무 번 누를 일이 없다.
 * 나머지 API 는 목록을 넘기며 읽는 것이 정상이라 넉넉해야 한다 —
 * 좁게 잡으면 <b>정상 사용이 막히고, 그러면 값을 올리게 되고, 올린 값은 안 내려간다.</b>
 *
 * <h2>Redis 가 죽으면 통과시킨다</h2>
 *
 * <p>{@code LoginAttemptService} 와 반대 방향이다. 그쪽은 로컬 카운터로 <b>계속 막고</b>
 * 여기는 <b>통과시킨다</b> — 가르는 것은 「막는 것과 여는 것 중 무엇이 사고인가」다.
 * 로그인 잠금이 풀리면 무차별 대입이 열리지만, 요청 제한이 풀리면 그동안 좀 붐빌 뿐이다.
 * 반대로 여기서 막으면 <b>Redis 장애가 전면 장애가 된다.</b>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** 창 하나. 짧으면 순간 몰림을 못 막고, 길면 한 번 걸린 사람이 오래 기다린다 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** 로그인·가입·재설정. 사람이 분당 스무 번 누를 일이 없다 */
    private static final int AUTH_LIMIT = 20;

    /** 나머지 API. 목록을 넘기며 읽는 것이 정상이라 넉넉하다 */
    private static final int API_LIMIT = 120;

    private static final String KEY_PREFIX = "rate:";

    private final StringRedisTemplate redis;
    private final ProblemWriter problems;

    public RateLimitFilter(StringRedisTemplate redis, ProblemWriter problems) {
        this.redis = redis;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        int limit = limitFor(request.getRequestURI());
        String key = KEY_PREFIX + limit + ":" + request.getRemoteAddr();

        if (over(key, limit)) {
            // RFC 6585 가 429 를 정하고 RFC 9110 이 Retry-After 를 이 상태에 둔다.
            // 값이 없으면 받는 쪽이 언제 다시 걸지 몰라서 즉시 재시도한다.
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(WINDOW.toSeconds()));
            problems.write(request, response, ErrorCode.TOO_MANY_REQUESTS);
            return;
        }

        chain.doFilter(request, response);
    }

    private int limitFor(String path) {
        return path.startsWith("/api/auth/") ? AUTH_LIMIT : API_LIMIT;
    }

    /**
     * <b>만료는 첫 요청에만 건다.</b> 요청마다 다시 걸면 창이 계속 밀려서
     * 「1분에 120번」이 「마지막 요청으로부터 1분」이 된다({@code LoginAttemptService} 와 같은 함정).
     */
    private boolean over(String key, int limit) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW);
            }
            return count != null && count > limit;
        } catch (DataAccessException e) {
            return false;
        }
    }
}
