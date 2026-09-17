package com.projectshop.shop.error;

import java.net.URI;

import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;

/**
 * RFC 9457 본문을 만든다.
 *
 * <p>{@link ApiExceptionHandler} 밖에도 쓰는 곳이 있어서 뺐다 —
 * 인증 실패(401)는 MVC 에 닿기 전에 보안 필터가 끊어서 예외 처리기가 못 잡는다.
 * 두 자리가 각자 본문을 만들면 같은 오류가 형태만 다르게 두 벌 나간다.
 *
 * <p><b>오류를 세는 자리이기도 하다</b>(청크 62). 본문을 만드는 곳이 하나라 세는 곳도 하나다 —
 * 예외 처리기에만 붙이면 <b>401 이 안 세어진다</b>(보안 필터가 먼저 끊어서 거기까지 안 온다).
 */
@Component
public class ProblemFactory {

    private final Tracer tracer;
    private final MeterRegistry meters;

    ProblemFactory(Tracer tracer, MeterRegistry meters) {
        this.tracer = tracer;
        this.meters = meters;
    }

    /**
     * 무엇이 몇 번 났나(청크 62, `D16`).
     *
     * <p><b>태그가 오류 코드 하나다.</b> {@link ErrorCode} 가 닫힌 목록이라 시계열이 그 수를 안 넘는다 —
     * 경로를 태그로 달면 엔드포인트 수만큼 갈리고, 그것이 `D16` 이 경계한 자리다.
     *
     * <p><b>상태 코드는 이 지표가 안 든다.</b> 스프링이 내는 {@code http.server.requests} 가
     * 이미 그 축을 들고 있어서 두 벌이 된다 — 여기가 답하는 것은 <b>어느 규칙이 걸렸나</b>다.
     */
    private void count(ErrorCode code) {
        Counter.builder("shop.error.raised")
                .tag("code", code.name())
                .description("오류 코드별로 센다. 어느 규칙이 얼마나 걸리나")
                .register(meters)
                .increment();
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

        count(code);

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
