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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 지표 이름·태그 키·노출 목록을 문서 규칙과 대조한다(`Q226`, {@code quality-gates.md} 「규칙 원장」 ⑤).
 *
 * <p>{@code observability-rules.md} 「이름과 태그」 — 이름은 {@code shop.<표>.<무엇>}(자원 토막은 표 이름, `Q82`), 태그는 닫힌 목록.
 * {@code security-baseline.md} 「노출면」 — 액추에이터 노출 목록은 그 표의 칸과 같다. 셋 다 막는 것이 없었다.
 *
 * <h2>실행 중인 레지스트리가 아니라 소스 글자로 잰다</h2>
 *
 * <p>미터 넷 중 셋({@code shop.permission.decide}·{@code shop.error.raised}·{@code shop.batch_run.finished})이
 * <b>호출 자리에서</b> {@code builder(…).register(…)} 로 등록된다. 시험 컨텍스트에서 그 길이 안 불리면 레지스트리에 없어서
 * 레지스트리로 재면 <b>빠진 것을 모르고 초록</b>이 된다. {@code Counter|Timer|Gauge…builder("…")} 글자는 빠짐없이 보인다.
 * 표 이름은 마이그레이션의 {@code create table} 에서 걷는다 — 빠른 레인이라 DB 에 못 묻는다.
 */
@DisplayName("지표 이름·태그·노출 목록")
class MetricNameTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");
    private static final Path APPLICATION = Path.of("src", "main", "resources", "application.yml");
    private static final Path BASELINE = Path.of("..", "doc", "reference", "security-baseline.md");

    /** 태그 키의 닫힌 목록({@code observability-rules.md} 「이름과 태그」). 늘리려면 그 문서부터 */
    private static final Set<String> TAG_KEYS = Set.of("action", "allowed", "code", "name", "resource", "status");

    /** 표가 아닌데 자원 토막으로 인정하는 것. {@code shop.error.raised} 가 오류 종류를 센다 */
    private static final Set<String> NON_TABLE_SEGMENTS = Set.of("error");

    private static final Pattern METER =
            Pattern.compile("(?:Counter|Timer|Gauge|DistributionSummary|LongTaskTimer)\\.builder\\(\"([^\"]+)\"");
    private static final Pattern TAG = Pattern.compile("\\.tag\\(\"([^\"]+)\"");
    private static final Pattern NAME = Pattern.compile("shop\\.([a-z_]+)\\.[a-z_]+");
    private static final Pattern CREATE_TABLE = Pattern.compile("(?i)create table (?:if not exists )?\"?([a-z_]+)");
    private static final Pattern EXPOSURE_ROW = Pattern.compile("^\\| 액추에이터 노출 목록 \\| ([^|]+) \\|");
    private static final Pattern CODE = Pattern.compile("`([a-z]+)`");

    @Test
    @DisplayName("지표 이름이 shop.<표>.<무엇> 이다")
    void meterNamesUseTableSegments() {
        Set<String> tables = tables();
        List<String> names = meterSources().stream().flatMap(text -> all(METER, text).stream()).toList();
        assertThat(names).as("미터를 0개 찾았다 — builder 꼴이 바뀌었으면 이 시험을 넓힌다").isNotEmpty();

        List<String> bad = new ArrayList<>();
        for (String name : names) {
            Matcher m = NAME.matcher(name);
            if (!m.matches() || !(tables.contains(m.group(1)) || NON_TABLE_SEGMENTS.contains(m.group(1)))) {
                bad.add(name);
            }
        }
        assertThat(bad)
                .as("shop.<표>.<무엇> 이 아닌 지표 이름 — 자원 토막은 표 이름(단수)이다(observability-rules.md 「이름과 태그」, `Q82`)")
                .isEmpty();
    }

    @Test
    @DisplayName("지표 태그 키가 닫힌 목록 안이다")
    void tagKeysAreClosed() {
        Set<String> keys = new TreeSet<>();
        meterSources().forEach(text -> keys.addAll(all(TAG, text)));
        assertThat(keys).as("태그를 0개 찾았다 — tag(\"…\") 꼴이 바뀌었으면 이 시험을 넓힌다").isNotEmpty();
        assertThat(keys)
                .as("목록 밖 태그 키 — 열린 값을 태그로 달면 시계열이 그 값 수만큼 갈라지고 개인정보가 샌다(observability-rules.md)")
                .isSubsetOf(TAG_KEYS);
    }

    @Test
    @DisplayName("액추에이터 노출 목록이 security-baseline 「노출면」 표의 칸과 같다")
    void exposureMatchesBaseline() {
        Set<String> configured = new TreeSet<>();
        for (String line : read(APPLICATION)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("include:")) {
                for (String part : trimmed.substring("include:".length()).split(",")) {
                    configured.add(part.trim());
                }
            }
        }
        Set<String> documented = new TreeSet<>();
        for (String line : read(BASELINE)) {
            Matcher row = EXPOSURE_ROW.matcher(line);
            if (row.find()) {
                documented.addAll(all(CODE, row.group(1)));
            }
        }
        assertThat(configured).as("application.yml 의 exposure.include 를 못 읽었다").isNotEmpty();
        assertThat(documented).as("security-baseline.md 「노출면」 표의 액추에이터 행을 못 읽었다").isNotEmpty();
        assertThat(configured)
                .as("노출 목록과 문서가 갈렸다 — 늘릴 때마다 그것이 무엇을 흘리는지 보고 문서를 같이 고친다(security-baseline.md 「노출면」)")
                .isEqualTo(documented);
    }

    /** 미터를 만드는 파일의 본문만 — {@code .tag("…")} 가 다른 자리(로그 등)에서 잡히지 않게 */
    private static List<String> meterSources() {
        try (Stream<Path> files = Files.walk(MAIN)) {
            List<String> texts = files.filter(path -> path.toString().endsWith(".java"))
                    .map(MetricNameTest::readText)
                    .filter(text -> METER.matcher(text).find())
                    .toList();
            assertThat(texts).as("미터를 만드는 main 소스를 0개 읽었다: %s", MAIN.toAbsolutePath()).isNotEmpty();
            return texts;
        } catch (IOException e) {
            throw new UncheckedIOException("main 소스를 못 읽었다", e);
        }
    }

    private static Set<String> tables() {
        Set<String> tables = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            files.filter(path -> path.toString().endsWith(".sql"))
                    .forEach(path -> tables.addAll(all(CREATE_TABLE, readText(path))));
        } catch (IOException e) {
            throw new UncheckedIOException("마이그레이션을 못 읽었다", e);
        }
        assertThat(tables).as("create table 을 0개 읽었다: %s", MIGRATIONS.toAbsolutePath()).isNotEmpty();
        return tables;
    }

    private static List<String> all(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.group(1).toLowerCase());
        }
        return found;
    }

    private static List<String> read(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path.toAbsolutePath() + " 를 못 읽었다", e);
        }
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(path + " 를 못 읽었다", e);
        }
    }
}
