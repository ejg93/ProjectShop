package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import com.projectshop.shop.PostgresTestBase;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 판정 캐시가 프로세스 밖에 있고, 그 밖이 죽어도 판정이 산다(`39`).
 *
 * <p><b>두 가지가 짝이다.</b> 캐시를 밖으로 내면 무효화가 모든 인스턴스에 퍼지는 대신
 * <b>캐시가 죽으면 판정이 죽는</b> 고장이 새로 생긴다. 앞엣것만 하고 뒤를 안 하면
 * 캐시를 「빠르게 하려고」 넣어 놓고 그것 때문에 서비스가 멈춘다.
 */
@DisplayName("판정 캐시")
class PermissionCacheTest extends PostgresTestBase {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Autowired
    private CacheErrorHandler cacheErrorHandler;

    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void captureLogs() {
        captured = new ListAppender<>();
        captured.start();
        cacheLogger().addAppender(captured);
    }

    @AfterEach
    void stopCapturing() {
        cacheLogger().detachAppender(captured);
        captured.stop();
    }

    /**
     * 캐시한 값이 <b>이 프로세스 밖</b>에 있나. 키가 Redis 에 보이면 다른 인스턴스도 같은 것을 읽고,
     * 한쪽이 지우면 모두에게 간다 — 그것이 이 이관의 전부다.
     */
    @Test
    @DisplayName("한 인스턴스가 캐시한 것을 다른 인스턴스가 읽고 무효화도 함께 받는다")
    void cacheIsSharedBetweenInstances() {
        long userId = 4242L;
        Cache here = cacheManager.getCache(PermissionCacheConfig.MEMBERSHIPS);
        Cache overThere = secondInstance().getCache(PermissionCacheConfig.MEMBERSHIPS);
        assertThat(here).isNotNull();
        assertThat(overThere).isNotNull();
        here.evict(userId);

        here.put(userId, List.of(1L, 2L));

        // **쓰기가 실패해도 강등 핸들러가 삼킨다.** 그러면 아래 단언이 「없다」로 빨개지는데
        // 진짜 원인(직렬화·연결)은 로그에만 있다. 여기서 먼저 물어야 그 줄이 실패 메시지에 뜬다.
        assertThat(warnings())
                .as("캐시 쓰기가 강등됐다. 아래 단언이 아니라 이 줄이 원인이다")
                .isEmpty();

        assertThat(overThere.get(userId))
                .as("프로세스 안에 두면 다른 인스턴스는 자기 것만 본다 — 같은 값을 두 번 읽는다")
                .isNotNull();

        here.evict(userId);

        assertThat(goneWithin(here, userId))
                .as("이쪽에서도 안 지워졌다. 강등 로그: " + warnings())
                .isTrue();
        assertThat(goneWithin(overThere, userId))
                .as("무효화가 저쪽까지 안 가면 역할을 회수해도 저쪽은 TTL 동안 계속 허용한다")
                .isTrue();
    }

    /**
     * 같은 Redis 를 보는 <b>두 번째 인스턴스</b>. 인스턴스를 늘렸을 때 무엇이 보이는지를
     * 한 JVM 에서 재는 방법이고, 바탕은 운영과 같은 빈({@link PermissionCacheConfig})이 만든다.
     */
    private CacheManager secondInstance() {
        return new PermissionCacheConfig().permissionCacheManager(connectionFactory);
    }

    /**
     * <b>부순 증거가 여기 붙는다.</b> 이 핸들러를 안 걸면 Spring 기본이 예외를 그대로 던져서
     * 캐시를 읽는 모든 요청이 500 이 된다.
     */
    @Test
    @DisplayName("캐시가 죽으면 예외를 삼키고 WARN 한 줄을 남긴다")
    void cacheFailureDegradesToDatabase() {
        Cache cache = cacheManager.getCache(PermissionCacheConfig.RULES);
        assertThat(cache).isNotNull();

        cacheErrorHandler.handleCacheGetError(
                new RedisConnectionFailureException("연결이 끊겼다"), cache, "9999");

        assertThat(captured.list)
                .as("삼키기만 하고 안 알리면 「느려졌다」는 민원만 오고 원인이 캐시라는 것을 아무도 모른다 (D16)")
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains(PermissionCacheConfig.RULES));

        assertThat(captured.list)
                .as("키에는 사용자 번호가 들어 있다. 로그에 식별자 말고 값을 적지 않는다 (D16)")
                .noneMatch(event -> event.getFormattedMessage().contains("9999"));
    }

    /**
     * 그 키가 사라졌나. <b>바로 안 사라질 수 있어서 잠깐 기다린다</b> — 무효화를 낸 직후에
     * 읽으면 아직 보이는 회차가 있었다(`39` 실측, 절반). 기다려도 남으면 그것은 진짜 실패다.
     */
    private static boolean goneWithin(Cache cache, Object key) {
        for (int attempt = 0; attempt < 40; attempt++) {
            if (cache.get(key) == null) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 이 회차에 남은 경고. 캐시가 조용히 강등되면 여기 뜬다 */
    private List<ILoggingEvent> warnings() {
        return captured.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private static Logger cacheLogger() {
        return (Logger) LoggerFactory.getLogger(PermissionCacheConfig.class);
    }
}
