package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.projectshop.shop.PostgresTestBase;

/**
 * 문서와 주석이 부르는 이름이 실재하나(`Q83`).
 *
 * <p><b>한 곳을 바꾸고 같은 것을 부르는 다른 곳을 안 따라가는 사고가 하루에 세 번 났다</b>(2026-09-17):
 * 응답에 애노테이션을 걸고 같은 record 를 쓰는 다른 화면을 안 고쳤고, 컬럼을 {@code read_only} →
 * {@code is_read_only} 로 바꾸고 그 이름을 부르는 문서 둘을 안 고쳤고, 없는 테스트 클래스를 주석이 가리켰다.
 *
 * <p><b>코드와 테스트는 이미 걸린다</b> — 컴파일과 빨간 테스트가 잡는다.
 * <b>안 걸리는 층이 문서와 주석</b>이고, 거기서 틀린 이름은 다음 사람을 그대로 틀린 자리로 보낸다.
 *
 * <p><b>표 이름으로 닻을 내린다.</b> {@code x.y} 꼴을 다 잡으면 {@code api.ts}·{@code github.ref} 까지
 * 백스물여덟이 걸린다 — <b>{@code x} 가 실재하는 표일 때만</b> 보면 아흔일곱으로 줄고 잡음이 거의 없다.
 * 그래도 남는 것이 있어서({@code role.granted} 는 감사 사건 이름이다) 통과 목록을 근거와 함께 둔다.
 */
@DisplayName("문서가 부르는 이름")
class IdentifierReferenceTest extends PostgresTestBase {

    private static final List<Path> DOC_ROOTS = List.of(
            Path.of("..", "doc", "reference"),
            Path.of("src", "main", "java"));

    /** 문서의 백틱과 javadoc 의 {@code ...} 를 같은 것으로 본다. */
    private static final Pattern BACKTICK = Pattern.compile("`([^`\\s]+)`");
    private static final Pattern CODE_TAG = Pattern.compile("\\{@code ([^}\\s]+)}");

    /** {@code 표.컬럼} 꼴 */
    private static final Pattern COLUMN = Pattern.compile("^([a-z][a-z0-9_]*)\\.([a-z][a-z0-9_]*)$");
    /** 테스트 클래스 이름. 이 저장소의 테스트는 전부 {@code ...Test} 로 끝난다 */
    private static final Pattern TEST_CLASS = Pattern.compile("^([A-Z][A-Za-z0-9]*Test)$");
    /**
     * {@code XTest.y} — 멤버는 한글도 받는다(ArchUnit 규칙 이름이 한글이다) · 끝의 {@code ()} 는 떼고 본다(마무리 62차 독립 리뷰).
     * 줄임표로 잘린 이름처럼 이 꼴이 아닌 것은 안 문다.
     */
    private static final Pattern TEST_MEMBER =
            Pattern.compile("^([A-Z][A-Za-z0-9]*Test)\\.([\\p{L}_][\\p{L}\\p{N}_]*)(?:\\(\\))?$");

    /**
     * 표 이름으로 시작하지만 컬럼이 아닌 것과 그 근거.
     *
     * <p><b>근거 없이 이름만 넣지 않는다.</b> 근거 칸이 없으면 이 목록이
     * <b>틀린 이름을 덮을 자리</b>가 된다.
     */
    private static final Map<String, String> NOT_A_COLUMN = Map.of(
            "role.granted", "감사 사건 이름이다(`AuditLog` 의 점 표기). 표와 무관하다",
            "permission.denied", "〃",
            "seller_member.invited", "〃");

    @Autowired
    private JdbcClient jdbc;

    @Test
    @DisplayName("부르는 컬럼이 실재한다")
    void referencedColumnsExist() {
        Set<String> columns = realColumns();
        Set<String> tables = columns.stream()
                .map(name -> name.substring(0, name.indexOf('.')))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        List<String> missing = references().stream()
                .filter(token -> {
                    Matcher matcher = COLUMN.matcher(token);
                    return matcher.matches() && tables.contains(matcher.group(1));
                })
                .filter(token -> !columns.contains(token))
                .filter(token -> !NOT_A_COLUMN.containsKey(token))
                .distinct()
                .sorted()
                .toList();

        assertThat(missing)
                .as("문서·주석이 없는 컬럼을 부른다. 이름을 고치거나, 컬럼이 아니면 "
                        + "NOT_A_COLUMN 에 근거와 함께 적는다")
                .isEmpty();
    }

    @Test
    @DisplayName("부르는 테스트 클래스가 실재한다")
    void referencedTestClassesExist() {
        Set<String> existing = testClassNames();

        List<String> missing = references().stream()
                .filter(token -> TEST_CLASS.matcher(token).matches())
                .filter(token -> !existing.contains(token))
                .distinct()
                .sorted()
                .toList();

        assertThat(missing)
                .as("문서·주석이 없는 테스트를 가리킨다. 이름을 고치거나 그 테스트를 세운다")
                .isEmpty();
    }

