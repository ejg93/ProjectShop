package com.projectshop.shop.support;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 컨트롤러가 {@link Paging} 을 받으면 {@code page}·{@code size} 를 읽어서 만들어 준다(`Q23`).
 *
 * <p><b>보정을 지나는 자리를 하나로 만든다.</b> 전에는 컨트롤러가 {@code int page, int size} 를
 * 받아 그대로 넘기고 {@code *Query} 가 각자 보정을 불렀다 — 목록이 하나 늘 때 그 호출을
 * 빠뜨려도 <b>컴파일도 테스트도 통과하고 상한만 사라진다.</b> 생 {@code int} 를 받는 자리를
 * 없애면 빠뜨릴 대상 자체가 없어진다.
 *
 * <p><b>보정 자체는 여기가 아니라 {@link Paging} 의 생성자가 한다.</b> 그래서 이 클래스를
 * 안 거치고 {@code new Paging(...)} 으로 만들어도 값이 성하다 — 강제가 이 클래스에 매달려 있지 않다.
 *
 * <p><b>숫자가 아니면 400 이다.</b> {@code @RequestParam int} 였을 때 Spring 이 내던 것과
 * 같은 예외를 던진다 — {@code ApiExceptionHandler} 가 {@code ResponseEntityExceptionHandler} 를
 * 상속하고 있어서 그대로 우리 형식의 400 이 된다. 조용히 기본값으로 떨어뜨리면
 * <b>오타를 낸 클라이언트가 자기가 뭘 잘못했는지 영영 모른다.</b>
 */
class PagingArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String PAGE = "page";
    private static final String SIZE = "size";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Paging.class.equals(parameter.getParameterType());
    }

    @Override
    public Paging resolveArgument(MethodParameter parameter, ModelAndViewContainer mav,
            NativeWebRequest request, WebDataBinderFactory binderFactory) {

        return new Paging(
                intParam(request, parameter, PAGE, 0),
                intParam(request, parameter, SIZE, ListQuery.DEFAULT_SIZE));
    }

    /** 안 주면 기본값, 숫자가 아니면 400. 범위 보정은 {@link Paging} 의 생성자가 한다. */
    private static int intParam(NativeWebRequest request, MethodParameter parameter,
            String name, int fallback) {

        String raw = request.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new MethodArgumentTypeMismatchException(raw, int.class, name, parameter, e);
        }
    }
}
