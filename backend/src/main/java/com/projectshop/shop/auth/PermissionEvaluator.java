package com.projectshop.shop.auth;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.projectshop.shop.audit.AuditLog;

/**
 * 한 사용자가 특정 자원에 특정 동작을 할 수 있는지를 한 군데서 판정한다.
 * 서비스 코드에 {@code if (order.sellerId != me.id)} 가 흩어지는 것을 막으려고 만든다.
 *
 * <p>판정에 쓰이는 축이 다섯이다.
 * <ul>
 *   <li>권한 — {@code resource:action} 이 역할에 달려 있나</li>
 *   <li>스코프 — 그 권한이 어느 범위의 행에 미치나 (own, seller, all)</li>
 *   <li>조직 — 역할이 특정 셀러에 묶여 부여됐나</li>
 *   <li>효과 — allow 인가 deny 인가</li>
 *   <li>상태 — 대상이 지금 그 동작을 열어 두는 상태인가 ({@link StatusPolicy})</li>
 * </ul>
 *
 * <p>응답 필드를 깎는 축(청크 4d)은 여기 없다.
 * 목록 조회에 스코프를 섞는 일(청크 8)도 여기가 아니다. 이 클래스는 행 하나에 대한 판정만 답한다.
 */
@Component
public class PermissionEvaluator {

    private final PermissionRuleLoader loader;
    private final AuditLog auditLog;
    private final List<StatusPolicy> statusPolicies;

    PermissionEvaluator(PermissionRuleLoader loader, AuditLog auditLog,
            List<StatusPolicy> statusPolicies) {
        this.loader = loader;
        this.auditLog = auditLog;
        this.statusPolicies = List.copyOf(statusPolicies);
    }

    /**
     * 판정 대상이 되는 행 하나. 아직 상품·주문 테이블이 없어서 호출자가 값을 채워 넘긴다.
     *
     * @param ownerUserId 이 행의 주인 계정. 주문이면 주문자, 계정이면 본인. 주인이 없으면 null
     * @param sellerId    이 행이 속한 셀러. 상품이면 파는 셀러, 주문이면 상품의 셀러. 셀러와 무관하면 null
     * @param status      이 행이 지금 어느 상태인가. 주문이면 배송 상태다(`D7`). <b>안 실어 보내면
     *                    상태 축이 걸린 동작에서 거부된다</b> — 빠뜨린 것이 조용히 허용으로 안 떨어진다
     */
    public record Target(Long ownerUserId, Long sellerId, String status) {

        /** 셀러와 무관하고 주인만 있는 행. 계정 정보 같은 것 */
        public static Target ownedBy(long userId) {
            return new Target(userId, null, null);
        }

        /** 주인 없이 셀러에만 속한 행. 상품 같은 것 */
        public static Target ofSeller(long sellerId) {
            return new Target(null, sellerId, null);
        }

        /** 주인과 셀러가 둘 다 있는 행. 주문이 여기 해당한다 */
        public static Target of(long ownerUserId, long sellerId) {
            return new Target(ownerUserId, sellerId, null);
        }

