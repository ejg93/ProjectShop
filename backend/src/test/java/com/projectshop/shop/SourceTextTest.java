package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * main 소스를 글자로 재는 규약 둘(`Q226`, {@code quality-gates.md} 「규칙 원장」 ①④). SQL 은 {@link SqlTextTest} 가 든다.
 *
 * <h2>{@code default} 가지는 이유를 단다</h2>
 *
 * <p>{@code coding-rules.md} 「열거형으로 분기할 때 {@code default} 를 안 둔다」 — 값이 하나 늘 때 컴파일이 통과하고
 * 그 값이 {@code default} 의 답을 받는데, 그건 「아직 안 정했다」가 아니라 <b>정해진 오답</b>이다. javac 에 그 lint 가 없고
 * ArchUnit 은 {@code switch} 를 못 봐서 막는 것이 0 이었다. <b>예외가 둘 있다</b> — 열거형이 아니라 문자열을 가르는 것,
 * 그리고 몇몇만 특별하고 나머지가 다수의 정답인 것. 그래서 {@code default} 마다 바로 위 줄에
 * {@code // default-ok: <사유>} 를 단다. 사유는 {@link #DEFAULT_REASONS} 둘뿐이다.
 *
 * <h2>자체 헤더에 {@code X-} 를 안 붙인다</h2>
 *
 * <p>{@code api-guidelines.md} 「헤더」 — RFC 6648 이 그 관행을 폐기했다. 받는 헤더(`X-Forwarded-For`)는 톰캣이 읽어서
 * main 에 글자로 안 나온다. 주석 줄은 안 본다.
 */
@DisplayName("main 소스 글자 규약")
class SourceTextTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** {@code default} 가 정답인 두 경우. 늘리려면 {@code coding-rules.md} 의 예외 절부터 고친다 */
    private static final Set<String> DEFAULT_REASONS = Set.of("string-switch", "majority");

    private static final Pattern DEFAULT_ARM = Pattern.compile("^\\s*default\\s*(->|:)");
    private static final Pattern DEFAULT_MARKER = Pattern.compile("^\\s*//\\s*default-ok:\\s*([a-z-]+)");
    private static final Pattern CUSTOM_HEADER = Pattern.compile("\"X-[A-Za-z]");

    private static final List<Path> FILES = javaFiles();

    @Test
    @DisplayName("switch 의 default 가지마다 바로 위에 알려진 사유의 마커가 있다")
    void defaultArmsCarryAKnownReason() {
        List<String> missing = new ArrayList<>();
        List<String> dangling = new ArrayList<>();
        for (Path file : FILES) {
            List<String> lines = read(file);
            for (int i = 0; i < lines.size(); i++) {
                Matcher marker = DEFAULT_MARKER.matcher(lines.get(i));
                boolean isMarker = marker.find();
                if (isMarker && (i + 1 >= lines.size() || !DEFAULT_ARM.matcher(lines.get(i + 1)).find())) {
                    dangling.add(file + ":" + (i + 1));
                }
                if (!DEFAULT_ARM.matcher(lines.get(i)).find()) {
                    continue;
                }
                Matcher above = i > 0 ? DEFAULT_MARKER.matcher(lines.get(i - 1)) : null;
                if (above == null || !above.find() || !DEFAULT_REASONS.contains(above.group(1))) {
                    missing.add(file + ":" + (i + 1));
                }
            }
        }
        assertThat(missing)
                .as("사유 없는 default 가지. 열거형이면 가지를 다 적고 default 를 지운다 — 값이 늘 때 컴파일이 알려 준다."
                        + " 문자열을 가르거나 다수가 정답이면 바로 위에 // default-ok: string-switch|majority 를 단다"
                        + " (coding-rules.md 「열거형으로 분기할 때 default 를 안 둔다」)")
                .isEmpty();
        assertThat(dangling).as("아래 줄이 default 가 아닌 마커 — default 를 지웠으면 마커도 지운다").isEmpty();
    }

    @Test
    @DisplayName("main 소스가 X- 로 시작하는 헤더 이름을 안 쓴다")
    void noCustomXHeaders() {
        List<String> hits = new ArrayList<>();
        for (Path file : FILES) {
            List<String> lines = read(file);
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                if (CUSTOM_HEADER.matcher(lines.get(i)).find()) {
                    hits.add(file + ":" + (i + 1));
                }
            }
        }
        assertThat(hits).as("자체 헤더에 X- 를 붙였다 — RFC 6648 이 폐기한 관행이다(api-guidelines.md 「헤더」)").isEmpty();
    }

    private static List<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<Path> found = files.filter(path -> path.toString().endsWith(".java")).toList();
            assertThat(found).as("main 소스를 0개 읽었다: %s", MAIN.toAbsolutePath()).isNotEmpty();
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스를 못 읽었다: " + MAIN.toAbsolutePath(), e);
        }
    }

    private static List<String> read(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " 를 못 읽었다", e);
        }
    }
}
