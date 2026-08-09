package com.projectshop.shop.auth;

import java.io.IOException;
import java.time.Duration;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 세션을 만든 지 12시간이 지나면 끊는다(`D14`).
 *
 * <p><b>무활동 만료와 다른 축이다.</b> {@code application.yml} 의 {@code session.timeout} 은
 * "마지막 요청으로부터 30분" 이라 계속 쓰면 영원히 안 끝난다. 절대 만료는 <b>언제 시작했나</b>를 보므로
 * 활동과 무관하게 끊긴다 — 탈취된 세션이 무한히 살아 있는 것을 막는 것이 목적이다.
 *
 * <p><b>서블릿에 설정 자리가 없어서 코드로만 된다.</b> 표준이 무활동 만료만 정의한다.
 *
 * <p>세션 생성 시각은 {@link HttpSession#getCreationTime()} 이 이미 들고 있다.
 * 우리가 따로 심지 않는다 — 심으면 그 값을 넣는 자리를 빠뜨릴 수 있고,
 * <b>로그인할 때 세션 ID 를 재발급하므로</b>(세션 고정 방어) 그 시점에 시각도 새로 시작한다.
 */
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    static final Duration MAX_AGE = Duration.ofHours(12);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {

        HttpSession session = request.getSession(false);

        if (session != null && isTooOld(session)) {
            session.invalidate();

            // 세션만 버리면 이 요청의 나머지 구간이 아직 인증된 상태로 돈다.
            // 컨텍스트까지 비워야 뒤따르는 필터와 컨트롤러가 비로그인으로 본다.
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }

    private static boolean isTooOld(HttpSession session) {
        long age = System.currentTimeMillis() - session.getCreationTime();
        return age > MAX_AGE.toMillis();
    }
}
