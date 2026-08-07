package com.projectshop.shop.auth;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 요청마다 계정이 아직 살아 있는지 본다(`ADR 0010`).
 *
 * <p>로그인할 때만 보면 <b>이미 로그인한 다른 기기가 안 막힌다.</b>
 * 권한은 요청마다 다시 판정하지만 탈퇴해도 {@code user_role} 행은 그대로라 판정이 통과한다.
 * 탈퇴한 계정으로 다른 브라우저에서 계속 쓸 수 있다는 뜻이다.
 *
 * <p>이것이 두 겹 중 안쪽이다. 바깥은 탈퇴가 세션을 직접 끊는 것(5g)인데,
 * 그쪽만 두면 계정을 죽이는 경로가 늘 때 <b>끊기를 빠뜨려도 아무도 모른다.</b>
 * 관리자 계정 정지(16)와 임퍼소네이션 종료(16b)가 뒤에 온다.
 *
 * <p>빈으로 두지 않는다. {@code @Component} 를 붙이면 Boot 가 서블릿 필터로도 등록해서
 * 보안 체인 밖에서 한 번 더 돈다. {@code SecurityConfig} 가 체인에만 건다.
 */
class AccountLivenessFilter extends OncePerRequestFilter {

    private final PermissionRuleLoader loader;

    AccountLivenessFilter(PermissionRuleLoader loader) {
        this.loader = loader;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 인증이 없으면 볼 것이 없다. 익명 요청은 그대로 흘려보내고 인가 필터가 판단한다.
        if (authentication != null && authentication.getPrincipal() instanceof ShopUser user
                && !loader.isAlive(user.id())) {

            expire(request);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 죽은 계정의 세션을 그 자리에서 버린다.
     *
     * <p>401 만 주고 세션을 두면 다음 요청마다 같은 조회가 다시 돈다.
     * 여기서 끊으면 그 브라우저는 로그인 화면으로 돌아가고 조회도 멈춘다.
     */
    private void expire(HttpServletRequest request) {
        SecurityContextHolder.clearContext();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
