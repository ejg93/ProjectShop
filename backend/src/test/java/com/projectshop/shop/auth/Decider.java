package com.projectshop.shop.auth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import com.projectshop.shop.auth.PermissionEvaluator.Decision;
import com.projectshop.shop.auth.PermissionEvaluator.Rule;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.auth.managed.ManagedAuthorizationManager;
import com.projectshop.shop.auth.managed.ManagedAuthorizationManager.GrantSource;
import com.projectshop.shop.auth.managed.ManagedAuthorizationManager.ScopedGrant;
import com.projectshop.shop.auth.managed.ManagedAuthorizationManager.Verdict;

/**
 * 판정 명세(`D6`) 하나를 두 구현으로 받는 자리다(청크 `74`). {@code PermissionMatrixTest} 가 같은 스냅샷을
 * 두 구현에 돌려 다른 칸을 빨강으로 낸다.
 *
 * <p><b>시험에만 있다.</b> 두 번째 구현을 필터체인에 안 걸어서 운영 코드에 부를 곳이 없다.
 *
 * <p>입력은 {@code PermissionEvaluator.evaluate} 가 받는 다섯 그대로다 — 규칙·소속 셀러·사용자·대상·열린 상태.
 */
interface Decider {

    /** 매트릭스가 묻는 동작. 규칙을 직접 넣으므로 이름에 뜻이 없고 「규칙 없음」 문장에만 찍힌다 */
    String RESOURCE = "matrix";
    String ACTION = "check";

    /** 운영 판정기({@code PermissionEvaluator.evaluate}) */
    Decider EVALUATOR = new Decider() {

        @Override
        public Decision decide(List<Rule> rules, Set<Long> memberOf, long userId, Target target,
                Allowed<String> openStatuses) {
            // `decide` 는 규칙이 없으면 `evaluate` 앞에서 끊는다. 그 갈래를 같은 팩토리로 둔다.
            if (rules.isEmpty()) {
                return Decision.noRulesFor(RESOURCE, ACTION);
            }
            return PermissionEvaluator.evaluate(rules, memberOf, userId, target, openStatuses);
        }

        @Override
        public String toString() {
            return "PermissionEvaluator";
        }
    };

    /**
     * Spring Security 구현({@link ManagedAuthorizationManager}). 규칙을 권한 줄로 옮기고, 대상을 변수 맵에 실어
     * 넘긴 뒤 {@link Verdict} 를 {@link Decision} 으로 되옮긴다 — 근거 문자열은 이 되옮김이 쓴다.
     */
    Decider MANAGED = new Decider() {

        @Override
        public Decision decide(List<Rule> rules, Set<Long> memberOf, long userId, Target target,
                Allowed<String> openStatuses) {
            Map<ScopedGrant, Rule> origin = new HashMap<>();
            List<ScopedGrant> grants = rules.stream()
                    .map(rule -> {
                        ScopedGrant grant = new ScopedGrant(rule.roleCode(), rule.grantSellerId(),
                                rule.scope(), rule.effect(), rule.fieldGroups());
                        origin.put(grant, rule);
                        return grant;
                    })
                    .toList();
            GrantSource source = new GrantSource() {

                @Override
                public List<ScopedGrant> grantsOf(long user, String resource, String action) {
                    return grants;
                }

                @Override
                public Set<Long> sellersOf(long user) {
                    return memberOf;
                }
            };

            Verdict verdict = new ManagedAuthorizationManager(source, RESOURCE, ACTION, openStatuses)
                    .authorize(() -> UsernamePasswordAuthenticationToken.authenticated(
                            new ShopUser(userId, "matrix@test.invalid", null, true), null, List.of()),
                            new RequestAuthorizationContext(new MockHttpServletRequest(), variables(target)));

            return switch (verdict) {
                case Verdict.Granted(ScopedGrant winner, Allowed<String> groups) ->
                        Decision.allow(origin.get(winner), groups);
                case Verdict.DeniedBy(ScopedGrant grant) -> Decision.denyBy(origin.get(grant));
                case Verdict.NoGrant ignored -> Decision.noRulesFor(RESOURCE, ACTION);
                case Verdict.OutOfScope ignored -> Decision.denyBecause("허용 규칙은 있으나 대상이 그 범위 밖이다");
                case Verdict.StatusMissing ignored ->
                        Decision.denyBecause("상태에 걸린 동작인데 대상의 상태가 안 실려 왔다");
                case Verdict.StatusClosed(String status) ->
                        Decision.denyBecause("상태가 %s 라 이 동작이 닫혀 있다".formatted(status));
                case Verdict.Anonymous ignored ->
                        throw new IllegalStateException("매트릭스는 언제나 로그인한 사용자로 묻는다");
            };
        }

        /** 빈 칸은 키를 안 싣는다 — 관리자는 키가 없으면 그 칸이 빈 대상으로 읽는다 */
        private Map<String, String> variables(Target target) {
            Map<String, String> variables = new HashMap<>();
            if (target.ownerUserId() != null) {
                variables.put(ManagedAuthorizationManager.OWNER_USER_ID, target.ownerUserId().toString());
            }
            if (target.sellerId() != null) {
                variables.put(ManagedAuthorizationManager.SELLER_ID, target.sellerId().toString());
            }
            if (target.status() != null) {
                variables.put(ManagedAuthorizationManager.STATUS, target.status());
            }
            return variables;
        }

        @Override
        public String toString() {
            return "ManagedAuthorizationManager";
        }
    };

    Decision decide(List<Rule> rules, Set<Long> memberOf, long userId, Target target,
            Allowed<String> openStatuses);

}
