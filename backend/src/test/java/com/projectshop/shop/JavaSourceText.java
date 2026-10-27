package com.projectshop.shop;

/**
 * 자바 소스에서 <b>주석만</b> 걷어낸다. 문자열·텍스트 블록 안은 그대로 둔다.
 *
 * <p><b>넷이 같은 걷기를 쓴다.</b> {@link SqlTextTest} 는 주석 안의 SQL 예시가 위반으로 뜨는 것을
 * 막으려고(`ListQuery` javadoc 에 {@code " order by " + sort} 가 세 줄 있다),
 * {@link BuildInputTest} 는 주석 안의 경로 예시가 대조 대상으로 잡히는 것을 막으려고 쓴다 —
 * <b>실제로 이 클래스의 javadoc 이 자기 테스트를 빨갛게 만들었다</b>(`Q25`).
 * {@link RowMapperCallTest}·{@link SchemaNameTest} 도 소스를 훑기 전에 이것으로 주석을 걷는다.
 * 줄 보존은 {@link JavaSourceTextTest} 가 잰다(`Q306b`).
 *
 * <p>줄 구조를 그대로 둔다 — 줄을 없애면 번호가 밀려서 어디를 고쳐야 하는지 못 짚는다.
 *
 * <p>문자열을 살려 두는 것이 문자열 안의 {@code //}(URL) 를 주석으로 오인하는 것도 같이 막는다.
 * 텍스트 블록 안의 {@code \"""} 이스케이프는 안 센다 — 지금 없고, 생기면 그 파일을 잘못 걷는다.
 */
final class JavaSourceText {

    private JavaSourceText() {
    }

    static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int end = source.length();
        while (i < end) {
            char c = source.charAt(i);
            char next = i + 1 < end ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                i = skipLineComment(source, i, out);
            } else if (c == '/' && next == '*') {
                i = skipBlockComment(source, i, out);
            } else if (c == '"' && next == '"' && i + 2 < end && source.charAt(i + 2) == '"') {
                i = copyTextBlock(source, i, out);
            } else if (c == '"' || c == '\'') {
                i = copyQuoted(source, i, out);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** {@code //} 부터 줄 끝 앞까지를 공백으로. 다음 인덱스(개행 자리)를 돌려준다 */
    private static int skipLineComment(String source, int start, StringBuilder out) {
        int i = start;
        int end = source.length();
        while (i < end && source.charAt(i) != '\n') {
            out.append(' ');
            i++;
        }
        return i;
    }

    /** {@code /*} 부터 닫는 표시까지를 공백으로 — 개행은 남긴다 */
    private static int skipBlockComment(String source, int start, StringBuilder out) {
        int end = source.length();
        out.append("  ");
        int i = start + 2;
        while (i < end && !(source.charAt(i) == '*' && i + 1 < end && source.charAt(i + 1) == '/')) {
            out.append(source.charAt(i) == '\n' ? '\n' : ' ');
            i++;
        }
        if (i < end) {
            out.append("  ");
            i += 2;
        }
        return i;
    }

    /** 텍스트 블록을 닫는 {@code """} 까지 그대로 옮긴다 */
    private static int copyTextBlock(String source, int start, StringBuilder out) {
        int end = source.length();
        out.append("\"\"\"");
        int i = start + 3;
        while (i < end && !(source.charAt(i) == '"' && i + 2 < end
                && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"')) {
            out.append(source.charAt(i));
            i++;
        }
        if (i < end) {
            out.append("\"\"\"");
            i += 3;
        }
        return i;
    }

    /** 문자열·문자 리터럴을 같은 따옴표나 줄 끝까지 옮긴다. 이스케이프는 두 글자를 같이 넘긴다 */
    private static int copyQuoted(String source, int start, StringBuilder out) {
        int end = source.length();
        char quote = source.charAt(start);
        out.append(quote);
        int i = start + 1;
        while (i < end && source.charAt(i) != quote && source.charAt(i) != '\n') {
            if (source.charAt(i) == '\\' && i + 1 < end) {
                out.append(source.charAt(i)).append(source.charAt(i + 1));
                i += 2;
                continue;
            }
            out.append(source.charAt(i));
            i++;
        }
        if (i < end) {
            out.append(source.charAt(i));
            i++;
        }
        return i;
    }
}
