package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JavaSourceText} 가 주석만 걷고 줄 구조를 그대로 두는가(`Q306b`).
 *
 * <p>쓰는 넷({@code SqlTextTest}·{@code BuildInputTest}·{@code RowMapperCallTest}·{@code SchemaNameTest})은
 * 걷은 결과로 위반을 찾을 뿐 줄 번호가 밀렸는지는 안 잰다 — 걷기를 고치다 줄이 밀리면 위반 자리를 엉뚱하게 짚는다.
 */
@DisplayName("자바 소스 주석 걷기")
class JavaSourceTextTest {

    @Test
    @DisplayName("줄 주석은 같은 길이의 공백이 된다")
    void lineComment() {
        assertThat(JavaSourceText.withoutComments("int a; // 설명\nint b;"))
                .isEqualTo("int a; " + " ".repeat("// 설명".length()) + "\nint b;");
    }

    @Test
    @DisplayName("블록 주석은 개행만 남긴다")
    void blockComment() {
        assertThat(JavaSourceText.withoutComments("a /* 하나\n둘 */ b"))
                .isEqualTo("a " + " ".repeat("/* 하나".length()) + "\n" + " ".repeat("둘 */".length()) + " b");
    }

    @Test
    @DisplayName("문자열 안의 // 는 주석이 아니다")
    void slashesInString() {
        String source = "String url = \"https://example.com\"; // 끝";
        assertThat(JavaSourceText.withoutComments(source))
                .startsWith("String url = \"https://example.com\";")
                .doesNotContain("끝");
    }

    @Test
    @DisplayName("텍스트 블록 안의 /* 는 주석이 아니다")
    void commentMarkerInTextBlock() {
        String source = "String sql = \"\"\"\n    select 1 /* 힌트 */\n    \"\"\";";
        assertThat(JavaSourceText.withoutComments(source)).isEqualTo(source);
    }

    @Test
    @DisplayName("이스케이프된 따옴표가 문자열을 닫지 않는다")
    void escapedQuote() {
        String source = "String s = \"a\\\"// 안\"; // 밖";
        assertThat(JavaSourceText.withoutComments(source))
                .contains("\"a\\\"// 안\";")
                .doesNotContain("밖");
    }

    @Test
    @DisplayName("문자 리터럴의 따옴표가 문자열을 열지 않는다")
    void charLiteral() {
        assertThat(JavaSourceText.withoutComments("char c = '\"'; // 끝"))
                .startsWith("char c = '\"';")
                .doesNotContain("끝");
    }

    @Test
    @DisplayName("입력과 출력의 길이와 줄 수가 같다")
    void keepsShape() {
        String source = "/**\n * 문서\n */\nclass A { // 꼬리\n  String s = \"/* 아님 */\";\n}\n";
        String out = JavaSourceText.withoutComments(source);
        assertThat(out).hasSameSizeAs(source);
        assertThat(out.lines().count()).isEqualTo(source.lines().count());
    }
}
