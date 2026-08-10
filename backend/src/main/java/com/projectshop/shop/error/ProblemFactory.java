package com.projectshop.shop.error;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.micrometer.tracing.Tracer;
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

    private final Tracer tracer;

    ProblemFactory(Tracer tracer) {
        this.tracer = tracer;
    }

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
        problem.setProperty("trace_id", traceIdOf());

        return problem;
    }

    /**
     * 지금 요청의 추적 ID.
     *
     * <p><b>{@code traceparent} 를 직접 파싱하지 않는다.</b> 추적기가 이미 그 헤더를 읽어
     * MDC 와 로그에 넣고 있으므로, 여기서 또 읽으면 같은 사실을 두 군데서 정하게 된다.
     * 헤더가 유효하지 않을 때 어느 쪽이 이기는지도 갈린다 — 그러면
     * <b>사용자가 불러 준 ID 로 로그를 찾았는데 안 나오는</b> 일이 생긴다.
     *
     * <p>추적 문맥이 없는 자리에서 오류가 나면 비운다. 요청 밖(기동·배치)이라
     * 지어내 봐야 어느 로그와도 안 이어진다.
     */
    private String traceIdOf() {
        var span = tracer.currentSpan();
        return span == null ? null : span.context().traceId();
    }
}
