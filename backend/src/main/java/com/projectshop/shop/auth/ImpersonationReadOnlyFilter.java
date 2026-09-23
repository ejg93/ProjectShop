package com.projectshop.shop.auth;

import java.io.IOException;
import java.util.Set;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ProblemWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 대행 중에는 쓰기를 막는다(`16b`).
 *
 * <p><b>한 곳에서 막는다.</b> 입구마다 막으면 새 입구가 빠뜨리고, 그 입구에서 관리자가 그 사람 이름으로
 * 주문하고 탈퇴한다 — 대행이 곧 권한 우회가 된다(`D14`). 여기서 막으면 새 입구가 생겨도 따라온다.
 *
 * <p><b>읽기만 통과한다</b>({@code GET}·{@code HEAD}·{@code OPTIONS}). 예외는 둘이다 — 대행을 끝내는 입구와
 * 로그아웃. 둘 다 막으면 대행에서 빠져나올 길이 없다.
 */
class ImpersonationReadOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /** 대행 중에도 여는 쓰기. 빠져나오는 길이다 */
    static final Set<String> EXITS = Set.of("/api/admin/impersonation/end", "/api/auth/logout");

    private final ProblemWriter problems;

    ImpersonationReadOnlyFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() instanceof ImpersonationToken
                && !READS.contains(request.getMethod())
                && !EXITS.contains(request.getRequestURI())) {
            problems.write(request, response, ErrorCode.IMPERSONATION_READ_ONLY);
            return;
        }

        chain.doFilter(request, response);
    }
}
