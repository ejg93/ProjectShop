package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code coding-rules.md} 「SQL」의 글자로 잡히는 조항 셋을 {@code main} 소스에 건다(`Q30`).
 *
 * <p><b>문서에만 있던 규칙이다.</b> 「{@code select *} 를 안 쓴다」와 「값이 결합되는 자리는
 * 둘 말고 없다」가 안 지켜져도 빌드가 초록이었다. 2026-09-13 에 세니 위반이 0이라
 * <b>지금 세우면 새 위반이 들어오는 순간 빨개진다.</b>
 *
 * <h2>왜 {@link ArchitectureTest} 가 아니라 글자를 읽나</h2>
 *
 * <p>{@code select *} 는 자바 코드에서 <b>문자열 안에 든 글자</b>라 컴파일된 클래스에 구조로
 * 남지 않는다. ArchUnit 이 볼 수 있는 것이 아니다. 대신 주석 안의 예시 문장까지 걸리므로
 * ({@code ListQuery} 의 javadoc 에 {@code " order by " + sort} 가 세 줄 있다)
 * 재기 전에 주석을 걷어낸다.
 *
 * <h2>{@code build.gradle.kts} 신고가 왜 필요 없나</h2>
 *
 * <p>읽는 것이 {@code src/main/java} 라 <b>main 이 바뀌면 클래스가 바뀌어 {@code test} 가
 * 다시 돈다.</b> {@code Q25} 가 잡으려는 {@code UP-TO-DATE} 구멍은 저장소 밖 파일을 읽는
 * 테스트의 것이고 이 테스트는 거기 해당하지 않는다.
 *
 * <p><b>그 전제에 좁은 틈이 하나 있다.</b> 마커는 주석이라 <b>바이트코드를 안 바꾸는 편집</b>이
 * 가능하다 — 마커만 지우거나 옮기면 클래스가 그대로라 이 테스트가 안 돌 수 있다.
 * 실제로는 마커를 건드리는 편집이 그 옆의 SQL 을 같이 건드리는 것이 보통이라 좁은 틈이고,
 * 넓어지면 {@code build.gradle.kts} 에 이 소스 묶음을 신고하는 쪽으로 간다.
 *
 * <h2>예외를 어떻게 다나 — 마커</h2>
 *
 * <p>값이 결합되는 자리를 새로 만들려면 <b>그 줄 바로 위에</b> 마커를 단다.
 *
 * <pre>{@code
 * // sql-exception: set-statement — SET 은 값 바인딩이 안 되는 자리다.
 * jdbc.sql("set local lock_timeout = " + LOCK_TIMEOUT_MS).update();
 * }</pre>
 *
 * <p><b>마커를 다는 것으로 혼자 끝나지 않게 만든 것이 이 설계의 요지다.</b> 주석 한 줄로
 * 검사를 끄는 수단은 악용될 수 있어서, 네 가지가 같이 걸린다.
 *
 * <table>
 *   <caption>마커가 새는 길과 막는 자리</caption>
 *   <tr><th>새는 길</th><th>막는 것</th></tr>
 *   <tr><td>위험한 결합을 마커로 덮는다</td>
 *       <td><b>사유가 닫힌 목록이다</b>({@link #SUPPRESSIBLE}). 새 사유를 쓰려면 이 파일의
 *           상수를 고쳐야 하고 그 줄이 diff 에 뜬다. 그리고 <b>진짜 주입은 CodeQL 이 따로
 *           본다</b>(`2e-5` 가 {@code JdbcClient.sql} 을 싱크로 등록했다) — 마커는 이
 *           테스트만 끄고 그쪽은 못 끈다</td></tr>
 *   <tr><td>걸릴까 봐 미리 뿌린다</td>
 *       <td><b>죽은 마커가 위반이다.</b> 마커 다음 줄에 실제 위반이 없으면 빨갛다.
 *           위반을 없앤 뒤 마커만 남은 것도 같이 잡힌다</td></tr>
 *   <tr><td>파일 전체를 덮는다</td>
 *       <td><b>바로 윗줄에만 걸린다.</b> 파일 머리에 달아도 아무것도 안 꺼진다</td></tr>
 *   <tr><td>조용히 개수가 는다</td>
 *       <td><b>총 개수를 박았다</b>({@link #MARKER_COUNT}). 하나 늘면 빨갛고,
 *           고치려면 숫자를 손대야 해서 리뷰에 보인다</td></tr>
 * </table>
 *
 * <p><b>{@code select *} 는 어떤 마커로도 못 끈다.</b> {@code coding-rules.md} 가 예외를
 * 둘만 인정했고 그 둘이 다 식별자 결합이라, 사유 목록에 {@code select *} 가 없다.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <p>결합이 두 줄에 걸쳐 있어도 잡지만({@code \s} 가 개행을 문다) <b>마커는 줄 단위</b>라
 * 위반의 첫 줄 바로 위에 있어야 한다. 텍스트 블록 안의 {@code \"""} 이스케이프는 안 센다 —
 * 지금 {@code main} 에 없고, 생기면 그 파일을 잘못 걷어 오탐이 난다.
 */
class SqlTextTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    /** 마커가 끌 수 있는 규칙과 그 근거. {@code coding-rules.md} 「SQL」이 인정한 예외 둘이다. */
    private static final Map<String, String> SUPPRESSIBLE = Map.of(
            "identifier", "정렬 컬럼은 식별자라 바인딩이 안 된다",
            "set-statement", "SET 은 값 바인딩이 안 되는 자리다");

    /**
     * 마커 총 개수를 박는다. <b>내리기만 한다</b> — 늘리는 것은 예외를 하나 더 인정한다는 뜻이라
     * 이 줄이 diff 에 떠야 한다.
     */
    private static final int MARKER_COUNT = 1;

    private static final Pattern MARKER =
            Pattern.compile("//\\s*sql-exception:\\s*([a-z-]+)\\s*(.*)");

    private static final Pattern SELECT_STAR = Pattern.compile("(?i)select\\s+\\*");
    /**
     * <b>{@code \s*+} 가 소유 수량자인 것이 핵심이다.</b> 그냥 {@code \s*} 면 룩어헤드가 실패했을 때
     * 공백을 0개로 되돌려 다시 맞추고, 그러면 {@code " + orderBy.clause()} 의 공백 자리에서
     * 룩어헤드가 통과해 <b>멀쩡한 다섯 자리가 전부 위반으로 뜬다</b>(이 청크에서 실제로 그랬다).
     */
    private static final Pattern ORDER_BY_CONCAT =
            Pattern.compile("(?i)order by\\s*\"\\s*\\+\\s*+(?!orderBy\\.clause\\(\\))");
    private static final Pattern SET_LOCAL = Pattern.compile("(?i)set\\s+local");

    private static final List<Source> SOURCES = readSources();

    @Test
    @DisplayName("main 소스에 select * 가 없다")
    void selectStarIsAbsent() {
        assertThat(violations(SELECT_STAR, null))
                .as("컬럼이 늘면 응답이 조용히 바뀐다 (coding-rules.md 「SQL」)."
                        + " 이 규칙은 마커로 못 끈다 — 인정된 예외 둘이 다 식별자 결합이다")
                .isEmpty();
    }

    @Test
    @DisplayName("정렬 컬럼은 OrderBy 를 거쳐서만 SQL 에 붙는다")
    void orderByGoesThroughOrderBy() {
        assertThat(violations(ORDER_BY_CONCAT, "identifier"))
                .as("요청 문자열이 order by 에 그대로 붙으면 인젝션이다 (D14, coding-rules.md 「SQL」)."
                        + " 허용 목록을 거친 ListQuery.orderBy.clause() 만 결합할 수 있다")
                .isEmpty();
    }

    @Test
    @DisplayName("set local 결합은 마커를 단 자리에만 있다")
    void setLocalCarriesMarker() {
        assertThat(violations(SET_LOCAL, "set-statement"))
                .as("SET 은 바인딩이 안 되는 자리라 결합이 강제된다 (K1, coding-rules.md 「SQL」)."
                        + " 결합해도 되는 것은 값을 우리가 정할 때뿐이고 그 근거를 마커가 든다")
                .isEmpty();
    }

    @Test
    @DisplayName("마커는 알려진 사유를 달고 실제 위반 위에 붙어 있다")
    void markersAreKnownAndAlive() {
        List<String> broken = new ArrayList<>();
        for (Source source : SOURCES) {
            for (Marker marker : source.markers()) {
                if (!SUPPRESSIBLE.containsKey(marker.id())) {
                    broken.add(marker.where() + " — 모르는 사유 '" + marker.id()
                            + "'. 쓸 수 있는 것: " + SUPPRESSIBLE.keySet());
                    continue;
                }
                if (marker.reason().isBlank()) {
                    broken.add(marker.where() + " — 사유 설명이 비었다."
                            + " 왜 바인딩이 안 되고 값을 누가 정하는지를 같이 적는다");
                    continue;
                }
                if (!source.violatesAt(marker.line() + 1, ruleOf(marker.id()))) {
                    broken.add(marker.where() + " — 다음 줄에 위반이 없다."
                            + " 덮을 것이 없는 마커는 지운다");
                }
            }
        }
        assertThat(broken)
                .as("마커는 주석 한 줄로 검사를 끄는 수단이라, 미리 뿌리거나 남겨 두면"
                        + " 무엇을 덮고 있는지 아무도 모르게 된다")
                .isEmpty();
    }

    /**
     * 「테스트」 — 마이그레이션이 심어 둔 표에 <b>과거 방향 상대 시각</b>으로 행을 넣지 않는다(`Q146`).
     *
     * <p><b>재사용 컨테이너의 워커 DB 가 며칠 산다.</b> 마이그레이션이 심은 행의 시각은 그 DB 를
     * 처음 만든 날 굳는데, 시험이 {@code now() - interval '1 day'} 로 「더 이른 행」을 만들면
     * 며칠 지난 DB 에서 그 값이 <b>오히려 더 새것</b>이 된다. 「더 이른」이 「더 늦은」이 되고
     * 시험이 스스로 뒤집힌다 — 2026-09-21 에 워커 DB 55개 중 10개가 그 상태였다(`Q144`).
     *
     * <p><b>CI 는 구조적으로 못 본다.</b> 거기는 컨테이너가 매번 새것이라 둘 사이가 늘 0에 가깝다.
     * 로컬만 빨갛고 CI 는 초록이라 「로컬 탓」으로 읽기 쉽다 — 그날 그 오독을 두 번 했다.
     *
     * <p><b>미래 방향과 맨 {@code now()} 는 안 본다.</b> 낡은 DB 는 심긴 행을 더 <b>오래된</b>
     * 쪽으로만 밀기 때문에, 「지금 시행됨」이나 「아직 시행 전」을 주장하는 자리는 흔들리지 않는다.
     * 뒤집히는 것은 <b>심긴 행보다 이르다</b>고 주장하는 자리 하나뿐이다.
     *
     * <p>고치는 법은 기준을 데이터에서 뽑는 것이다 —
     * {@code min(effective_at) - interval '1 day' from policy_document}.
     */
    @Test
    @DisplayName("시험이 시드 표의 시각을 벽시계로 거슬러 만들지 않는다")
    void testsDoNotBackdateSeededRows() {
        List<String> violations = new ArrayList<>();
        Set<String> seeded = tablesSeededByMigrations();
        for (Path path : testSources()) {
            String text = JavaSourceText.withoutComments(readText(path));
            Matcher matcher = PAST_RELATIVE.matcher(text);
            while (matcher.find()) {
                String prefix = text.substring(0, matcher.start());
                String table = lastStatementTarget(prefix);
                if (table == null || !seeded.contains(table)) {
                    continue;
                }
                long line = prefix.chars().filter(c -> c == '\n').count() + 1;
                violations.add(path + ":" + line + " — " + table);
            }
        }
        assertThat(violations)
                .as("마이그레이션이 심은 행의 시각은 워커 DB 를 만든 날 굳는다."
                        + " 그보다 이르다고 주장하려면 기준을 벽시계가 아니라 그 표에서 뽑는다 (Q146)")
                .isEmpty();
    }

    /** 시각을 거슬러 잡는 SQL. 미래 방향({@code now() + interval})은 안 뒤집혀서 안 본다. */
    private static final Pattern PAST_RELATIVE =
            Pattern.compile("(?i)(?:now\\(\\)|current_timestamp)\\s*-\\s*interval");

    private static final Pattern STATEMENT_TARGET =
            Pattern.compile("(?i)(?:insert\\s+into|update)\\s+([a-z_]+)");

    private static final Pattern MIGRATION_INSERT =
            Pattern.compile("(?i)insert\\s+into\\s+([a-z_]+)");

    private static final Path TEST = Path.of("src", "test", "java");

    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

    /**
     * 상대 시각이 어느 표로 가나. <b>가장 가까운 앞쪽</b>의 {@code insert into}·{@code update} 를 문다.
     *
     * <p>SQL 이 문자열로 조립되면 그 짝이 어긋날 수 있다({@code OrderContractTest} 의
     * {@code insertPolicy} 가 시각만 인자로 받는다). 그래도 <b>두 표가 다 시드 표</b>라 판정은 같다 —
     * 어긋나서 통과하는 쪽이 아니라 어긋나서 잡히는 쪽이다.
     */
    private static String lastStatementTarget(String prefix) {
        Matcher matcher = STATEMENT_TARGET.matcher(prefix);
        String table = null;
        while (matcher.find()) {
            table = matcher.group(1).toLowerCase();
        }
        return table;
    }

    private static Set<String> tablesSeededByMigrations() {
        Set<String> tables = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MIGRATIONS)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                Matcher matcher = MIGRATION_INSERT.matcher(readText(path));
                while (matcher.find()) {
                    tables.add(matcher.group(1).toLowerCase());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션을 못 읽었다: " + MIGRATIONS.toAbsolutePath(), e);
        }
        return tables;
    }

    private static List<Path> testSources() {
        try (Stream<Path> files = Files.walk(TEST)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("테스트 소스를 못 읽었다: " + TEST.toAbsolutePath(), e);
        }
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(path + " 를 못 읽었다", e);
        }
    }

    @Test
    @DisplayName("SQL 예외 개수가 박힌 수와 같다")
    void markerCountIsPinned() {
        List<String> markers = SOURCES.stream()
                .flatMap(source -> source.markers().stream().map(Marker::where))
                .toList();
        assertThat(markers)
                .as("예외가 하나씩 늘면 어느 변경에서도 눈에 안 띈다."
                        + " 늘리려면 SqlTextTest.MARKER_COUNT 를 같이 고치고 그 줄을 리뷰에 올린다")
                .hasSize(MARKER_COUNT);
    }

    private static Pattern ruleOf(String markerId) {
        return "identifier".equals(markerId) ? ORDER_BY_CONCAT : SET_LOCAL;
    }

    /** 규칙 하나를 저장소 전체에 재고, {@code markerId} 마커가 바로 위에 붙은 자리는 뺀다. */
    private static List<String> violations(Pattern rule, String markerId) {
        List<String> found = new ArrayList<>();
        for (Source source : SOURCES) {
            for (int line : source.linesMatching(rule)) {
                if (markerId != null && source.hasMarker(line - 1, markerId)) {
                    continue;
                }
                found.add(source.path() + ":" + line);
            }
        }
        return found;
    }

    /**
     * 파일 하나. 주석을 걷어낸 본문과 원문 마커를 같이 든다.
     *
     * <p>걷어낸 본문은 <b>줄 구조를 그대로 둔다</b> — 줄을 지우면 번호가 밀려서 어디를
     * 고쳐야 하는지 못 짚는다({@code doc-lint.sh} 의 코드펜스 처리와 같은 이유다).
     */
    private record Source(Path path, String scrubbed, List<Marker> markers) {

        List<Integer> linesMatching(Pattern rule) {
            List<Integer> lines = new ArrayList<>();
            Matcher matcher = rule.matcher(scrubbed);
            while (matcher.find()) {
                lines.add(lineOf(matcher.start()));
            }
            return lines;
        }

        boolean violatesAt(int line, Pattern rule) {
            return linesMatching(rule).contains(line);
        }

        boolean hasMarker(int line, String id) {
            return markers.stream().anyMatch(m -> m.line() == line && m.id().equals(id));
        }

        private int lineOf(int offset) {
            int line = 1;
            for (int i = 0; i < offset; i++) {
                if (scrubbed.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }
    }

    private record Marker(Path path, int line, String id, String reason) {
        String where() {
            return path + ":" + line;
        }
    }

    private static List<Source> readSources() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .map(SqlTextTest::readSource)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스를 못 읽었다: " + MAIN.toAbsolutePath(), e);
        }
    }

    private static Source readSource(Path path) {
        String text;
        try {
            text = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(path + " 를 못 읽었다", e);
        }
        List<Marker> markers = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = MARKER.matcher(lines[i]);
            if (matcher.find()) {
                markers.add(new Marker(path, i + 1, matcher.group(1), matcher.group(2).trim()));
            }
        }
        return new Source(path, JavaSourceText.withoutComments(text), markers);
    }
}
