package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 목록 조회기의 행 매퍼가 다른 질의·판정을 안 부르는지 글자로 본다(`Q255`, `coding-rules.md` 「SQL >
 * 행 매퍼 안에서 다른 질의·판정을 안 부른다」).
 *
 * <p>행 매퍼 안에서 판정을 부르면 결과를 읽는 연결 하나를 쥔 채 둘째 연결을 문다 — 풀을 다 쥔 동시 요청이 서로를
 * 기다린다(`Q250`). {@link ListQueryConnectionTest} 는 고친 세 자리를 <b>연결로</b> 재고, 이 시험은 모든
 * {@code *Query} 를 <b>글자로</b> 잰다. 새 목록이 생겨도 이쪽이 먼저 문다.
 *
 * <p><b>ArchUnit 으로 안 짰다.</b> 람다는 합성 메서드라 「{@code .query(} 의 인자」와 「{@code .list()} 뒤
 * {@code .map(row -> allowedActions…)}」이 똑같이 보인다 — 후자가 바로 고친 모양이라 그물이 고친 코드를 문다.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <ul>
 *   <li><b>메서드 참조 꼴</b> — {@code .query(X::mapRow)} 는 본문이 다른 자리에 있다. 지금 {@code *Query} 에 0건이다</li>
 *   <li><b>도우미를 거친 호출</b> — 행 매퍼가 부른 도우미가 안에서 질의하면 글자로는 안 보인다</li>
 *   <li><b>목록에 없는 협력자</b> — 금지 목록은 지금 {@code *Query} 가 든 필드 이름이다. 새 협력자를 들이면 여기 더한다</li>
 *   <li><b>{@code *Query} 밖</b> — 서비스의 {@code .query(} 람다는 안 걷는다</li>
 * </ul>
 */
@DisplayName("행 매퍼 안의 호출")
class RowMapperCallTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * 행 매퍼가 람다로 시작하는 자리 — 인자 이름·형을 안 가린다({@code (rs, rowNum)}·{@code (r, n)}·
     * {@code (ResultSet rs, int rowNum)}·{@code rs}), 캐스트 꼴({@code (RowCallbackHandler) rs ->})도 문다.
     * 메서드 참조는 머리글대로 안 본다
     */
    private static final Pattern ROW_MAPPER =
            Pattern.compile("\\.query\\(\\s*(\\(\\s*[A-Z]\\w*\\s*\\)\\s*)?(\\([^()]*\\)|\\w+)\\s*->");

    /**
     * 행 매퍼가 이보다 적게 잡히면 패턴이 낡은 것이다(2026-10-11 에 47 — 람다 46 + 캐스트 꼴 1).
     * 줄면 이 수를 같이 내리고 그 이유를 커밋에 적는다 — 「찾은 것이 0 이 아니다」만 보면 46 이 1 이 돼도 초록이다
     */
    private static final int MIN_MAPPERS = 47;

    /**
     * 행 매퍼 안에 있으면 안 되는 호출 — 판정과 질의. 낱말 경계를 건다({@code transactions.} 가 {@code actions.} 로 안 걸리게).
     * 질의는 협력자 이름과 상관없이 {@code .sql(}·{@code .query(} 로 잡는다. {@code products.} 는 안 넣는다 —
     * {@code ProductSearchQuery} 가 {@code products.publicItem(rs)} 로 행을 옮겨 담는 공용 도우미를 부른다(질의 없음)
     */
    private static final List<Pattern> FORBIDDEN = Stream.of(
                    "\\bevaluator\\.", "\\bactions\\.", "\\bdecide\\(", "\\ballowedActions\\(", "\\breturnRequests\\.",
                    "\\bloader\\.", "\\bsales\\.", "\\bruns\\.", "\\bendpoints\\.",
                    "\\.sql\\(", "\\.query\\(")
            .map(Pattern::compile)
            .toList();

    @Test
    @DisplayName("*Query 의 행 매퍼는 판정·질의를 안 부른다")
    void rowMappersDoNotJudgeOrQuery() {
        List<String> violations = new ArrayList<>();
        int mappers = 0;
        for (Path path : querySources()) {
            String text = JavaSourceText.withoutComments(read(path));
            Matcher matcher = ROW_MAPPER.matcher(text);
            while (matcher.find()) {
                mappers++;
                int open = text.indexOf('(', matcher.start());
                String body = text.substring(open, closingParen(text, open) + 1);
                for (Pattern call : FORBIDDEN) {
                    if (call.matcher(body).find()) {
                        int line = text.substring(0, matcher.start()).split("\n", -1).length;
                        violations.add(SOURCE_ROOT.relativize(path) + ":" + line + " — " + call.pattern());
                    }
                }
            }
        }

        assertThat(mappers)
                .as("행 매퍼가 줄었다 — 패턴이 낡았거나 정말 줄었다. 줄었으면 MIN_MAPPERS 를 같이 내린다")
                .isGreaterThanOrEqualTo(MIN_MAPPERS);
        assertThat(violations)
                .as("결과를 다 읽어 연결을 놓은 뒤 판정한다 — 행 매퍼는 컬럼만 담는다 (Q250, coding-rules.md 「SQL」)")
                .isEmpty();
    }

    /**
     * 여는 괄호에 짝이 맞는 닫는 괄호. 문자열·문자·텍스트 블록 안의 괄호는 안 센다 — SQL 텍스트 블록이
     * {@code count(*)} 같은 괄호를 잔뜩 든다.
     */
    private static int closingParen(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            if (text.startsWith("\"\"\"", i)) {
                i = text.indexOf("\"\"\"", i + 3) + 3;
                continue;
            }
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(text, i, c);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
            i++;
        }
        throw new IllegalStateException("괄호 짝이 안 맞는다 — 소스를 잘못 걸었다");
    }

    private static int endOfLiteral(String text, int start, char quote) {
        int i = start + 1;
        while (i < text.length() && text.charAt(i) != quote) {
            i += text.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }

    private static List<Path> querySources() {
        try (Stream<Path> paths = Files.walk(SOURCE_ROOT)) {
            return paths.filter(path -> path.getFileName().toString().endsWith("Query.java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
