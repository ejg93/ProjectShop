package com.projectshop.shop.support;

import java.io.IOException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <h2>시험에서는 꺼 둔다</h2>
 *
 * <p>{@code shop.rate-limit.enabled} 가 가른다. 로그인 시험 하나가 <b>실패 다섯 번</b>을
 * 보내는 식이라, 켜 둔 채로 느린 레인을 돌리면 <b>401 을 기대한 자리에 429 가 온다</b> —
 * 실제로 {@code AuthLoginTest} 가 그렇게 깨졌다(마무리 26차).
 *
 * <p><b>제한 자체는 제 시험이 잰다</b>({@code RateLimitFilterTest}). 그 하나만 켜면 되고,
 * 나머지 구백 몇 개는 이 필터를 재는 시험이 아니다.
 *
 * <p>{@code LoginAttemptService} 와 반대 방향이다. 그쪽은 로컬 카운터로 <b>계속 막고</b>
 * 여기는 <b>통과시킨다</b> — 가르는 것은 「막는 것과 여는 것 중 무엇이 사고인가」다.
 * 로그인 잠금이 풀리면 무차별 대입이 열리지만, 요청 제한이 풀리면 그동안 좀 붐빌 뿐이다.
 * 반대로 여기서 막으면 <b>Redis 장애가 전면 장애가 된다.</b>
 *
 * <h2>첫 거부를 한 줄 남긴다</h2>
 *
 * <p>운영에서 손님 전원이 한 열쇠로 세였는데(`Q251`, 2026-10-03 휴대폰 확인) <b>무엇을 열쇠로 썼는지 남은 것이 없어</b>
 * 원인을 못 가렸다. 그래서 열쇠마다 창 하나에 <b>첫 거부만</b> 남긴다 — 공격이 와도 열쇠당 분당 한 줄이다.
 * <b>주소는 안 찍는다</b>(`D16` 「식별자만 찍는다」) — 손님 헤더가 믿겼나와 열쇠 주소의 대역만 찍는다.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 창 하나. 짧으면 순간 몰림을 못 막고, 길면 한 번 걸린 사람이 오래 기다린다 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** 로그인·가입·재설정. 사람이 분당 스무 번 누를 일이 없다 */
    private static final int AUTH_LIMIT = 20;

    /** 나머지 API. 목록을 넘기며 읽는 것이 정상이라 넉넉하다 */
    private static final int API_LIMIT = 120;

    /**
     * 열쇠 앞에 붙는다. <b>Redis 하나를 나눠 쓰는 것들과 안 섞이게</b> 한다 —
     * 세션이 {@code shop:session} 을 쓰는 것과 같은 자리다.
     *
     * <p><b>설정으로 뺀 이유는 배포다</b>({@code Q104} 정정). 처음에는 시험 때문이라고 적었는데
     * 전제가 틀렸다 — {@code PostgresTestBase.forkRedis} 가 이미 fork 마다 논리 DB 를 가르므로
     * ({@code 2i-2}) 시험은 이 값을 안 건드린다. 남긴 것은 <b>관리형 Redis 를 남과 나눠 쓰는 날</b>
     * 을 위해서고, 그날은 이 한 줄이 열쇠 공간을 가른다.
     */
    private final String keyPrefix;

    /**
     * 손님 주소 헤더의 이름. <b>톰캣 설정({@code server.tomcat.remoteip.remote-ip-header})에서 받는다</b> —
     * `RemoteIpValve` 가 실제로 읽는 헤더를 그대로 봐야 진단이 맞고, 받는 헤더 이름을 main 에 글자로 안 쓴다({@code SourceTextTest}).
     */
    private final String clientIpHeader;

    private final StringRedisTemplate redis;
    private final ProblemWriter problems;

    public RateLimitFilter(StringRedisTemplate redis, ProblemWriter problems, String keyPrefix, String clientIpHeader) {
        this.redis = redis;
        this.problems = problems;
        this.keyPrefix = keyPrefix;
        this.clientIpHeader = clientIpHeader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        int limit = limitFor(request.getRequestURI());
        String key = keyPrefix + limit + ":" + request.getRemoteAddr();

        long count = count(key);
        if (count > limit) {
            if (count == limit + 1L) {
                log.info("요청 제한 첫 거부 limit={} client_header={} key_class={}", limit,
                        headerState(request.getHeader(clientIpHeader), request.getRemoteAddr()),
                        addressClass(request.getRemoteAddr()));
            }
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
    private long count(String key) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW);
            }
            return count == null ? 0L : count;
        } catch (DataAccessException e) {
            return 0L;
        }
    }

    /**
     * 손님 주소 헤더가 어떻게 됐나. 믿을 상대에게서 오면 `RemoteIpValve` 가 주소를 그 값으로 바꾸므로 둘이 같다.
     *
     * @return {@code absent}(헤더가 없다) · {@code trusted}(주소가 헤더 값이다) · {@code ignored}(헤더가 있는데 안 믿었다 —
     *         보낸 상대가 {@code TRUSTED_PROXIES} 밖이다)
     */
    static String headerState(String realIpHeader, String remoteAddr) {
        if (realIpHeader == null || realIpHeader.isBlank()) {
            return "absent";
        }
        return realIpHeader.strip().equalsIgnoreCase(remoteAddr) ? "trusted" : "ignored";
    }

    /** 열쇠 주소의 대역. <b>주소 자체는 안 낸다</b>(`D16`) — 어느 대역이 열쇠가 됐는지만 가린다 */
    static String addressClass(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        String a = address.toLowerCase(java.util.Locale.ROOT);
        if (a.contains(":")) {
            if (a.equals("::1") || a.equals("0:0:0:0:0:0:0:1")) {
                return "loopback";
            }
            if (a.startsWith("fc") || a.startsWith("fd")) {
                return "ipv6-ula";
            }
            if (a.startsWith("fe8") || a.startsWith("fe9") || a.startsWith("fea") || a.startsWith("feb")) {
                return "ipv6-link-local";
            }
            return "ipv6-public";
        }
        String[] parts = a.split("\\.");
        if (parts.length != 4) {
            return "unknown";
        }
        int first;
        int second;
        try {
            first = Integer.parseInt(parts[0]);
            second = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return "unknown";
        }
        if (first == 127) {
            return "loopback";
        }
        if (first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168)) {
            return "ipv4-private";
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return "ipv4-shared";
        }
        if (first == 169 && second == 254) {
            return "ipv4-link-local";
        }
        return "ipv4-public";
    }
}
