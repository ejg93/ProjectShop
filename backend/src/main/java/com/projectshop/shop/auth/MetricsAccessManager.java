package com.projectshop.shop.auth;

import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * {@code /actuator/prometheus} 를 관리자에게만 연다(`Q53`, 사용자 결정 2026-09-16).
 *
 * <p><b>지표에 업무 규모가 드러난다.</b> 배치가 처리한 건수, 기한을 넘긴 환급 대기 수 같은 값이라
 * 로그인만으로 열면 손님 계정이 그것을 읽는다.
 *
 * <p><b>역할이 답을 바꾸므로 판정 엔진을 지난다</b>(`permission-rules.md` 「판정을 안 지나는 입구」의
 * 가르는 물음). 소유 조건으로 막을 자원이 아니다 — 지표에는 주인이 없다.
 * 권한은 {@code metric:read} 고 {@code V71} 이 관리자에게만 붙였다.
 *
 * <p><b>거부가 둘로 갈린다.</b> 익명은 인증 진입점이 401 로 돌려주고, 로그인했는데 권한이 없으면
 * 403 이다 — 이 관리자는 「허용인가」만 답하고 그 갈림은 Spring Security 가 한다.
 *
 * <p>{@link ObjectProvider} 로 받는 이유는 {@link PermissionEvaluator} 가 감사 로그와 상태 정책을
 * 끌고 오기 때문이다. 필터 체인을 만드는 시점에 그것들을 다 세우면 설정 사이에 순환이 생긴다.
 */
class MetricsAccessManager implements AuthorizationManager<RequestAuthorizationContext> {

    /** 지표에는 주인도 셀러도 상태도 없다. 스코프 {@code all} 만 이 대상을 덮는다 */
    private static final Target NO_TARGET = new Target(null, null, null);

    private final ObjectProvider<PermissionEvaluator> evaluators;

    MetricsAccessManager(ObjectProvider<PermissionEvaluator> evaluators) {
        this.evaluators = evaluators;
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends Authentication> authentication,
            RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (auth == null || !(auth.getPrincipal() instanceof ShopUser user)) {
            return new AuthorizationDecision(false);
        }
        return new AuthorizationDecision(
                evaluators.getObject().decide(user.id(), "metric", "read", NO_TARGET).allowed());
    }
}
