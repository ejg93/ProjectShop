package com.projectshop.shop.auth;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 한 사용자가 특정 자원에 특정 동작을 할 수 있는지를 한 군데서 판정한다.
 * 서비스 코드에 {@code if (order.sellerId != me.id)} 가 흩어지는 것을 막으려고 만든다.
 *
 * <p>판정에 쓰이는 축이 넷이다.
 * <ul>
 *   <li>권한 — {@code resource:action} 이 역할에 달려 있나</li>
 *   <li>스코프 — 그 권한이 어느 범위의 행에 미치나 (own, seller, all)</li>
 *   <li>조직 — 역할이 특정 셀러에 묶여 부여됐나</li>
 *   <li>효과 — allow 인가 deny 인가</li>
 * </ul>
 *
 * <p>자원의 상태가 권한을 깎는 축(청크 11a)과 응답 필드를 깎는 축(청크 4d)은 여기 없다.
 * 목록 조회에 스코프를 섞는 일(청크 8)도 여기가 아니다. 이 클래스는 행 하나에 대한 판정만 답한다.
 */
@Component
public class PermissionEvaluator {

    private final JdbcClient jdbcClient;

    PermissionEvaluator(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * 판정 대상이 되는 행 하나. 아직 상품·주문 테이블이 없어서 호출자가 값을 채워 넘긴다.
     *
     * @param ownerUserId 이 행의 주인 계정. 주문이면 주문자, 계정이면 본인. 주인이 없으면 null
     * @param sellerId    이 행이 속한 셀러. 상품이면 파는 셀러, 주문이면 상품의 셀러. 셀러와 무관하면 null
     */
    public record Target(Long ownerUserId, Long sellerId) {

        /** 셀러와 무관하고 주인만 있는 행. 계정 정보 같은 것 */
        public static Target ownedBy(long userId) {
            return new Target(userId, null);
        }

        /** 주인 없이 셀러에만 속한 행. 상품 같은 것 */
        public static Target ofSeller(long sellerId) {
            return new Target(null, sellerId);
        }

        /** 주인과 셀러가 둘 다 있는 행. 주문이 여기 해당한다 */
        public static Target of(long ownerUserId, long sellerId) {
            return new Target(ownerUserId, sellerId);
        }
    }

    /**
     * DB에서 읽어 온 규칙 한 줄. 사용자가 가진 역할 하나와 그 역할에 달린 권한 하나가 만나 만들어진다.
     *
     * @param grantSellerId 이 역할을 어느 셀러에서 받았나. 전역 부여면 null
     */
    record Rule(String roleCode, Long grantSellerId, String scope, String effect, Set<String> fieldGroups) {

        boolean isDeny() {
            return "deny".equals(effect);
        }

        /** 넓을수록 크다. 같은 동작에 규칙이 여럿 걸릴 때 어느 허용이 이기는지를 정한다 */
        int scopeWidth() {
            return switch (scope) {
                case "all" -> 3;
                case "seller" -> 2;
                case "own" -> 1;
                default -> 0;
            };
        }

        @Override
        public String toString() {
            String where = grantSellerId == null ? "전역" : "셀러 " + grantSellerId;
            return "%s(%s) %s/%s".formatted(roleCode, where, effect, scope);
        }
    }

    /**
     * 판정 결과. 허용 여부만이 아니라 어느 규칙이 이겼는지와 어디까지 보이는지를 같이 담는다.
     * 왜 막혔는지 모르면 권한 문제는 재현이 안 된다.
     *
     * @param visibleFieldGroups 볼 수 있는 필드 그룹. <b>빈 집합은 제한이 없다는 뜻이다.</b>
     *                           아무것도 못 본다면 애초에 {@code allowed} 가 false 다
     */
    public record Decision(boolean allowed, String reason, Set<String> visibleFieldGroups) {

        /** 이 자원에 필드 제한이 걸려 있나 */
        public boolean fieldRestricted() {
            return !visibleFieldGroups.isEmpty();
        }

        /** 이 필드 그룹을 볼 수 있나. 제한이 없으면 무엇이든 볼 수 있다 */
        public boolean canSee(String fieldGroup) {
            return allowed && (visibleFieldGroups.isEmpty() || visibleFieldGroups.contains(fieldGroup));
        }

        static Decision allow(Rule rule, Set<String> fieldGroups) {
            return new Decision(true, "허용 — " + rule, fieldGroups);
        }

        static Decision denyBy(Rule rule) {
            return new Decision(false, "거부 — " + rule, Set.of());
        }

        static Decision denyBecause(String reason) {
            return new Decision(false, "거부 — " + reason, Set.of());
        }
    }

    /**
     * 이 사용자가 이 대상에 이 동작을 할 수 있나.
     *
     * <p>규칙 두 개가 방향이 반대다. 스코프는 넓은 쪽이 이기고 효과는 좁은 쪽이 이긴다.
     * 그래서 순서가 결과를 바꾼다. deny 를 먼저 훑어야 "전체 허용하되 일부 거부" 가 성립한다.
     * allow 를 먼저 보면 넓은 allow 하나가 뒤의 deny 를 가려 버린다.
     *
     * <p>허용 쪽은 첫 매치에서 끊지 않고 걸리는 규칙을 전부 모은다.
     * 끊으면 DB 가 돌려주는 순서가 판정을 정하게 되고, 필드 그룹도 먼저 걸린 규칙 것만 반영된다.
     * 역할을 둘 이상 가진 사용자는 양쪽이 허용하는 만큼을 다 봐야 한다.
     */
    public Decision decide(long userId, String resource, String action, Target target) {
        List<Rule> rules = loadRules(userId, resource, action);
        if (rules.isEmpty()) {
            return Decision.denyBecause("%s:%s 에 걸린 규칙이 하나도 없다".formatted(resource, action));
        }

        Set<Long> memberOf = loadSellerMemberships(userId);

        for (Rule rule : rules) {
            if (rule.isDeny() && covers(rule, userId, memberOf, target)) {
                return Decision.denyBy(rule);
            }
        }

        Rule widest = null;
        Set<String> fieldGroups = new HashSet<>();
        boolean unrestricted = false;

        for (Rule rule : rules) {
            if (rule.isDeny() || !covers(rule, userId, memberOf, target)) {
                continue;
            }
            if (widest == null || rule.scopeWidth() > widest.scopeWidth()) {
                widest = rule;
            }
            // 제한이 없는 규칙이 하나라도 걸리면 다른 규칙의 그룹 목록은 의미가 없다.
            if (rule.fieldGroups().isEmpty()) {
                unrestricted = true;
            }
            fieldGroups.addAll(rule.fieldGroups());
        }

        if (widest == null) {
            return Decision.denyBecause("허용 규칙은 있으나 대상이 그 범위 밖이다");
        }
        return Decision.allow(widest, unrestricted ? Set.of() : Set.copyOf(fieldGroups));
    }

    /**
     * 이 규칙의 범위가 이 대상을 덮나.
     *
     * <p>{@code seller} 스코프의 뜻이 부여 방식에 따라 갈린다.
     * 조직 역할로 받았으면 받은 그 셀러만 덮고, 전역으로 받았으면 사용자가 속한 모든 셀러를 덮는다.
     * 이걸 구분하지 않으면 A셀러의 CS 담당이 B셀러의 주문을 보게 된다.
     */
    private boolean covers(Rule rule, long userId, Set<Long> memberOf, Target target) {
        return switch (rule.scope()) {
            case "all" -> true;
            case "own" -> target.ownerUserId() != null && target.ownerUserId() == userId;
            case "seller" -> {
                if (target.sellerId() == null) {
                    yield false;
                }
                if (rule.grantSellerId() != null) {
                    yield rule.grantSellerId().equals(target.sellerId());
                }
                yield memberOf.contains(target.sellerId());
            }
            default -> false;
        };
    }

    private List<Rule> loadRules(long userId, String resource, String action) {
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

    private static Set<String> splitGroups(String joined) {
        if (joined == null || joined.isEmpty()) {
            return Set.of();
        }
        return Set.of(joined.split(","));
    }

    private Set<Long> loadSellerMemberships(long userId) {
        return Set.copyOf(jdbcClient.sql("select seller_id from seller_member where user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .list());
    }
}
