package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;
import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Target;

/**
 * 캐시가 판정을 바꾸지 않는지, 그리고 회수가 즉시 먹는지 본다.
 *
 * <p>캐시의 위험은 빨라지는 쪽이 아니라 <b>틀린 답이 남는 쪽</b>이다.
 * 권한을 회수했는데 캐시가 옛 규칙을 들고 있으면 회수가 안 먹는다.
 *
 * <p>테스트마다 캐시를 비우고 시작한다. 안 그러면 앞 테스트가 채운 값이 뒤 테스트의 답이 된다.
 */
class PermissionCacheTest extends PostgresTestBase {

    @Autowired
    PermissionEvaluator evaluator;

    @Autowired
    PermissionRuleLoader loader;

    @Autowired
    CacheManager cacheManager;

    @Autowired
    JdbcClient jdbc;

    AuthFixture fixture;
    long alpha;
    long seller;

    @BeforeEach
    void setUp() {
        PermissionCacheConfig.cacheNames()
                .forEach(name -> cacheManager.getCache(name).clear());

        fixture = new AuthFixture(jdbc);
        alpha = fixture.insertSeller("alpha", "알파상회");
        seller = fixture.insertUser("alpha-seller@test.local", "알파 대표");
        fixture.joinSeller(alpha, seller);
        fixture.grantOrg(seller, "seller_owner", alpha);
    }

    @Nested
    @DisplayName("캐시가 채워지나")
    class Filling {

        @Test
        @DisplayName("같은 조회를 두 번 하면 두 번째는 캐시에서 온다")
        void secondCallHitsCache() {
            loader.loadRules(seller, "order", "read");

            assertThat(cachedRules(seller, "order", "read"))
                    .as("첫 조회 뒤 규칙 캐시에 항목이 있어야 한다")
                    .isNotNull();
        }

        @Test
        @DisplayName("소속 캐시는 사용자 하나를 키로 쓴다")
        void membershipCacheIsKeyedByUser() {
            loader.loadSellerMemberships(seller);

            assertThat(cached(PermissionCacheConfig.MEMBERSHIPS, seller))
                    .isEqualTo(Set.of(alpha));
        }

        @Test
        @DisplayName("판정 결과는 캐시하지 않는다")
        void decisionIsNotCached() {
            evaluator.decide(seller, "order", "read", Target.ofSeller(alpha));

            assertThat(PermissionCacheConfig.cacheNames())
                    .as("캐시는 규칙과 소속 둘뿐이다. 대상 행이 키에 들어가는 캐시를 만들지 않는다")
                    .containsExactly(PermissionCacheConfig.RULES, PermissionCacheConfig.MEMBERSHIPS);
        }
    }

    @Nested
    @DisplayName("회수가 즉시 먹나")
    class Revocation {

        @Test
        @DisplayName("역할을 회수하고 무효화하면 그 다음 판정이 거부한다")
        void revokedRoleIsDeniedAfterEvict() {
            assertThat(decide().allowed())
                    .as("회수 전에는 허용이어야 뒤의 검증이 뜻을 갖는다")
                    .isTrue();

            fixture.revokeAllRoles(seller);
            loader.evict(seller);

            assertThat(decide().allowed()).isFalse();
        }

        @Test
        @DisplayName("무효화를 안 하면 회수해도 옛 판정이 남는다")
        void staleDecisionSurvivesWithoutEvict() {
            decide();
            fixture.revokeAllRoles(seller);

            assertThat(decide().allowed())
                    .as("""
                            캐시의 실패 모드를 고정한다. 무효화를 부르지 않으면 회수가 안 먹는다.
                            그래서 역할을 건드리는 곳은 반드시 evict 를 부르고(청크 16),
                            빠뜨려도 TTL 이 지나면 맞아진다.
                            """)
                    .isTrue();
        }

        @Test
        @DisplayName("소속이 끊기고 무효화하면 소속 조회가 비어서 돌아온다")
        void membershipChangeTakesEffectAfterEvict() {
            assertThat(loader.loadSellerMemberships(seller)).containsExactly(alpha);

            fixture.leaveSeller(alpha, seller);

            assertThat(loader.loadSellerMemberships(seller))
                    .as("무효화 전에는 캐시가 옛 소속을 들고 있다")
                    .containsExactly(alpha);

            loader.evict(seller);

            assertThat(loader.loadSellerMemberships(seller)).isEmpty();
        }

        @Test
        @DisplayName("한 사용자를 무효화하면 다른 사용자의 규칙 캐시도 같이 비워진다")
        void evictClearsRuleCacheForEveryone() {
            long other = fixture.insertUser("other@test.local", "다른 사람");
            fixture.grantGlobal(other, "customer");

            loader.loadRules(seller, "order", "read");
            loader.loadRules(other, "order", "read");

            loader.evict(seller);

            assertThat(cachedRules(other, "order", "read"))
                    .as("""
                            Caffeine 에 키 패턴 삭제가 없어서 규칙 캐시는 통째로 비운다.
                            다른 사용자가 다음 조회에서 DB 를 한 번 더 읽을 뿐이라 틀리는 쪽보다 낫다.
                            """)
                    .isNull();
            assertThat(cached(PermissionCacheConfig.MEMBERSHIPS, other))
                    .as("소속 캐시는 키가 사용자 하나라 남의 것을 안 건드린다")
                    .isNull();
        }
    }

    private Decision decide() {
        return evaluator.decide(seller, "order", "read", Target.ofSeller(alpha));
    }

    /** 규칙 캐시의 키는 인자 셋을 묶은 것이다. 캐시에 실제로 그 키가 있는지만 본다 */
    private Object cachedRules(long userId, String resource, String action) {
        return cached(PermissionCacheConfig.RULES,
                new org.springframework.cache.interceptor.SimpleKey(userId, resource, action));
    }

    private Object cached(String cacheName, Object key) {
        var wrapper = cacheManager.getCache(cacheName).get(key);
        return wrapper == null ? null : wrapper.get();
    }
}
