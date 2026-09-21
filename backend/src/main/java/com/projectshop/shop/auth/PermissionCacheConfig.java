package com.projectshop.shop.auth;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 판정에 쓰이는 조회 결과를 캐시한다.
 *
 * <p>캐시하는 것은 <b>"이 사용자가 어떤 규칙을 가졌나" 뿐</b>이고 판정 결과는 아니다.
 * 판정 결과를 캐시하면 키에 대상 행이 들어가서 행마다 키가 하나씩 생긴다.
 * 같은 행을 다시 볼 일이 드물어 히트는 안 나고 메모리만 먹는다.
 * 상태가 권한을 깎는 축(청크 11a)이 붙으면 상태 변화에 캐시를 지울 신호가 없어 조용히 틀린다.
 *
 * <p>규칙은 역할이 바뀔 때만 바뀌므로 무효화 지점이 하나로 모인다.
 *
 * <h2>왜 프로세스 밖으로 옮겼나(`39`)</h2>
 *
 * <p>전에는 Caffeine 이라 <b>캐시가 인스턴스마다 하나씩</b>이었다. 그러면 역할을 회수해도
 * 그 요청을 받은 인스턴스만 지우고 <b>다른 인스턴스는 TTL 이 끝날 때까지 계속 허용한다.</b>
 * 한 대로 돌 때는 안 보이다가 <b>늘리는 순간</b> 조용히 틀리는 자리라, 늘리기 전에 옮긴다.
 *
 * <p>Redis 로 옮기면 무효화가 한 번으로 모두에게 간다. 대신 <b>캐시가 죽으면 판정이 죽는</b>
 * 새 고장이 생기는데, 그것은 {@link #errorHandler} 가 막는다 — 캐시를 못 읽으면
 * 그 요청은 DB 로 내려가고 {@code WARN} 한 줄이 남는다(`D16`).
 */
@Configuration
@EnableCaching
class PermissionCacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PermissionCacheConfig.class);

    /** 사용자별 권한 규칙. 키는 (사용자, 자원, 동작) */
    static final String RULES = "permissionRules";

    /** 사용자별 셀러 소속. 키는 사용자 */
    static final String MEMBERSHIPS = "sellerMemberships";

    /**
     * 계정이 살아 있나. 키는 사용자.
     *
     * <p>요청마다 보는 값이라 캐시가 없으면 모든 요청에 조회가 하나 붙는다.
     * 대가는 TTL 동안 탈퇴가 안 먹는 것인데, 역할 회수와 같은 성질이라 해법도 같다 — 지우면 된다.
     */
    static final String LIVENESS = "accountLiveness";

    /**
     * 무효화를 빼먹어도 결국 맞아지게 하는 안전망이다.
     *
     * <p>역할을 부여·회수하는 화면은 청크 16 에 가서야 생긴다. 그때 무효화 호출을 빠뜨리면
     * 틀린 판정이 재기동할 때까지 남는데, 권한에서 그건 사고다. 만료가 그 창을 이만큼으로 자른다.
     *
     * <p><b>Redis 로 옮긴 뒤에도 그대로 둔다.</b> 무효화가 퍼지는 것과 무효화를 빠뜨리는 것은
     * 다른 사고고, 이 값이 막는 것은 뒤쪽이다.
     */
    static final Duration TTL = Duration.ofSeconds(60);

    /**
     * 키 앞에 붙는 이름. <b>세션과 같은 Redis 를 쓴다</b>(`38`) — 접두어가 없으면
     * 무엇이 캐시고 무엇이 세션인지 키만 보고 못 가른다.
     */
    static final String KEY_PREFIX = "permission:";

    @Bean
    CacheManager permissionCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .prefixCacheNameWith(KEY_PREFIX)
                // **`null` 을 안 싣는다.** 판정 입력에 `null` 이 캐시되면 그 값이 무엇을 뜻하는지
                // (조회가 없었나, 없다고 나왔나) 읽는 쪽에서 안 갈린다.
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericJackson2JsonRedisSerializer(cacheObjectMapper())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .initialCacheNames(java.util.Set.copyOf(cacheNames()))
                .build();
    }

    /**
     * 캐시에 실을 값을 JSON 으로 굳힌다.
     *
     * <p><b>타입 정보를 같이 싣는다.</b> 기본 설정은 `List<Long>` 을 그냥 배열로 적고,
     * 되읽을 때 「무엇의 배열인지」를 몰라서 {@code SerializationException} 을 낸다 —
     * 그러면 <b>캐시가 매번 읽기에 실패하고 강등 로그만 쌓인다</b>(`39` 에서 실측했다).
     *
     * <p><b>아무 타입이나 되살리지 않는다</b>(`D14`). 검증자를 안 좁히면 Redis 에 쓸 수 있는 쪽이
     * 클래스 이름을 골라 역직렬화를 시킬 수 있다 — 캐시 값에 들어올 것은 우리 타입과
     * {@code java.util} 의 그릇뿐이라 거기까지만 연다.
     */
    private static ObjectMapper cacheObjectMapper() {
        return JsonMapper.builder()
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfSubType("com.projectshop.shop.")
                                .allowIfSubType("java.util.")
                                .allowIfSubType("java.lang.")
                                .build(),
                        ObjectMapper.DefaultTyping.EVERYTHING,
                        JsonTypeInfo.As.WRAPPER_ARRAY)
                .build();
    }

    /**
     * 캐시가 죽어도 판정은 산다(`39`, `5c-2` 가 남긴 자리).
     *
     * <p><b>기본 동작은 예외를 그대로 던지는 것</b>이라, Redis 가 죽으면 캐시를 읽는 모든 요청이
     * 500 이 된다 — 캐시는 <b>빠르게 하려고</b> 넣은 것인데 그것 때문에 서비스가 멈춘다.
     * 여기서 삼키면 그 요청은 DB 로 내려가서 <b>느리지만 옳은 답</b>을 낸다.
     *
     * <p><b>조용히 삼키지 않는다</b>(`D16`). 강등은 눈에 보여야 한다 — 안 보이면
     * 「느려졌다」는 민원만 오고 원인이 캐시라는 것을 아무도 모른다. 그래서 캐시 이름과
     * 예외를 한 줄로 남긴다. <b>키는 안 적는다</b> — 사용자 번호가 들어 있다.
     */
    @Override
    @Bean
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {

            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                degraded("읽기", cache, exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
                degraded("쓰기", cache, exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                degraded("무효화", cache, exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                degraded("비우기", cache, exception);
            }
        };
    }

    /**
     * <b>무효화 실패는 읽기 실패와 무게가 다르다.</b> 읽기가 실패하면 그 요청만 느려지는데,
     * 무효화가 실패하면 <b>지웠어야 할 값이 TTL 만큼 남는다</b> — 권한에서는 그것이 사고다.
     * 그래도 여기서 예외를 다시 던지지 않는다. 던지면 역할을 바꾸는 요청 자체가 실패해서
     * <b>DB 는 바뀌었는데 응답은 오류</b>인 상태가 되고, 부르는 쪽이 다시 시도하면 같은 자리에서 또 막힌다.
     */
    private static void degraded(String what, Cache cache, RuntimeException exception) {
        log.warn("판정 캐시 {} 가 실패해서 DB 로 내려간다. cache={} 최대 {}초 동안 옛 값이 남을 수 있다",
                what, cache.getName(), TTL.toSeconds(), exception);
    }

    /**
     * 캐시 이름 목록. <b>무효화는 이 목록을 안 쓴다</b> — {@link PermissionRuleLoader#evict} 가
     * 캐시마다 {@code @CacheEvict} 를 하나씩 직접 적는다. 지우는 방식이 캐시마다 달라서
     * (규칙은 통째로, 나머지는 키 하나) 목록으로 돌 수가 없다.
     *
     * <p>쓰는 곳은 <b>테스트와 위 캐시 매니저</b>다. "캐시가 지금 몇 개인가" 를 고정해 둬서,
     * 캐시를 새로 추가하면 그 단언이 깨져 무효화를 같이 봤는지 묻게 만든다.
     */
    static List<String> cacheNames() {
        return List.of(RULES, MEMBERSHIPS, LIVENESS);
    }
}
