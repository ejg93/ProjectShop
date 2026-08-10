package com.projectshop.shop.auth;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 로그인 실패를 세고 한도를 넘으면 잠근다(`D14`).
 *
 * <p><b>세는 단위가 (계정, IP) 조합이다.</b> 계정만 보고 잠그면 남의 계정에 일부러 틀려서
 * 잠그는 공격이 열린다. IP 를 같이 묶으면 공격자가 자기 IP 만 막게 된다.
 *
 * <p><b>여러 IP 로 나눠 던지는 공격은 이 방식으로 못 막는다.</b> 캡차(청크 5d)와
 * 요청 제한(청크 71)이 그 자리다.
 *
 * <p>관리자 해제 화면이 없다. 시간이 지나면 풀리므로 해제할 것이 없다.
 *
 * <h2>왜 Redis 인가</h2>
 *
 * 카운터 하나로 완결되는 값이라 <b>다른 데이터와 진짜로 관계가 없다.</b>
 * 날아가면 차단이 풀릴 뿐이고 공격자는 5회를 더 시도한다 — 손해가 작다.
 * 멱등키(청크 10-0)를 Postgres 에 둔 것과 정반대 이유다. 그쪽은 유실이 곧 중복 주문이다.
 *
 * <p>DB 로 하면 실패마다 {@code update} 가 나가고 15분 지난 행을 지우는 배치가 또 붙는다.
 * 여기서는 {@code INCR} 과 {@code EXPIRE} 두 번으로 끝나고 만료를 Redis 가 알아서 한다.
 *
 * <h2>Redis 가 죽으면 프로세스 로컬 카운터로 강등한다</h2>
 *
 * 예외를 안 잡으면 <b>차단 카운터를 못 읽는 것이 로그인 실패로 번진다</b> — Redis 장애가
 * 로그인 전면 정지가 된다. 그렇다고 그냥 통과시키면 장애 동안 남는 방어가 비밀번호 해시뿐이고,
 * 「개인정보의 안전성 확보조치 기준」이 요구하는 <b>인증 실패 횟수 제한이 그 시간 동안 사라진다.</b>
 *
 * <p>그래서 로그인은 계속 받되 세는 자리를 {@link LocalCounter} 로 옮긴다(`D14`).
 * 이 앱은 단일 인스턴스라 로컬 카운터가 Redis 와 사실상 같은 범위를 덮는다.
 * 잃는 것은 재시작 시 카운터 소실과 장애가 끝날 때 카운터가 갈아엎히는 것 둘이고,
 * 둘 다 공격자가 5회를 더 얻는 정도다 — Redis 를 고른 이유와 같은 크기의 손해다.
 *
 * <p>강등을 로그로 남기는 것은 청크 2b 뒤다. 형식이 정해지기 전에 찍으면 그 줄이 관례가 된다.
 */
@Service
public class LoginAttemptService {

    /** 이 횟수를 넘기면 잠긴다(`D14`) */
    static final int MAX_ATTEMPTS = 5;

    /** 잠기는 시간. 지나면 저절로 풀린다(`D14`) */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redis;

    /** Redis 가 죽어 있는 동안만 쓴다 */
    private final LocalCounter localCounter = new LocalCounter();

    LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 지금 잠겨 있나.
     *
     * <p>Redis 가 살아 있으면 로컬 카운터를 안 본다. 앞선 장애 때 쌓인 값이 남아 있어도
     * 15분이면 스스로 만료된다 — 둘을 합치면 복구 뒤에 정상 사용자가 옛 실패로 잠긴다.
     */
    public boolean isBlocked(String email, String ip) {
        String key = key(email, ip);
        try {
            String value = redis.opsForValue().get(key);
            return value != null && Integer.parseInt(value) >= MAX_ATTEMPTS;
        } catch (DataAccessException e) {
            return localCounter.count(key) >= MAX_ATTEMPTS;
        }
    }

    /**
     * 실패를 한 번 센다.
     *
     * <p><b>만료는 첫 실패에만 건다.</b> 실패마다 다시 걸면 계속 틀리는 동안 창이 밀려서
     * 15분이 "마지막 실패로부터 15분" 이 된다. `D14` 가 정한 것은 차단 시간이지 무활동 시간이 아니다.
     */
    public void recordFailure(String email, String ip) {
        String key = key(email, ip);
        try {
            Long count = redis.opsForValue().increment(key);

            if (count != null && count == 1L) {
                redis.expire(key, LOCK_DURATION);
            }
        } catch (DataAccessException e) {
            localCounter.increment(key);
        }
    }

    /**
     * 성공하면 카운터를 지운다. 0 으로 되돌리는 것과 같고 키가 안 남는다.
     *
     * <p>로컬 카운터는 Redis 가 살아 있어도 같이 지운다. 장애 중에 쌓인 값을 남겨 두면
     * 다음 장애 때 그 사용자가 첫 실패에서 바로 잠긴다.
     */
    public void reset(String email, String ip) {
        String key = key(email, ip);
        localCounter.remove(key);
        try {
            redis.delete(key);
        } catch (DataAccessException e) {
            // 지울 것이 Redis 에 없거나 Redis 가 없다. 어느 쪽이든 15분 뒤 사라진다.
        }
    }

    /**
     * 이메일을 그대로 키에 넣는다.
     *
     * <p>개인정보지만 <b>식별자로만 쓰이고 15분 뒤 사라진다.</b> 해시하면 운영 중에
     * "이 계정이 왜 잠겼나" 를 못 본다. 로그에는 안 찍는다(`D16` — 개인정보는 식별자만).
     */
    private static String key(String email, String ip) {
        return KEY_PREFIX + email + ":" + ip;
    }

    /**
     * Redis 자리를 대신 세는 프로세스 로컬 카운터.
     *
     * <p>Redis 의 {@code INCR} + {@code EXPIRE} 를 창(count, 만료 시각) 하나로 흉내 낸다.
     * 만료를 대신 걸어 줄 것이 없으므로 <b>읽을 때 만료를 판정</b>하고 지우지 않는다 —
     * 만료된 창은 다음 실패가 새 창으로 갈아치운다.
     */
    private static final class LocalCounter {

        /**
         * 이 수를 넘으면 만료된 창을 걷어낸다.
         *
         * <p>키가 (계정, IP) 조합이라 공격자가 무한히 만들 수 있다. Redis 라면 서버가 만료로
         * 거두지만 여기서는 아무도 안 거둔다 — 상한이 없으면 장애가 길어질수록 힙이 찬다.
         */
        private static final int PRUNE_THRESHOLD = 10_000;

        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

        int count(String key) {
            Window window = windows.get(key);
            long now = System.currentTimeMillis();

            return window == null || window.expired(now) ? 0 : window.count();
        }

        void increment(String key) {
            long now = System.currentTimeMillis();

            if (windows.size() >= PRUNE_THRESHOLD) {
                windows.values().removeIf(window -> window.expired(now));
            }

            windows.compute(key, (ignored, window) -> window == null || window.expired(now)
                    ? new Window(1, now + LOCK_DURATION.toMillis())
                    : new Window(window.count() + 1, window.expiresAtMillis()));
        }

        void remove(String key) {
            windows.remove(key);
        }

        /** 만료 시각을 창이 직접 들고 있다. 첫 실패가 정하고 뒤따르는 실패가 안 밀어낸다 */
        private record Window(int count, long expiresAtMillis) {

            boolean expired(long nowMillis) {
                return nowMillis >= expiresAtMillis;
            }
        }
    }
}
