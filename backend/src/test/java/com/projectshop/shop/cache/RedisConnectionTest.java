package com.projectshop.shop.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.projectshop.shop.PostgresTestBase;

/**
 * Redis 가 실제로 붙어 있나.
 *
 * <p>컨테이너를 띄우는 청크(38)의 산출물이 <b>설정뿐</b>이라, 이 테스트가 없으면
 * "붙였다" 를 확인할 방법이 없다. 쓰는 코드는 청크 5c(로그인 실패 카운터)와
 * 39(판정 캐시 이관)가 만든다.
 */
@DisplayName("Redis 연결")
class RedisConnectionTest extends PostgresTestBase {

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private CacheManager cacheManager;

    @Test
    @DisplayName("값을 넣고 읽는다")
    void storesAndReads() {
        redis.opsForValue().set("shop:test:key", "value", Duration.ofSeconds(10));

        assertThat(redis.opsForValue().get("shop:test:key")).isEqualTo("value");
    }

    @Test
    @DisplayName("TTL 이 걸린다")
    void expires() {
        redis.opsForValue().set("shop:test:ttl", "value", Duration.ofSeconds(30));

        assertThat(redis.getExpire("shop:test:ttl"))
                .as("만료가 안 걸리면 5c 의 15분 차단이 영구 차단이 된다")
                .isPositive();
    }

    @Test
    @DisplayName("INCR 로 카운터가 는다")
    void counts() {
        redis.delete("shop:test:counter");

        assertThat(redis.opsForValue().increment("shop:test:counter")).isEqualTo(1);
        assertThat(redis.opsForValue().increment("shop:test:counter")).isEqualTo(2);
    }

    @Test
    @DisplayName("판정 캐시가 Redis 에 있다")
    void permissionCacheLivesInRedis() {
        assertThat(cacheManager.getClass().getSimpleName())
                .as("프로세스 안에 두면 역할을 회수해도 그 요청을 받은 인스턴스만 지운다 — "
                        + "다른 인스턴스는 TTL 이 끝날 때까지 계속 허용한다(청크 39). "
                        + "한 대로 돌 때는 안 보이다가 늘리는 순간 조용히 틀리는 자리다")
                .isEqualTo("RedisCacheManager");
    }
}
