package com.projectshop.shop;

/**
 * 자바 소스에서 <b>주석만</b> 걷어낸다. 문자열·텍스트 블록 안은 그대로 둔다.
 *
 * <p><b>둘이 같은 걷기를 쓴다.</b> {@link SqlTextTest} 는 주석 안의 SQL 예시가 위반으로 뜨는 것을
 * 막으려고(`ListQuery` javadoc 에 {@code " order by " + sort} 가 세 줄 있다),
 * {@link BuildInputTest} 는 주석 안의 경로 예시가 대조 대상으로 잡히는 것을 막으려고 쓴다 —
 * <b>실제로 이 클래스의 javadoc 이 자기 테스트를 빨갛게 만들었다</b>(`Q25`).
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
                while (i < end && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && next == '*') {
                out.append("  ");
                i += 2;
                while (i < end && !(source.charAt(i) == '*' && i + 1 < end && source.charAt(i + 1) == '/')) {
                    out.append(source.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                if (i < end) {
                    out.append("  ");
                    i += 2;
                }
            } else if (c == '"' && next == '"' && i + 2 < end && source.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 3;
                while (i < end && !(source.charAt(i) == '"' && i + 2 < end
                        && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"')) {
                    out.append(source.charAt(i));
                    i++;
                }
                if (i < end) {
                    out.append("\"\"\"");
                    i += 3;
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < end && source.charAt(i) != c && source.charAt(i) != '\n') {
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
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
