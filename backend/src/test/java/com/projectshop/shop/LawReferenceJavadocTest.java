package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 법을 언급하는 Javadoc 은 {@code D2} 의 요건 번호({@code R번호})를 든다(`Q394a`, {@code coding-rules.md}
 * 「법에서 온 값에는 요건 번호를 적는다」).
 *
 * <p><b>개정되면 이 번호로 찾는다.</b> 조문만 적힌 Javadoc 은 「이 상수가 법에서 왔다」는 알려도 {@code D2} 의 어느 행이
 * 그것을 강제 지점으로 드는지는 못 알린다 — 조문이 개정될 때 그 행에서 코드로 내려가는 길이 끊긴다.
 *
 * <p><b>Checkstyle 은 Javadoc 내용을 못 잰다</b>(`Q378a` — 있나만 본다). 그래서 main 의 Javadoc 블록을 글자로 뜬다.
 * 법 단어는 목록으로 센다 — 「법」 한 글자로 세면 「방법」·「문법」이 걸린다.
 *
 * <p><b>필드·열거 상수의 Javadoc 만 잰다.</b> 그 규칙이 「값」에 걸린 것이고, 2026-11-08 첫 측정에서 클래스·메서드까지 세니
 * 84 로 {@code D2} 행 수(44)를 넘었다 — 산문 설명의 조문 인용까지 번호를 달라는 것은 규칙의 범위 밖이다(`Q394a` 실패 사다리).
 *
 * <p><b>기준선은 양방향 래칫이다.</b> {@link #EXEMPT} 는 파일마다 지금 「번호 없는 법 언급」 수다 — 실제가 그보다 많으면
 * 새 위반이고, 적으면 그 줄을 낮추라는 뜻이다(메운 것이 기준선에 남으면 다시 빠져도 안 걸린다). {@code Q394b} 가 0 으로 비웠다(2026-11-08).
 */
@DisplayName("법 언급 Javadoc 의 요건 번호")
class LawReferenceJavadocTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final Pattern JAVADOC = Pattern.compile("/\\*\\*.*?\\*/", Pattern.DOTALL);

    /** 법·하위 규범을 가리키는 단어. 늘리려면 여기와 {@code coding-rules.md} 를 같이 */
    private static final Pattern LAW = Pattern.compile(
            "상거래법|보호법|정보통신망법|국세기본법|부가가치세법|저작권법|약관규제법|민법|표시광고법|전자문서법"
                    + "|시행령|시행규칙|제\\d+조|고시|지침");

    private static final Pattern REQUIREMENT = Pattern.compile("\\bR\\d+\\b");

    /** Javadoc 뒤 첫 코드 줄이 필드다 — 이름 뒤가 {@code =} 나 {@code ;} */
    private static final Pattern FIELD = Pattern.compile(
            "^\\s*(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*[\\w.<>\\[\\]?, ]+\\s+\\w+\\s*[=;].*");

    /** Javadoc 뒤 첫 코드 줄이 열거 상수다 — 대문자 이름 뒤가 괄호·쉼표·쌍반점·중괄호·줄 끝 */
    private static final Pattern ENUM_CONSTANT = Pattern.compile("^\\s*[A-Z][A-Z0-9_]*\\s*(?:[(,;{].*)?$");

    /** 파일 이름 → 지금 「번호 없는 법 언급 Javadoc」 수. 첫 측정 30건·15파일을 {@code Q394b} 가 같은 날 메워 비었다 — 새 예외는 이유와 함께 */
    private static final Map<String, Integer> EXEMPT = Map.of();

    @Test
    @DisplayName("법 단어를 품은 main Javadoc 은 R번호를 든다 — 기준선의 수와 실제가 같다")
    void lawMentioningJavadocCarriesRequirementNumber() {
        Map<String, Integer> missing = new TreeMap<>();
        int lawMentions = 0;
        for (Path file : javaFiles()) {
            String source = read(file);
            Matcher javadoc = JAVADOC.matcher(source);
            while (javadoc.find()) {
                String block = javadoc.group();
                if (!LAW.matcher(block).find() || !isValueDeclaration(nextCodeLine(source, javadoc.end()))) {
                    continue;
                }
                lawMentions++;
                if (!REQUIREMENT.matcher(block).find()) {
                    missing.merge(file.getFileName().toString(), 1, Integer::sum);
                }
            }
        }

        assertThat(lawMentions).as("법 언급 Javadoc 을 하나도 못 읽었다 — 정규식이 상하면 조용히 초록이 된다").isPositive();
        assertThat(missing)
                .as("법 단어를 품은 Javadoc 에 `D2` 의 R번호가 없다(파일 → 수). 늘었으면 번호를 적고,"
                        + " 줄었으면 EXEMPT 의 그 줄을 낮춘다(coding-rules.md 「법에서 온 값에는 요건 번호를 적는다」)")
                .isEqualTo(EXEMPT);
    }

    /** Javadoc 뒤의 첫 코드 줄. 빈 줄과 애너테이션 줄은 건너뛴다 */
    private static String nextCodeLine(String source, int from) {
        for (String line : source.substring(from).split("\n", 20)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("@")) {
                return line;
            }
        }
        return "";
    }

    private static boolean isValueDeclaration(String line) {
        return FIELD.matcher(line).matches() || ENUM_CONSTANT.matcher(line).matches();
    }

    private static Iterable<Path> javaFiles() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스를 못 읽었다: " + MAIN.toAbsolutePath(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(file + " 를 못 읽었다", e);
        }
    }
}
