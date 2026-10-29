package com.projectshop.shop.support;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import com.projectshop.shop.error.InvalidParameterException;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * {@code expand} 질의 파라미터가 고른 하위 목록 이름들(`Q309b`, `D5` 「필드 선택·확장」).
 *
 * <p><b>허용 목록은 입구가 준다.</b> 상세마다 끼울 수 있는 하위 목록이 달라서 {@code fields} 처럼 타입에서 못 뽑는다.
 *
 * @param names 고른 이름. 비면 아무것도 안 끼운다
 */
public record Expansion(Set<String> names) {

    /** 요청에 쓰는 파라미터 이름 */
    public static final String PARAMETER = "expand";

    /** 끼우는 첫 쪽 — 하위 입구가 파라미터 없이 받을 때와 같다 */
    public static final Paging FIRST_PAGE = new Paging(0, ListQuery.DEFAULT_LIMIT);

    public Expansion {
        names = Set.copyOf(names);
    }

    /**
     * 쉼표로 구분한 이름을 읽는다. 없으면 빈 것이고 {@code allowed} 밖의 이름이 있으면 400 이다.
     *
     * <p><b>모르는 이름을 조용히 빼지 않는다</b> — {@code fields} 와 같은 쪽이다.
     */
    public static Expansion of(String raw, Set<String> allowed) {
        Set<String> names = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",", -1)) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        Set<String> unknown = new TreeSet<>(names);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new InvalidParameterException(PARAMETER,
                    "끼울 수 없는 목록이다: " + unknown + ". 끼울 수 있는 것: " + new TreeSet<>(allowed));
        }
        return new Expansion(names);
    }

    public boolean has(String name) {
        return names.contains(name);
    }
}
