package com.projectshop.shop.audit;

import java.util.List;

/**
 * 아직 열람 기록을 안 남긴 목록 한 쪽(`Q282b`). 값은 {@link #record} 로만 꺼낸다 — 기록을 빠뜨리면 값을 못 얻어서
 * 컴파일이 안 된다(안전성 확보조치 기준 제8조①, `D2` R44).
 *
 * <p>만드는 것은 누구나 한다. 꺼내는 길이 증서({@link ListAccess})를 받는 하나뿐이고, 값 접근자는 없다.
 *
 * @param <T> 목록 한 쪽
 */
public final class Unrecorded<T> {

    private final T value;
    private final List<String> numbers;

    private Unrecorded(T value, List<String> numbers) {
        this.value = value;
        this.numbers = List.copyOf(numbers);
    }

    /**
     * @param value   목록 한 쪽
     * @param numbers 그 쪽에 실린 자원의 노출 번호. 정보주체는 이 번호로 찾는다
     */
    public static <T> Unrecorded<T> of(T value, List<String> numbers) {
        return new Unrecorded<>(value, numbers);
    }

    /** 열람 기록을 남기고 쪽을 돌려준다 */
    public T record(ListAccess access) {
        access.record(numbers);
        return value;
    }
}
