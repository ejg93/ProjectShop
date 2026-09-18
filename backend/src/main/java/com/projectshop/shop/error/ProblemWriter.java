package com.projectshop.shop.error;

import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 컨트롤러 밖에서 끊는 응답에 RFC 9457 본문을 쓴다({@code Q84}).
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@code @ExceptionHandler} 는 컨트롤러에 들어온 요청만 본다. <b>필터가 먼저 끊으면
 * 그 자리를 안 지나간다</b> — 그래서 {@code response.setStatus} 로 끝내던 자리 셋이
 * 상태 코드만 내보내고 있었다(마무리 24차 독립 리뷰).
 *
 * <p>받는 쪽에서 그것은 <b>{@code trace_id} 도 {@code type} 도 없는 응답</b>이고,
 * {@code ProblemFactory} 를 안 지나서 <b>오류율 지표에도 안 잡힌다</b>(청크 62).
 *
 * <h2>한 자리에 모은다</h2>
 *
 * <p>같은 여섯 줄을 네 곳에 쓰면 한 곳을 고칠 때 나머지가 안 따라온다 —
 * {@code Content-Type} 하나만 빠져도 받는 쪽은 그것을 본문 없는 응답으로 읽는다.
 */
@Component
public class ProblemWriter {

    private final ProblemFactory problems;
    private final ObjectMapper objectMapper;

    ProblemWriter(ProblemFactory problems, ObjectMapper objectMapper) {
        this.problems = problems;
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {
        write(request, response, code, null);
    }

    /**
     * @param challenge {@code WWW-Authenticate} 값. 401 에만 뜻이 있고 없으면 안 붙인다.
     *                  <b>RFC 9110 이 401 에 이 헤더를 요구한다</b>
     */
    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code,
            String challenge) throws IOException {

        ProblemDetail problem = problems.create(code, null, request);

        response.setStatus(code.status().value());
        if (challenge != null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge);
        }
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
