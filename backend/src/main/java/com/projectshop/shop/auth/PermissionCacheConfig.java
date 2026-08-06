package com.projectshop.shop.auth;

import java.time.Duration;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 판정에 쓰이는 조회 결과를 캐시한다.
 *
 * <p>캐시하는 것은 <b>"이 사용자가 어떤 규칙을 가졌나" 뿐</b>이고 판정 결과는 아니다.
 * 판정 결과를 캐시하면 키에 대상 행이 들어가서 행마다 키가 하나씩 생긴다.
 * 같은 행을 다시 볼 일이 드물어 히트는 안 나고 메모리만 먹는다.
 * 상태가 권한을 깎는 축(청크 11a)이 붙으면 상태 변화에 캐시를 지울 신호가 없어 조용히 틀린다.
 *
 * <p>규칙은 역할이 바뀔 때만 바뀌므로 무효화 지점이 하나로 모인다.
 */
@Configuration
@EnableCaching
class PermissionCacheConfig {

    /** 사용자별 권한 규칙. 키는 (사용자, 자원, 동작) */
    static final String RULES = "permissionRules";

    /** 사용자별 셀러 소속. 키는 사용자 */
    static final String MEMBERSHIPS = "sellerMemberships";

    /**
     * 무효화를 빼먹어도 결국 맞아지게 하는 안전망이다.
     *
     * <p>역할을 부여·회수하는 화면은 청크 16 에 가서야 생긴다. 그때 무효화 호출을 빠뜨리면
     * 틀린 판정이 재기동할 때까지 남는데, 권한에서 그건 사고다. 만료가 그 창을 이만큼으로 자른다.
     */
    static final Duration TTL = Duration.ofSeconds(60);

    /** 사용자 수만큼 늘어나는 캐시라 상한을 둔다. 넘으면 안 쓰인 것부터 밀려난다 */
    static final long MAX_SIZE = 10_000;

    @Bean
    CacheManager permissionCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(RULES, MEMBERSHIPS);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(TTL)
                .maximumSize(MAX_SIZE));
        return manager;
    }

    /**
     * 캐시 이름 목록. 무효화 쪽이 이 목록을 쓴다.
     * 캐시를 새로 추가하고 여기 안 넣으면 무효화가 그 캐시를 안 지운다.
     */
    static List<String> cacheNames() {
        return List.of(RULES, MEMBERSHIPS);
    }
}
