package com.projectshop.shop.auth;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 요청마다 2차 인증 대상인데 표시 없는 세션을 끊는다(`Q453`, 안전성 확보조치 기준 제6조② `D2` R45).
 *
 * <p><b>입구가 늘어도 한 곳이 잡는다.</b> 로그인({@code AuthController})은 대상 계정에 표시 없는 세션을 안 세우지만,
 * 고객으로 로그인한 세션이 도중에 셀러·관리자 역할을 받거나, 필드를 더하기 전의 옛 세션이 남아 있으면 그 세션은
 * 표시 없이 대상이 된다. 역할을 주는 입구·로그인 입구마다 막으면 새 입구가 빠뜨린다.
 *
 * <p><b>대행 토큰은 건너뛴다.</b> 대행은 2차 인증을 지난 관리자 세션에서만 열리고({@code ImpersonationService}) 읽기 전용이다 —
 * principal 이 대상(셀러일 수 있다)이라 이 판정을 그대로 대면 대행이 셀러를 볼 때마다 끊긴다.
 *
 * <p>빈으로 두지 않는다 — {@link AccountLivenessFilter} 와 같은 이유로 {@code SecurityConfig} 가 체인에만 건다.
 */
class SecondFactorFilter extends OncePerRequestFilter {

    private final PermissionRuleLoader loader;
    private final ProblemWriter problems;

    SecondFactorFilter(PermissionRuleLoader loader, ProblemWriter problems) {
        this.loader = loader;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ImpersonationToken)
                && authentication != null
                && authentication.getPrincipal() instanceof ShopUser user
                && !user.secondFactorVerified()
                && loader.requiresSecondFactor(user.id())) {

            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            problems.write(request, response, ErrorCode.SECOND_FACTOR_REQUIRED);
            return;
        }

        chain.doFilter(request, response);
    }
}