        /** 같은 대상에 상태를 실는다. 상태 축이 걸린 동작을 판정하려면 이걸 거쳐야 한다 */
        public Target inStatus(String status) {
            return new Target(ownerUserId, sellerId, status);
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
     * @param visibleFieldGroups 볼 수 있는 필드 그룹. {@link Allowed} 라 <b>"전부" 와 "이 목록만" 이
     *                           타입으로 갈린다</b> — 빈 집합의 뜻을 물어볼 일이 없다
     */
    public record Decision(boolean allowed, String reason, Allowed<String> visibleFieldGroups) {

        /** 이 자원에 필드 제한이 걸려 있나 */
        public boolean fieldRestricted() {
            return visibleFieldGroups.restricted();
        }

        /** 이 필드 그룹을 볼 수 있나. 제한이 없으면 무엇이든 볼 수 있다 */
        public boolean canSee(String fieldGroup) {
            return allowed && visibleFieldGroups.covers(fieldGroup);
        }

        static Decision allow(Rule rule, Allowed<String> fieldGroups) {
            return new Decision(true, "허용 — " + rule, fieldGroups);
        }

        static Decision denyBy(Rule rule) {
            // 거부는 볼 것이 없다. Everything 이 아니라 빈 Only 다 — 그 구분이 여기서 값을 한다.
            return new Decision(false, "거부 — " + rule, Allowed.only(Set.of()));
        }

        static Decision denyBecause(String reason) {
            return new Decision(false, "거부 — " + reason, Allowed.only(Set.of()));
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
        List<Rule> rules = loader.loadRules(userId, resource, action);
        Decision decision = rules.isEmpty()
                ? Decision.denyBecause("%s:%s 에 걸린 규칙이 하나도 없다".formatted(resource, action))
                : evaluate(rules, loader.loadSellerMemberships(userId), userId, target,
                        allowedStatuses(resource, action));

        if (!decision.allowed()) {
            recordDenial(userId, resource, action, target, decision);
        }
        return decision;
    }

    /**
     * 거부를 감사 로그에 남긴다. 호출자가 부르는 게 아니라 여기서 남기는 이유는 <b>빠뜨릴 수 없게</b> 하려는 것이다.
     * 새 API 를 만들면서 기록을 잊으면 그 경로만 감사에서 통째로 사라지는데, 그건 나중에 알 방법이 없다.
     *
     * <p>화면이 권한에 따라 버튼을 가리기 전(청크 13b)까지는 정상적인 거부도 여기 쌓인다.
     * 공격 시도를 가려내는 것은 쌓인 뒤에 세고 묶어서 할 일이다.
     */
    private void recordDenial(long userId, String resource, String action, Target target, Decision decision) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("resource", resource);
        detail.put("action", action);
        detail.put("reason", decision.reason());
        detail.put("owner_user_id", target.ownerUserId());
        detail.put("seller_id", target.sellerId());
        detail.put("status", target.status());

        auditLog.record("permission.denied", userId, AuditLog.Target.ofType(resource), detail);
    }

    /**
     * 이 동작에 상태 축이 걸려 있나. 걸려 있으면 어느 상태에서 열리나.
     *
     * <p>한 자원은 정책 하나가 맡는다. 둘이 같은 {@code resource:action} 에 답하면
     * 먼저 등록된 것이 이기는데, <b>그 상황은 표가 두 벌이라는 뜻이라 애초에 만들지 않는다.</b>
     */
    private Allowed<String> allowedStatuses(String resource, String action) {
        for (StatusPolicy policy : statusPolicies) {
            Allowed<String> statuses = policy.allowedStatuses(resource, action);
            if (statuses.restricted()) {
                return statuses;
            }
        }
        return Allowed.everything();
    }

    /**
     * 규칙 목록으로 판정한다. DB 를 보지 않는 순수 계산이라 규칙을 직접 만들어 부를 수 있다.
     *
     * <p>{@link #decide} 에서 떼어 낸 이유는 검증 비용 때문이다.
     * 붙어 있으면 판정 규칙 하나를 확인하는 데도 계정·셀러·소속·역할 부여를 DB 에 넣어야 하고,
     * 청크 4c 의 매트릭스는 그 준비를 수백 번 반복하게 된다.
     *
     * <p>테스트 전용 구현이 아니라 {@link #decide} 가 실제로 거쳐 가는 경로다.
     * 따로 만들면 운영과 다른 것을 검증하게 된다.
     */
    static Decision evaluate(List<Rule> rules, Set<Long> memberOf, long userId, Target target) {
        return evaluate(rules, memberOf, userId, target, Allowed.everything());
    }

    /**
     * 상태 축까지 보고 판정한다.
     *
     * <p><b>상태를 규칙 뒤에 본다.</b> 먼저 보면 권한이 아예 없는 사용자도 "상태 때문에 막혔다" 는
     * 이유를 받고, 그 이유로는 무엇이 문제인지 못 찾는다.
     *
     * <p><b>이 검사가 판정 밖으로 나가면 안 된다.</b> 서비스에 두면 "권한은 있는데 상태가 막는다" 가
     * 판정 밖에서 결정돼서 거부가 감사에 안 남고, 새 경로를 만들 때 빠뜨려도 아무도 모른다.
     *
     * <p>상태를 안 보는 {@link #evaluate(List, Set, long, Target)} 는 <b>능력 목록(8a)이 쓴다</b> —
     * "이 사람이 이 동작을 할 수 있는 역할인가" 는 특정 행의 상태와 무관한 질문이다.
     * 상태별로 지금 무엇이 되는지는 그 주문을 조회할 때 답한다(청크 11c).
     */
    static Decision evaluate(List<Rule> rules, Set<Long> memberOf, long userId, Target target,
            Allowed<String> allowedStatuses) {

        Decision decision = evaluateRules(rules, memberOf, userId, target);
        if (!decision.allowed() || !allowedStatuses.restricted()) {
            return decision;
        }
        if (target.status() == null) {
            return Decision.denyBecause("상태에 걸린 동작인데 대상의 상태가 안 실려 왔다");
        }
        if (!allowedStatuses.covers(target.status())) {
            return Decision.denyBecause("상태가 %s 라 이 동작이 닫혀 있다".formatted(target.status()));
        }
        return decision;
    }

    private static Decision evaluateRules(List<Rule> rules, Set<Long> memberOf, long userId,
            Target target) {
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
        return Decision.allow(widest,
                unrestricted ? Allowed.everything() : Allowed.only(fieldGroups));
    }

    /**
     * 이 규칙의 범위가 이 대상을 덮나.
     *
     * <p>{@code seller} 스코프의 뜻이 부여 방식에 따라 갈린다.
     * 조직 역할로 받았으면 받은 그 셀러만 덮고, 전역으로 받았으면 사용자가 속한 모든 셀러를 덮는다.
     * 이걸 구분하지 않으면 A셀러의 CS 담당이 B셀러의 주문을 보게 된다.
     */
    private static boolean covers(Rule rule, long userId, Set<Long> memberOf, Target target) {
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

}
