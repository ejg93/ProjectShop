package com.projectshop.shop.error;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * MVC 앞 필터에서 터진 의존 실패를 503 으로 옮긴다(`Q260`).
 *
 * <p><b>{@link ApiExceptionHandler} 가 못 보는 자리다.</b> 로그인한 요청은 컨트롤러에 닿기 전에
 * 세션을 Redis 에서 읽고({@code SessionRepositoryFilter}) 계정이 살았나를 DB 에 묻는다({@code AccountLivenessFilter}).
 * 거기서 던진 것은 서블릿 컨테이너의 오류 화면으로 가서 500 이었다 — 같은 장애가 비로그인에는 503, 로그인에는 500 으로 갈렸다.
 *
 * <h2>자리</h2>
 *
 * <p><b>세션 필터보다 바깥</b>({@code SessionRepositoryFilter.DEFAULT_ORDER} 는 {@code HIGHEST_PRECEDENCE + 50})이어야
 * 세션을 읽다 터진 것을 받는다. <b>{@code RequestLogFilter}(+10) 보다 안쪽</b>이어야 그 줄이 503 을 찍는다.
 *
 * <p><b>응답이 이미 나갔으면 못 바꾼다.</b> 본문을 쓰기 시작한 뒤에 터진 것(세션 저장이 응답 뒤에 실패)은 그대로 다시 던진다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DependencyFailureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DependencyFailureFilter.class);

    private final ProblemWriter problems;

    DependencyFailureFilter(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (RuntimeException | ServletException e) {
            Throwable failure = DependencyFailure.find(e);
            if (failure == null || response.isCommitted()) {
                throw e;
            }
            log.error("바깥 의존에 닿지 못했다(필터): {} {} — {}: {}", request.getMethod(), request.getRequestURI(),
                    failure.getClass().getSimpleName(), failure.getMessage());
            response.reset();
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(DependencyFailure.RETRY_AFTER.toSeconds()));
            problems.write(request, response, ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