    /**
     * 부르는 시험 멤버가 실재한다(`Q268`). {@code PermissionCacheTest.secondCallHitsCache} 처럼 클래스 뒤에 점을 찍어
     * <b>메서드·중첩 클래스·상수</b>를 부르는 토큰이다. 위 시험은 {@code …Test} 로 <b>끝나는</b> 토큰만 봐서
     * 멤버 이름이 바뀌어도 아무것도 안 잡았다 — PR #99 리뷰가 옛 메서드 이름을 든 문장을 세 판 연달아 짚은 그 모양이다.
     *
     * <p><b>소스 글자로 잰다</b> — 그 클래스 파일에 {@code 이름(}·{@code class 이름}·{@code 이름 =}·{@code 이름;} 중
     * 하나가 있어야 한다. 리플렉션은 중첩 클래스의 메서드까지 따라가야 하고 쓰는 쪽의 표기가 그것을 안 가른다.
     * <b>파일 이름({@code XTest.java})은 뺀다</b> — 멤버가 아니다. 클래스가 없는 토큰은 위 시험의 몫이라 건너뛴다.
     */
    @Test
    @DisplayName("부르는 시험 멤버가 실재한다")
    void referencedTestMembersExist() {
        Map<String, Path> files = testClassFiles();
        List<String> missing = references().stream()
                .map(TEST_MEMBER::matcher)
                .filter(Matcher::matches)
                .filter(matcher -> !matcher.group(2).equals("java"))
                .filter(matcher -> files.containsKey(matcher.group(1)))
                .filter(matcher -> !declares(readString(files.get(matcher.group(1))), matcher.group(2)))
                .map(matcher -> matcher.group(1) + "." + matcher.group(2))
                .distinct()
                .sorted()
                .toList();
        assertThat(missing)
                .as("문서·주석이 시험 클래스에 없는 멤버를 부른다. 지금 이름으로 고치거나 그 문장을 지운다")
                .isEmpty();
    }

    /** 그 소스가 이 이름을 메서드·중첩 클래스·필드로 선언하나. 주석에서만 나와도 통과한다 — 부르는 이름이 글자로 남아 있다는 뜻이라 */
    private static boolean declares(String source, String member) {
        // `\b` 는 한글을 단어 글자로 안 봐서 한글 이름 앞에서 안 선다 — 글자·숫자·밑줄이 아닌 것으로 경계를 직접 건다.
        String name = "(?<![\\p{L}\\p{N}_])" + Pattern.quote(member);
        String end = "(?![\\p{L}\\p{N}_])";
        return Pattern.compile(name + "\\s*\\(|\\bclass\\s+" + Pattern.quote(member) + end + "|" + name + "\\s*[=;]")
                .matcher(source).find();
    }

    /** 시험 클래스 이름 → 파일. 백엔드 시험만이다 — 화면 시험은 {@code x.test.tsx} 라 {@code XTest.y} 꼴이 아니다 */
    private static Map<String, Path> testClassFiles() {
        Map<String, Path> files = new java.util.TreeMap<>();
        for (Path file : filesUnder(Path.of("src", "test", "java"))) {
            String name = file.getFileName().toString();
            if (name.endsWith("Test.java")) {
                files.put(name.substring(0, name.length() - ".java".length()), file);
            }
        }
        return files;
    }

    /** 문서와 주석이 부르는 이름 전부. 중복은 아래에서 걷는다. */
    private static List<String> references() {
        List<String> found = new ArrayList<>();
        for (Path root : DOC_ROOTS) {
            for (Path file : filesUnder(root)) {
                String text = readString(file);
                collect(BACKTICK.matcher(text), found);
                collect(CODE_TAG.matcher(text), found);
            }
        }
        return found;
    }

    private static void collect(Matcher matcher, List<String> found) {
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
    }

    private static List<Path> filesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> {
                String name = path.toString();
                return name.endsWith(".md") || name.endsWith(".java");
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("못 읽었다: " + root.toAbsolutePath(), e);
        }
    }

    /** {@code 표.컬럼} 전부. <b>도는 스키마에서 읽는다</b> — 마이그레이션 파일이 아니라 결과가 답이다. */
    private Set<String> realColumns() {
        return new TreeSet<>(jdbc.sql("""
                        select c.relname || '.' || a.attname
                          from pg_class c
                          join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
                          join pg_attribute a on a.attrelid = c.oid
                                             and a.attnum > 0 and not a.attisdropped
                         where c.relkind = 'r'
                        """)
                .query(String.class)
                .list());
    }

    private static Set<String> testClassNames() {
        Set<String> names = new TreeSet<>();
        for (Path root : List.of(Path.of("src", "test", "java"), Path.of("..", "frontend", "src"))) {
            for (Path file : filesUnder(root)) {
                String name = file.getFileName().toString();
                names.add(name.substring(0, name.indexOf('.')));
            }
        }
        return names;
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("못 읽었다: " + path.toAbsolutePath(), e);
        }
    }
}
