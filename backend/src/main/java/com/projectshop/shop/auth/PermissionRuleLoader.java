package com.projectshop.shop.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.projectshop.shop.auth.PermissionEvaluator.Rule;

/**
 * 판정에 필요한 것을 DB 에서 읽어 온다. 캐시가 걸리는 자리다.
 *
 * <p>{@link PermissionEvaluator} 안에 두지 않은 이유가 있다.
 * {@code @Cacheable} 은 프록시가 가로채는 방식이라 <b>같은 객체 안에서 부르면 안 먹는다.</b>
 * 조회를 별도 빈으로 빼야 캐시가 실제로 걸린다.
 *
 * <p>가르고 나니 책임도 갈렸다. 이쪽은 규칙을 읽고, 판정기는 규칙으로 답을 낸다.
 */
@Component
class PermissionRuleLoader {

    private final JdbcClient jdbcClient;

    PermissionRuleLoader(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 이 사용자가 이 자원의 이 동작에 대해 가진 규칙 전부.
     *
     * <p>역할을 여럿 가졌으면 역할마다 한 줄씩 나온다. 필드 그룹은 한 줄로 모아서 온다.
     */
    @Cacheable(PermissionCacheConfig.RULES)
    List<Rule> loadRules(long userId, String resource, String action) {
        return jdbcClient.sql("""
                        select r.code as role_code, ur.seller_id as grant_seller_id, rp.scope, rp.effect,
                               coalesce(string_agg(g.code, ',' order by g.code), '') as field_groups
                        from user_role ur
                        join role r on r.id = ur.role_id
                        join role_permission rp on rp.role_id = ur.role_id
                        join permission p on p.id = rp.permission_id
                        left join role_permission_field rpf
                               on rpf.role_id = rp.role_id
                              and rpf.permission_id = rp.permission_id
                              and rpf.effect = rp.effect
                        left join permission_field_group g on g.id = rpf.field_group_id
                        where ur.user_id = :userId
                          and p.resource = :resource
                          and p.action = :action
                        group by r.code, ur.seller_id, rp.scope, rp.effect
                        """)
                .param("userId", userId)
                .param("resource", resource)
                .param("action", action)
                .query((rs, rowNum) -> new Rule(
                        rs.getString("role_code"),
                        rs.getObject("grant_seller_id", Long.class),
                        rs.getString("scope"),
                        rs.getString("effect"),
                        splitGroups(rs.getString("field_groups"))))
                .list();
    }

    /**
     * 이 사용자가 가진 규칙 전부를 자원·동작으로 묶어서.
     *
     * <p>{@link #loadRules} 를 권한 수만큼 부르지 않으려고 따로 둔다.
     * 목록은 무엇이 있는지 모르는 채로 시작하므로 하나씩 물어볼 대상이 없다.
     *
     * <p><b>캐시를 안 붙였다.</b> 붙이면 키가 사용자 하나뿐이라 지우기는 쉽지만,
     * 같은 규칙이 두 캐시에 형태만 달리해서 들어간다. 한쪽만 비는 날이 생긴다.
     * 이 조회는 화면 진입마다 한 번이라 그 위험을 살 이유가 없다.
     */
    Map<ResourceAction, List<Rule>> loadAllRules(long userId) {
        return jdbcClient.sql("""
                        select p.resource, p.action,
                               r.code as role_code, ur.seller_id as grant_seller_id, rp.scope, rp.effect,
                               coalesce(string_agg(g.code, ',' order by g.code), '') as field_groups
                        from user_role ur
                        join role r on r.id = ur.role_id
                        join role_permission rp on rp.role_id = ur.role_id
                        join permission p on p.id = rp.permission_id
                        left join role_permission_field rpf
                               on rpf.role_id = rp.role_id
                              and rpf.permission_id = rp.permission_id
                              and rpf.effect = rp.effect
                        left join permission_field_group g on g.id = rpf.field_group_id
                        where ur.user_id = :userId
                        group by p.resource, p.action, r.code, ur.seller_id, rp.scope, rp.effect
                        order by p.resource, p.action
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> Map.entry(
                        new ResourceAction(rs.getString("resource"), rs.getString("action")),
                        new Rule(
                                rs.getString("role_code"),
                                rs.getObject("grant_seller_id", Long.class),
                                rs.getString("scope"),
                                rs.getString("effect"),
                                splitGroups(rs.getString("field_groups")))))
                .list()
                .stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey, LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
    }

    /** 권한 하나를 가리키는 키 */
    record ResourceAction(String resource, String action) {
    }

    /** 이 사용자가 속한 셀러. 전역으로 받은 {@code seller} 스코프가 어디까지 미치는지를 정한다 */
    @Cacheable(PermissionCacheConfig.MEMBERSHIPS)
    Set<Long> loadSellerMemberships(long userId) {
        return Set.copyOf(jdbcClient.sql("select seller_id from seller_member where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .list());
    }

    /**
     * 이 사용자의 캐시를 버린다. 역할을 주거나 회수하거나 소속이 바뀌면 부른다.
     *
     * <p><b>규칙 캐시는 그 사용자 것만 못 지우고 통째로 비운다.</b>
     * 키가 (사용자, 자원, 동작)이라 한 사용자의 항목이 권한 수만큼 흩어져 있는데,
     * Caffeine 에 키 패턴으로 지우는 기능이 없다.
     *
     * <p>남는 방법은 둘이었다. 사용자별 키 목록을 따로 들고 다니거나, 통째로 비우거나.
     * 앞은 목록과 캐시가 어긋나면 안 지워진 항목이 남는데 그게 권한에서는 사고다.
     * 뒤는 다른 사용자가 다음 조회에서 한 번 더 DB 를 읽을 뿐이다. 틀리는 쪽보다 낫다.
     *
     * <p>소속 캐시는 키가 사용자 하나라 정확히 지운다.
     */
    @Caching(evict = {
            @CacheEvict(value = PermissionCacheConfig.RULES, allEntries = true),
            @CacheEvict(value = PermissionCacheConfig.MEMBERSHIPS, key = "#userId")
    })
    void evict(long userId) {
        // 애너테이션이 일한다. 부르는 쪽에 무엇을 지우는지 이름으로 드러내려고 메서드를 둔다.
    }

    private static Set<String> splitGroups(String joined) {
        if (joined == null || joined.isEmpty()) {
            return Set.of();
        }
        return Set.of(joined.split(","));
    }
}
