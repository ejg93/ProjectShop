package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 요건표({@code commerce-compliance.md}, {@code D2})의 「강제 지점」 칸이 가리키는 이름이 실물인지 본다({@code Q33}).
 *
 * <p><b>법 층의 대조는 방향이 반대다</b>({@code coding-rules.md} 「지켜지는지는 위에서 내려가며 확인한다」).
 * 코딩 규약은 코드를 훑으면 어긋난 것이 보이지만, 법 요건은 <b>코드에 없어서</b> 요건표에서 내려가야 한다.
 * 그래서 그 표가 「검사할 수 있는 이름」을 적는데, <b>그 이름이 실물인지는 아무도 안 봤다</b> —
 * {@code req-coverage.sh} 는 반대 방향(테스트가 R 을 언급하나)만 센다. 제약 이름을 마이그레이션에서 바꾸면
 * 표는 그대로 낡고 「R4 는 이 제약이 막는다」가 거짓인 채로 남는다.
 *
 * <p><b>백틱 토큰을 넷으로 가른다.</b> 넷 밖(경로·청크 번호·설명)은 안 잰다.
 * <table>
 *   <caption>토큰 종류와 실물</caption>
 *   <tr><th>모양</th><th>실물</th></tr>
 *   <tr><td>{@code *_check}·{@code *_key}·{@code *_idx}</td><td>{@code pg_constraint} ∪ {@code pg_indexes} — 도는 스키마</td></tr>
 *   <tr><td>{@code Class.java}</td><td>그 파일이 {@code src/main/java}·{@code src/test/java} 에 있나</td></tr>
 *   <tr><td>{@code Class.method}</td><td>{@code Class.java}(중첩이면 그것을 선언한 파일) 안에 그 이름이 있나</td></tr>
 *   <tr><td>{@code Vnn}</td><td>{@code db/migration/Vnn__*.sql}</td></tr>
 * </table>
 *
 * <p><b>{@code Class.method} 는 파일 단위로 찾는다.</b> 중첩 클래스({@code Decision.visibleFieldGroups})는 그것을
 * 선언한 파일에서 찾는데 <b>같은 이름의 중첩 클래스가 둘이다</b>({@code PermissionEvaluator.Decision}·
 * {@code ReturnRequestService.Decision}) — 후보 전부를 본다. 테스트 클래스({@code OrderActionTest.copyableMediaDoesNotBlock})도
 * 실물이다. 같은 이름의 메서드가 딴 클래스에 있으면 못 가른다 — <b>미탐 쪽이지 오탐은 없다.</b> 파일이 없으면 그건 잡는다.
 *
 * <p><b>접미사를 틀리면 못 잰다.</b> {@code product_withdrawal_reason_chek} 은 네 모양 어디에도 안 맞아 조용히 빠진다 —
 * 잡는 것은 줄기가 틀린 것({@code product_withdrawal_reson_check})이다. 통째로 빠지는 것은 {@link #tableStillHasTokens} 가
 * 수로 잰다. 부순 증거를 만들 때 이 함정을 실제로 밟았다({@code Q33}).
 *
 * <p><b>저장소 밖 파일을 읽으므로 {@code build.gradle.kts} 의 {@code integrationTest} 입력에 요건표를 신고했다.</b>
 * 안 하면 표만 고친 청크에서 {@code UP-TO-DATE} 로 건너뛴다({@code Q25} 가 잡는 함정).
 */
class RequirementEnforcementTest extends PostgresTestBase {

    /** gradle 의 cwd 가 {@code backend} 다 — {@code LengthConstraintTest} 와 같은 전제 */
    private static final Path REQUIREMENTS = Path.of("..", "doc", "reference", "commerce-compliance.md");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final List<Path> SOURCE_ROOTS = List.of(
            Path.of("src", "main", "java"), Path.of("src", "test", "java"));

    private static final Pattern REQUIREMENT_ROW = Pattern.compile("^\\| *(R\\d+) *\\|");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]+)`");
    private static final Pattern CONSTRAINT = Pattern.compile("^[a-z][a-z0-9_]*_(check|key|idx)$");
    /** {@code Password.java} 처럼 파일을 통째로 가리키는 것. {@code CODE} 보다 먼저 본다 — 아니면 {@code java} 가 메서드로 읽힌다 */
    private static final Pattern FILE = Pattern.compile("^[A-Z]\\w+\\.java$");
    private static final Pattern CODE = Pattern.compile("^([A-Z]\\w+)\\.([a-z]\\w*)$");
    private static final Pattern MIGRATION = Pattern.compile("^V\\d+$");

    @Autowired
    private JdbcClient jdbc;

    /** 요건 행마다 넷째 칸(강제 지점)의 백틱 토큰. {@code (R번호, 토큰)} */
    static Stream<Arguments> tokens() throws IOException {
        List<Arguments> out = new ArrayList<>();
        for (String line : Files.readAllLines(REQUIREMENTS)) {
            Matcher row = REQUIREMENT_ROW.matcher(line);
            if (!row.find()) {
                continue;
            }
            String[] cells = line.split("\\|");
            // cells[0] 은 첫 `|` 앞의 빈 문자열이라 넷째 칸이 [4] 다
            if (cells.length < 5) {
                continue;
            }
            Matcher token = BACKTICK.matcher(cells[4]);
            while (token.find()) {
                String name = token.group(1).trim();
                if (CONSTRAINT.matcher(name).matches() || FILE.matcher(name).matches()
                        || CODE.matcher(name).matches() || MIGRATION.matcher(name).matches()) {
                    out.add(Arguments.of(row.group(1), name));
                }
            }
        }
        return out.stream();
    }

    @Test
    @DisplayName("요건표에서 재는 토큰이 하나라도 있다 — 없으면 표 모양이 바뀐 것이다")
    void tableStillHasTokens() throws IOException {
        assertThat(tokens().count())
                .as("2026-09-13 에 85개였다. 0 이면 표 헤더나 칸 순서가 바뀌어 아무것도 안 재고 있다")
                .isGreaterThan(30);
    }

    @ParameterizedTest(name = "{0} — {1}")
    @MethodSource("tokens")
    @DisplayName("강제 지점 칸의 이름이 실물이다")
    void enforcementPointExists(String requirement, String name) throws IOException {
        if (CONSTRAINT.matcher(name).matches()) {
            assertThat(constraintExists(name))
                    .as("%s 가 가리키는 제약·인덱스 %s 가 도는 스키마에 없다 — 마이그레이션에서 이름이 바뀌었으면 표도 같이", requirement, name)
                    .isTrue();
        } else if (FILE.matcher(name).matches()) {
            assertThat(findFiles(name))
                    .as("%s 가 가리키는 %s 가 main·test 소스에 없다", requirement, name)
                    .isNotEmpty();
        } else if (MIGRATION.matcher(name).matches()) {
            assertThat(migrationExists(name))
                    .as("%s 가 가리키는 %s 파일이 db/migration 에 없다", requirement, name)
                    .isTrue();
        } else {
            Matcher code = CODE.matcher(name);
            assertThat(code.matches()).isTrue();
            assertThat(codeExists(code.group(1), code.group(2)))
                    .as("%s 가 가리키는 %s 가 main·test 소스에 없다 — 이름을 바꿨으면 표도 같이", requirement, name)
                    .isTrue();
        }
    }

    private boolean constraintExists(String name) {
        Integer n = jdbc.sql("""
                        select count(*) from (
                            select conname as name from pg_constraint
                            union all
                            select indexname from pg_indexes where schemaname = 'public'
                        ) named where name = :name
                        """)
                .param("name", name)
                .query(Integer.class)
                .single();
        return n > 0;
    }

    private static boolean migrationExists(String version) throws IOException {
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            return files.anyMatch(f -> f.getFileName().toString().startsWith(version + "__"));
        }
    }

    /**
     * {@code Class.java}(없으면 그 이름을 선언한 파일들) 안에 이름이 있나.
     *
     * <p>{@code method(} 만 보면 <b>record 컴포넌트를 놓친다</b> — {@code Decision.visibleFieldGroups} 는
     * {@code record Decision(…, List<String> visibleFieldGroups)} 라 선언에 여는 괄호가 없다. 그래서 뒤에
     * {@code (}·{@code ,}·{@code )} 중 하나가 오면 실물로 본다.
     */
    private static boolean codeExists(String className, String member) throws IOException {
        Pattern use = Pattern.compile("\\b" + Pattern.quote(member) + "\\s*[(,)]");
        List<Path> candidates = findFiles(className + ".java");
        if (candidates.isEmpty()) {
            candidates = findDeclaringFiles(className);
        }
        for (Path file : candidates) {
            if (use.matcher(Files.readString(file)).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> findFiles(String fileName) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(f -> f.getFileName().toString().equals(fileName)).forEach(out::add);
            }
        }
        return out;
    }

    /** 중첩 타입 선언({@code record X}·{@code class X}·{@code interface X}·{@code enum X})을 든 파일 전부 */
    private static List<Path> findDeclaringFiles(String nested) throws IOException {
        Pattern declaration = Pattern.compile("\\b(class|record|interface|enum)\\s+" + Pattern.quote(nested) + "\\b");
        List<Path> out = new ArrayList<>();
        for (Path root : SOURCE_ROOTS) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    if (declaration.matcher(Files.readString(f)).find()) {
                        out.add(f);
                    }
                }
            }
        }
        return out;
    }
}
