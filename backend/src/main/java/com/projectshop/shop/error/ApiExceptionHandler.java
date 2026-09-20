package com.projectshop.shop.error;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
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

    /**
     * 추적 ID 는 MDC 가 붙인다(`D16`). 그래서 여기서 따로 안 싣는다 —
     * 응답의 {@code trace_id} 와 로그 앞머리의 값이 같아야 서로 이어진다.
     */
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

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
     *
     * <p><b>객체 전체에 걸린 것도 담는다</b>(`Q127` 독립 리뷰). 클래스 단위 제약은 칸 하나를
     * 못 짚어서 {@code getGlobalErrors} 로 오는데, 그것만 빠뜨리면 <b>같은 검증 실패가
     * 두 모양</b>이 된다 — 아래 형제 핸들러는 그것을 담고 있었다.
     *
     * <p>그 자리의 이름은 객체 이름이다. 화면은 요청 본문에 그런 칸이 없으므로 짚지 못하고,
     * 폼 전체 오류로 그린다(`13h` 「모르는 칸을 지목하지 않는다」).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<FieldError> errors = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(error -> errors.add(
                new FieldError(toSnakeCase(error.getField()), error.getDefaultMessage())));
        e.getBindingResult().getGlobalErrors().forEach(error -> errors.add(
                new FieldError(toSnakeCase(error.getObjectName()), error.getDefaultMessage())));

        return validationFailed(errors, request);
    }

    /**
     * 메서드 파라미터에 걸린 제약이 깨졌다(`Q127`).
     *
     * <p><b>같은 검증 실패인데 예외가 갈린다.</b> 입구에 {@code @RequestHeader @Size} 처럼
     * <b>파라미터 자체에 붙은 제약</b>이 하나라도 있으면 Spring 은 그 메서드를 메서드 검증으로
     * 돌리고, 본문 검증 실패까지 묶어 이 예외로 던진다 — {@link MethodArgumentNotValidException}
     * 이 아니다.
     *
     * <p>이 자리를 안 덮으면 {@link #createResponseEntity} 가 400 을
     * {@code MALFORMED_REQUEST} 로 옮기고 {@code errors} 도 사라진다. 그러면 화면은
     * 어느 칸이 틀렸는지 모른 채 「잠시 후 다시 시도해 주세요」를 띄우는데,
     * <b>다시 시도해도 같은 값이면 또 틀린다</b>(`D20`).
     *
     * <p><b>주문 입구가 실제로 그랬다</b> — 배포한 사이트에서 우편번호를 잘못 적으면
     * 결제가 시작도 안 하고 「결제하지 못했습니다」가 떴다.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        List<FieldError> errors = new ArrayList<>();
        for (ParameterValidationResult result : e.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bean) {
                // 본문 객체다. 칸 이름이 요청에 쓴 이름과 같아야 화면이 그 칸을 찾는다.
                bean.getFieldErrors().forEach(error -> errors.add(
                        new FieldError(toSnakeCase(error.getField()), error.getDefaultMessage())));
                bean.getGlobalErrors().forEach(error -> errors.add(
                        new FieldError(parameterNameOf(result.getMethodParameter()),
                                error.getDefaultMessage())));
                continue;
            }
            // 헤더·질의 파라미터·경로 변수다. 본문 칸이 아니므로 본문 표기로 안 바꾼다.
            String name = parameterNameOf(result.getMethodParameter());
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(new FieldError(name, error.getDefaultMessage()));
            }
        }
        return validationFailed(errors, request);
    }

    /**
     * 검증 실패 응답을 만든다. <b>예외가 무엇이든 한 이름으로 나간다</b>(`D5` — 프론트는
     * 상태 코드가 아니라 {@code type} 으로 분기한다).
     */
    private ResponseEntity<Object> validationFailed(List<FieldError> errors, WebRequest request) {
        ProblemDetail problem = problems.create(
                ErrorCode.VALIDATION_FAILED, null, servletRequestOf(request));
        problem.setProperty("errors", errors);

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.status()).body(problem);
    }

    /**
     * 파라미터를 <b>요청에 쓴 이름</b>으로 부른다.
     *
     * <p>헤더는 {@code Idempotency-Key} 고 질의 파라미터는 {@code page} 다 — Java 이름을 그대로
     * 주면 보낸 쪽에 없는 이름이라 화면이 짚을 칸을 못 찾는다(`D20` 「모르는 칸을 지목하지 않는다」).
     * 애너테이션이 이름을 안 적었으면 Java 이름을 snake_case 로 바꿔 쓴다.
     */
    private static String parameterNameOf(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.name().isEmpty()) {
            return header.name();
        }
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        if (param != null && !param.name().isEmpty()) {
            return param.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        String name = parameter.getParameterName();
        return name == null ? "" : toSnakeCase(name);
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
                    frameworkCodeOf(statusCode), detail.getDetail(), servletRequestOf(request));
            return super.createResponseEntity(ours, headers, statusCode, request);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    /**
     * 프레임워크가 정한 상태 코드를 우리 {@link ErrorCode} 로 옮긴다.
     *
     * <p><b>하나로 뭉치면 안 된다</b>(`Q1`). 여기서 전부 {@code validation-failed} 를 주면
     * 405·415·깨진 JSON 이 같은 {@code type} 으로 나가고, 그러면 <b>상태 코드보다 {@code type} 이
     * 더 뭉친다</b> — `D5` 가 상태 코드 대신 {@code type} 으로 분기하라고 한 근거가 그 자리에서 뒤집힌다.
     *
     * <p>예외 종류가 아니라 상태 코드로 가른다. 프레임워크가 예외를 늘려도 상태 코드는 같은 표에
     * 떨어지고, 우리가 모르는 것은 <b>그 무리의 기본값</b>으로 내려간다.
     */
    private static ErrorCode frameworkCodeOf(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return ErrorCode.ENDPOINT_NOT_FOUND;
        }
        return status.is5xxServerError() ? ErrorCode.INTERNAL : ErrorCode.MALFORMED_REQUEST;
    }

    /**
     * 마지막 그물.
     *
     * <p>여기까지 온 것은 우리가 예상 못 한 것이다. <b>원인을 응답에 안 담는다</b> —
     * 스택이나 SQL 문구가 나가면 그 자체가 정보 유출이다(`D14`).
     * 원인은 로그에 남기고 클라이언트에는 추적 ID 만 준다.
     *
     * <p><b>로그를 실제로 남긴다</b>(`35c` 에서 고쳤다). 그 전까지 이 주석만 있고 코드가 없어서
     * <b>500 이 나가는데 원인이 어디에도 안 남았다</b> — 응답에는 추적 ID 가 있는데
     * 그 ID 로 찾을 줄이 없었다. 스택까지 남긴다. 여기 온 예외는 우리가 모르는 것이라
     * 메시지 한 줄로는 어느 줄에서 났는지 못 찾는다(`D16`).
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception e, HttpServletRequest request) {
        log.error("처리하지 못한 예외: {} {}", request.getMethod(), request.getRequestURI(), e);
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
