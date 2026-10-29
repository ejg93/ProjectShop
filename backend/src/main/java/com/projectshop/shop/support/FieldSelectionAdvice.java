package com.projectshop.shop.support;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.projectshop.shop.error.InvalidParameterException;

/**
 * {@code GET} JSON 응답에 {@code fields} 를 건다(`Q309a`, `D5` 「필드 선택·확장」).
 *
 * <p><b>한 곳에 건다.</b> 입구마다 심으면 새 입구가 빠뜨린다(`D23` 축 2 「앱 검증」).
 *
 * <p><b>마스킹 뒤다.</b> 컨트롤러가 돌려준 본문은 이미 판정이 깎은 것이라({@code canSee ? v : null} +
 * {@code NON_NULL}), 여기서 고르는 것은 남은 칸 안에서다. 깎인 칸은 이름을 불러도 안 생긴다.
 * {@code _visible_field_groups} 는 무엇이 깎였는지 알리는 칸이라 늘 남긴다.
 *
 * <p>오류 응답({@link ProblemDetail})과 JSON 이 아닌 응답(파일)은 안 건드린다.
 */
@RestControllerAdvice
class FieldSelectionAdvice implements ResponseBodyAdvice<Object> {

    private static final String VISIBLE_FIELD_GROUPS = "_visible_field_groups";
    private static final String ITEMS = "items";
    private static final String OFFSET = "offset";

    private final ObjectMapper objectMapper;

    FieldSelectionAdvice(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return JacksonJsonHttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
            Class<? extends HttpMessageConverter<?>> converterType,
            ServerHttpRequest request, ServerHttpResponse response) {

        String raw = rawFields(request);
        if (raw == null || body == null || body instanceof ProblemDetail
                || !HttpMethod.GET.equals(request.getMethod())) {
            return body;
        }
        ResolvableType type = bodyType(returnType, body);
        Class<?> bodyClass = type.resolve(body.getClass());

        if (body instanceof List<?> list) {
            Class<?> element = type.getGeneric(0).resolve(firstClassOf(list));
            return filterEach(objectMapper.valueToTree(body), selection(raw, element));
        }
        if (!bodyClass.isRecord()) {
            throw new InvalidParameterException(FieldSelection.PARAMETER, "이 응답은 칸을 고를 수 없다");
        }
        Set<String> top = FieldSelection.jsonNamesOf(bodyClass);
        ObjectNode tree = objectMapper.valueToTree(body);
        if (top.contains(ITEMS) && top.contains(OFFSET)) {
            Class<?> element = itemType(type, bodyClass);
            filterEach(tree.get(ITEMS), selection(raw, element));
            return tree;
        }
        keepOnly(tree, FieldSelection.parse(raw, top).names());
        return tree;
    }

    private static String rawFields(ServerHttpRequest request) {
        return request instanceof ServletServerHttpRequest servlet
                ? servlet.getServletRequest().getParameter(FieldSelection.PARAMETER)
                : null;
    }

    /** {@code ResponseEntity<T>} 면 {@code T}. 선언이 {@code Object} 처럼 넓으면 실제 본문의 타입 */
    private static ResolvableType bodyType(MethodParameter returnType, Object body) {
        ResolvableType declared = ResolvableType.forMethodParameter(returnType);
        if (HttpEntity.class.isAssignableFrom(declared.toClass())) {
            declared = declared.as(HttpEntity.class).getGeneric(0);
        }
        Class<?> resolved = declared.resolve();
        return resolved != null && resolved.isInstance(body) ? declared : ResolvableType.forInstance(body);
    }

    /** 껍데기의 {@code items} 원소 타입. 제네릭이 안 풀리면 null */
    private static Class<?> itemType(ResolvableType envelope, Class<?> envelopeClass) {
        try {
            ResolvableType items = ResolvableType.forField(envelopeClass.getDeclaredField(ITEMS), envelope);
            return items.getGeneric(0).resolve();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(envelopeClass + " 에 items 성분이 없다", e);
        }
    }

    private static Class<?> firstClassOf(List<?> list) {
        return list.isEmpty() || list.getFirst() == null ? null : list.getFirst().getClass();
    }

    /** 원소 타입을 모르면(빈 목록의 안 풀린 제네릭) 거를 것도 없어서 검증만 건너뛴다 */
    private static Optional<Set<String>> selection(String raw, Class<?> element) {
        return Optional.ofNullable(element)
                .map(type -> FieldSelection.parse(raw, FieldSelection.jsonNamesOf(type)).names());
    }

    private static JsonNode filterEach(JsonNode array, Optional<Set<String>> selection) {
        selection.ifPresent(names -> array.forEach(item -> {
            if (item instanceof ObjectNode object) {
                keepOnly(object, names);
            }
        }));
        return array;
    }

    private static void keepOnly(ObjectNode object, Set<String> names) {
        Set<String> kept = new HashSet<>(names);
        kept.add(VISIBLE_FIELD_GROUPS);
        object.retain(kept);
    }
}
