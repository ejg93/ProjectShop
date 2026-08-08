package com.projectshop.shop.error;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import tools.jackson.databind.ObjectMapper;

/**
 * 인증이 없을 때의 401 본문.
 *
 * <p>보안 필터가 MVC 에 닿기 전에 요청을 끊어서 {@link ApiExceptionHandler} 가 못 잡는다.
 * 여기서 직접 쓰지 않으면 <b>인증 실패만 본문 없이 상태 코드로 나가고</b>
 * 클라이언트는 그 하나만 다르게 처리해야 한다.
 *
 * <p>{@code formLogin} 을 껐으므로 302 대신 401 이 나가는 것은 이미 정해져 있다(`5-1`).
 * 이 클래스가 더하는 것은 형식뿐이다.
 */
@Component
public class ProblemEntryPoint implements AuthenticationEntryPoint {

    private final ProblemFactory problems;
    private final ObjectMapper objectMapper;

    ProblemEntryPoint(ProblemFactory problems, ObjectMapper objectMapper) {
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {

        ProblemDetail problem = problems.create(ErrorCode.UNAUTHENTICATED, null, request);

        response.setStatus(ErrorCode.UNAUTHENTICATED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
