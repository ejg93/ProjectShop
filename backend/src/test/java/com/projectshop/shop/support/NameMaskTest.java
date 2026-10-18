package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 남에게 보이는 이름 가리기(`Q272`). 모양은 관례고, 법이 요구한 것은 「일부만」이다 */
class NameMaskTest {

    @Test
    @DisplayName("한 글자는 그 글자 뒤에 별 하나")
    void oneCharacter() {
        assertThat(NameMask.mask("김")).isEqualTo("김*");
    }

    @Test
    @DisplayName("두 글자는 첫 글자만 살린다")
    void twoCharacters() {
        assertThat(NameMask.mask("김철")).isEqualTo("김*");
    }

    @Test
    @DisplayName("세 글자는 가운데를 가린다")
    void threeCharacters() {
        assertThat(NameMask.mask("홍길동")).isEqualTo("홍*동");
    }

    @Test
    @DisplayName("네 글자는 가운데 둘을 가린다")
    void fourCharacters() {
        assertThat(NameMask.mask("남궁민수")).isEqualTo("남**수");
    }

    @Test
    @DisplayName("비었으면 그대로")
    void emptyStaysAsIs() {
        assertThat(NameMask.mask(null)).isNull();
        assertThat(NameMask.mask("")).isEmpty();
        assertThat(NameMask.mask("  ")).isEqualTo("  ");
    }

    /** 자모로 풀린 「홍길동」은 UTF-16 으로 아홉 칸이다 — 그대로 세면 앞 자모 하나만 남고 음절이 깨진다 */
    @Test
    @DisplayName("자모로 풀린 이름도 음절로 센다")
    void decomposedHangul() {
        String decomposed = Normalizer.normalize("홍길동", Normalizer.Form.NFD);

        assertThat(decomposed).hasSize(9);
        assertThat(NameMask.mask(decomposed)).isEqualTo("홍*동");
    }

    /** 보충 평면 글자는 UTF-16 으로 두 칸이다 — 반쪽만 남으면 화면에 깨진 글자가 나간다 */
    @Test
    @DisplayName("보충 평면 글자를 반으로 자르지 않는다")
    void supplementaryCharacter() {
        assertThat(NameMask.mask("𠮷길동")).isEqualTo("𠮷*동");
    }
}
