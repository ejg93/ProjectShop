package com.projectshop.shop.error;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * RFC 9457 본문을 만든다.
 *
 * <p>{@link ApiExceptionHandler} 밖에도 쓰는 곳이 있어서 뺐다 —
 * 인증 실패(401)는 MVC 에 닿기 전에 보안 필터가 끊어서 예외 처리기가 못 잡는다.
 * 두 자리가 각자 본문을 만들면 같은 오류가 형태만 다르게 두 벌 나간다.
 */
@Component
public class ProblemFactory {

    /** W3C Trace Context 헤더. 프론트나 프록시가 실어 보낸다 */
    private static final String TRACEPARENT = "traceparent";

    /**
     * @param detail 이 자리에서만 쓰는 설명. null 이면 {@link ErrorCode} 의 기본 문구를 쓴다
     */
    public ProblemDetail create(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                code.status(), detail == null ? code.title() : detail);

        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));

        // 오류 본문에만 넣는다(`D16`). 성공 응답에 넣으면 모든 응답이 커지는데
        // 되짚어 볼 일이 있는 것은 실패한 요청이다.
        problem.setProperty("trace_id", traceIdOf(request));

        return problem;
    }

    /**
     * 추적 ID 를 정한다.
     *
     * <p>{@code traceparent} 가 오면 그 안의 trace-id 를 쓰고, 없으면 만든다.
     * 헤더 형식은 {@code 00-<32자리 trace-id>-<16자리 span-id>-<flags>} 다.
     *
     * <p><b>아직 로그와 안 묶여 있다.</b> 이 값으로 로그를 찾으려면 MDC 에 넣어야 하는데
     * 그건 청크 2b 다 — 지금은 애플리케이션 로그가 0건이라 찾을 대상 자체가 없다.
     * 그래도 지금 넣는 이유는 <b>응답 형식이 나중에 바뀌면 그게 계약 변경</b>이라서다.
     */
    private static String traceIdOf(HttpServletRequest request) {
        String traceparent = request.getHeader(TRACEPARENT);
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 3 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}
