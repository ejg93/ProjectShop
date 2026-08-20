package com.projectshop.shop.error;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
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

    /**
     * 401 에 반드시 붙어야 하는 챌린지(RFC 9110 제15.5.2절, RFC 7235 제3.1절).
     *
     * <p>「The server generating a 401 response <b>MUST send a WWW-Authenticate header field</b>
     * containing at least one challenge」다. 예외가 없다.
     *
     * <p><b>등록된 스킴을 안 쓴다</b>(사용자 선택, `Q12`). {@code Basic} 이나 {@code Digest} 를 넣으면
     * 브라우저가 <b>기본 인증 대화상자를 띄워서 우리 로그인 화면을 가린다</b> —
     * 우리 인증은 세션 쿠키라 그 상자에 무엇을 넣어도 안 맞는다.
     *
     * <p>{@code Session} 은 IANA 에 등록된 이름이 아니다. 브라우저는 모르는 스킴을 무시하므로
     * 팝업이 안 뜨고, 형식상 챌린지는 하나 있다. <b>버린 길 셋</b>은 이렇다 —
     * 등록된 스킴(팝업이 뜬다), 403 으로 바꾸기(「인증이 없다」와 「권한이 없다」가 뭉친다),
     * 그냥 두기(MUST 위반이 근거 없이 남는다).
     *
     * <p>{@code realm} 은 안 붙인다. 그 값이 뜻을 갖는 것은 스킴이 그것을 쓰기로 정했을 때고,
     * 여기서는 아무도 안 읽는다.
     */
    private static final String CHALLENGE = "Session";

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
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, CHALLENGE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
