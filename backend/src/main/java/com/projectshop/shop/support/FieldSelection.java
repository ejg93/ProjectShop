package com.projectshop.shop.support;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.PropertyNamingStrategy;

import com.projectshop.shop.error.InvalidParameterException;

/**
 * {@code fields} 질의 파라미터가 고른 최상위 칸 이름들(`Q309a`, `D5` 「필드 선택·확장」).
 *
 * <p><b>허용 목록은 응답 타입의 record 성분에서 뽑는다.</b> 입구마다 목록을 적으면 사본이 생기고,
 * 칸이 하나 늘 때 그 사본만 낡는다. JSON 이름은 {@code @JsonProperty} 가 있으면 그것, 없으면
 * {@code ObjectMapper} 와 같은 {@code SNAKE_CASE} 변환이다(`application.yml` 의 {@code property-naming-strategy}).
 *
 * @param names 고른 칸. 비지 않는다
 */
record FieldSelection(Set<String> names) {

    /** 요청에 쓰는 파라미터 이름 */
    static final String PARAMETER = "fields";

    private static final PropertyNamingStrategy SNAKE_CASE = PropertyNamingStrategies.SNAKE_CASE;

    public FieldSelection {
        names = Set.copyOf(names);
    }

    /**
     * 쉼표로 구분한 이름을 읽고 {@code allowed} 에 없는 것이 있으면 400 이다.
     *
     * <p><b>모르는 이름을 조용히 빼지 않는다</b> — 오타를 낸 쪽이 칸이 없는 이유를 모른다.
     */
    static FieldSelection parse(String raw, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",", -1)) {
            String name = part.trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        if (names.isEmpty()) {
            throw new InvalidParameterException(PARAMETER, "고를 칸 이름이 없다");
        }
        Set<String> unknown = new TreeSet<>(names);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new InvalidParameterException(PARAMETER,
                    "고를 수 없는 칸이다: " + unknown + ". 고를 수 있는 것: " + new TreeSet<>(allowed));
        }
        return new FieldSelection(names);
    }

    /** record 성분의 JSON 이름. record 가 아니면 빈 집합이다 */
    static Set<String> jsonNamesOf(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        if (components == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Arrays.stream(components).forEach(component -> names.add(jsonNameOf(component)));
        return names;
    }

    private static String jsonNameOf(RecordComponent component) {
        // 성분에 단 애너테이션은 대상(METHOD·FIELD·PARAMETER)으로 옮겨 간다 — 성분 자체에는 안 남는다
        JsonProperty explicit = component.getAccessor().getAnnotation(JsonProperty.class);
        if (explicit != null && !explicit.value().isEmpty()) {
            return explicit.value();
        }
        // translate 가 protected 라 공개 입구로 부른다 — 설정·필드는 안 본다
        return SNAKE_CASE.nameForField(null, null, component.getName());
    }
}
