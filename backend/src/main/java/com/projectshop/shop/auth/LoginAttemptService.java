package com.projectshop.shop.auth;

import java.time.Duration;

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
 */
@Service
public class LoginAttemptService {

    /** 이 횟수를 넘기면 잠긴다(`D14`) */
    static final int MAX_ATTEMPTS = 5;

    /** 잠기는 시간. 지나면 저절로 풀린다(`D14`) */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final String KEY_PREFIX = "login:fail:";

    private final StringRedisTemplate redis;

    LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 지금 잠겨 있나 */
    public boolean isBlocked(String email, String ip) {
        String value = redis.opsForValue().get(key(email, ip));
        return value != null && Integer.parseInt(value) >= MAX_ATTEMPTS;
    }

    /**
     * 실패를 한 번 센다.
     *
     * <p><b>만료는 첫 실패에만 건다.</b> 실패마다 다시 걸면 계속 틀리는 동안 창이 밀려서
     * 15분이 "마지막 실패로부터 15분" 이 된다. `D14` 가 정한 것은 차단 시간이지 무활동 시간이 아니다.
     */
    public void recordFailure(String email, String ip) {
        String key = key(email, ip);
        Long count = redis.opsForValue().increment(key);

        if (count != null && count == 1L) {
            redis.expire(key, LOCK_DURATION);
        }
    }

    /** 성공하면 카운터를 지운다. 0 으로 되돌리는 것과 같고 키가 안 남는다 */
    public void reset(String email, String ip) {
        redis.delete(key(email, ip));
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
}
