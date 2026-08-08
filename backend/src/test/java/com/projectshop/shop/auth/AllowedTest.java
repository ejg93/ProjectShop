package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 허용 범위의 두 경우가 실제로 갈리는지 본다.
 *
 * <p>이 타입을 만든 이유가 <b>빈 집합의 두 뜻</b>이었다 — "아무것도 없음" 과 "제한 없음".
 * 그 구분이 사라지면 되돌아간 것이라 여기서 못박아 둔다.
 */
@DisplayName("허용 범위")
class AllowedTest {

    @Test
    @DisplayName("전부는 무엇이든 덮는다")
    void everythingCoversAnything() {
        Allowed<String> allowed = Allowed.everything();

        assertThat(allowed.covers("무엇이든")).isTrue();
        assertThat(allowed.restricted()).isFalse();
    }

    @Test
    @DisplayName("빈 목록은 아무것도 안 덮는다 — 전부와 반대다")
    void emptyOnlyCoversNothing() {
        Allowed<String> allowed = Allowed.only(Set.of());

        assertThat(allowed.covers("무엇이든"))
                .as("이 둘이 같아지면 거부가 전체 허용이 된다. 타입을 만든 이유가 이것이다")
                .isFalse();
        assertThat(allowed.restricted()).isTrue();
    }

    @Test
    @DisplayName("전부와 빈 목록은 values() 가 같다 — 그래서 그것만 보면 안 된다")
    void valuesCannotTellThemApart() {
        assertThat(Allowed.everything().values()).isEmpty();
        assertThat(Allowed.only(Set.of()).values()).isEmpty();

        // 응답으로 내보낼 때만 values() 를 쓰고, 판단에는 covers 나 switch 를 쓴다.
        assertThat(Allowed.everything().restricted())
                .isNotEqualTo(Allowed.only(Set.of()).restricted());
    }

    @Test
    @DisplayName("목록은 담은 것만 덮는다")
    void onlyCoversItsValues() {
        Allowed<String> allowed = Allowed.only(Set.of("basic", "shipping"));

        assertThat(allowed.covers("basic")).isTrue();
        assertThat(allowed.covers("payment")).isFalse();
    }

    @Test
    @DisplayName("담은 뒤 바깥에서 못 바꾼다")
    void copiesTheGivenSet() {
        Set<String> mutable = new java.util.HashSet<>(Set.of("basic"));
        Allowed<String> allowed = Allowed.only(mutable);

        mutable.add("payment");

        assertThat(allowed.covers("payment"))
                .as("판정 결과가 나중에 바뀌면 그 결정이 언제 것인지 알 수 없다")
                .isFalse();
    }
}
