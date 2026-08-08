package com.projectshop.shop.auth;

import java.util.Set;

/**
 * 허용 범위. <b>전부이거나 목록이다.</b>
 *
 * <p>권한에서 이 모양이 계속 나온다. {@code own}·{@code seller} 는 값으로 표현되지만
 * {@code all} 은 <b>"제한이 없다"</b> 라서 넣을 값이 없다.
 *
 * <p>그래서 빈 집합에 두 뜻이 생겼다 — "아무것도 없음" 과 "제한 없음".
 * 두 자리가 그걸 <b>주석으로 막고 있었다</b>(볼 수 있는 필드 그룹, 볼 수 있는 셀러).
 * 읽는 사람이 주석을 봐야 안 틀리면 그건 가장 약한 강제 지점이다(`D23`).
 *
 * <p>타입으로 내리면 물어볼 일이 사라진다. {@code switch} 가 두 경우를 다 안 다루면
 * <b>컴파일이 안 된다.</b>
 *
 * <pre>
 * switch (visible) {
 *     case Everything&lt;Long&gt; ignored -&gt; ...
 *     case Only&lt;Long&gt;(Set&lt;Long&gt; ids) -&gt; ...
 * }
 * </pre>
 *
 * @param <T> 목록에 담기는 것. 필드 그룹 이름이거나 셀러 id 다
 */
public sealed interface Allowed<T> {

    /** 제한이 없다. 무엇이든 걸린다 */
    record Everything<T>() implements Allowed<T> {
    }

    /** 이 목록에 있는 것만. <b>빈 목록은 아무것도 없다는 뜻이다</b> — 여기서는 모호하지 않다 */
    record Only<T>(Set<T> values) implements Allowed<T> {

        public Only {
            values = Set.copyOf(values);
        }
    }

    static <T> Allowed<T> everything() {
        return new Everything<>();
    }

    static <T> Allowed<T> only(Set<T> values) {
        return new Only<>(values);
    }

    /** 이것이 범위 안에 드나. 제한이 없으면 언제나 참이다 */
    default boolean covers(T value) {
        return switch (this) {
            case Everything<T> ignored -> true;
            case Only<T>(Set<T> values) -> values.contains(value);
        };
    }

    /** 제한이 걸려 있나. 화면이 "무엇이 가려졌나" 를 물을 때 쓴다 */
    default boolean restricted() {
        return this instanceof Only;
    }

    /**
     * 목록으로 본다. <b>제한이 없으면 빈 집합</b>이라 이 값만으로는 두 경우가 안 갈린다 —
     * 응답으로 내보내는 자리에서만 쓰고, 판단에는 {@link #covers} 나 {@code switch} 를 쓴다.
     */
    default Set<T> values() {
        return switch (this) {
            case Everything<T> ignored -> Set.of();
            case Only<T>(Set<T> values) -> values;
        };
    }
}
