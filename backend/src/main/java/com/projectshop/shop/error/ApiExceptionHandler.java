package com.projectshop.shop.error;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 도메인 예외를 HTTP 로 옮기는 유일한 자리(`D23`).
 *
 * <p>여기 말고 상태 코드를 정하는 곳이 생기면 `D5` 규약과 대조할 대상이 흩어진다.
 * 서비스는 {@link ShopException} 만 던지고 번역은 전부 이 클래스가 한다.
 *
 * <p><b>{@link ResponseEntityExceptionHandler} 를 상속한다.</b> Spring 이 프레임워크 예외
 * (본문 파싱 실패, 지원 안 하는 메서드·미디어 타입, 검증 실패)를 자기 핸들러로 먼저 잡는데,
 * 상속하지 않으면 <b>그것들만 우리 형식을 안 타고 나간다</b> — {@code type} 도 {@code trace_id} 도 없이.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private final ProblemFactory problems;

    ApiExceptionHandler(ProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ShopException.class)
    ProblemDetail handle(ShopException e, HttpServletRequest request) {
        return problems.create(e.code(), e.getMessage(), request);
    }

    /**
     * Bean Validation 실패.
     *
     * <p><b>어느 필드가 왜 틀렸는지를 담는다.</b> "요청 형식이 맞지 않는다" 만 주면
     * 클라이언트가 어디를 고쳐야 할지 몰라서 사람이 눈으로 찾게 된다.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = problems.create(
                ErrorCode.VALIDATION_FAILED, null, servletRequestOf(request));

        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(toSnakeCase(error.getField()), error.getDefaultMessage()))
                .toList());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(problem);
    }

    /** @param field 요청 본문의 필드 이름. 중첩이면 점 표기다 */
    record FieldError(String field, String message) {
    }

    /**
     * 나머지 프레임워크 예외. 깨진 JSON, 지원 안 하는 메서드·미디어 타입 같은 것들이다.
     *
     * <p>Spring 이 만든 본문을 버리고 우리 것으로 갈아 끼운다 — 상태 코드는 그쪽 판단이 맞지만
     * {@code type} 과 {@code trace_id} 가 없으면 프론트가 이것들만 다르게 분기해야 한다.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        if (body instanceof ProblemDetail detail && detail.getProperties() == null) {
            ProblemDetail ours = problems.create(
                    ErrorCode.VALIDATION_FAILED, detail.getDetail(), servletRequestOf(request));
            return super.createResponseEntity(ours, headers, statusCode, request);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /**
     * 마지막 그물.
     *
     * <p>여기까지 온 것은 우리가 예상 못 한 것이다. <b>원인을 응답에 안 담는다</b> —
     * 스택이나 SQL 문구가 나가면 그 자체가 정보 유출이다(`D14`).
     * 원인은 로그에 남기고 클라이언트에는 추적 ID 만 준다.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception e, HttpServletRequest request) {
        return problems.create(ErrorCode.INTERNAL, null, request);
    }

    private static HttpServletRequest servletRequestOf(WebRequest request) {
        return ((ServletWebRequest) request).getRequest();
    }

    /**
     * Jackson 이 필드 이름을 snake_case 로 내보내는데 Bean Validation 은 Java 이름을 준다.
     * 여기서 맞춰 주지 않으면 요청에 쓴 이름과 오류에 나온 이름이 갈린다(`D5`).
     */
    private static String toSnakeCase(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
