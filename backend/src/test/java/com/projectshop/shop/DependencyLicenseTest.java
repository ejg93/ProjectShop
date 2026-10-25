package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 선언한 의존성마다 라이선스를 판단한 기록이 있는가(`Q298`).
 *
 * <p>{@code external-references.md} 「의존성」·「도구」 표가 「새 의존성을 넣으면 이 표에 줄을 더한다」를
 * 문서로만 들고 있었고, 점검 W(2026-10-23)가 앱에 실리는 것 다섯과 선언 열넷이 빠진 것을 찾았다.
 * <b>판단한 적 없는 것과 판단해서 괜찮은 것은 다르다</b>(`D23`) — 여기서 재는 것은 <b>판단한 기록(행)이 있나</b>까지다.
 * 행의 라이선스가 맞는지, 실제로 판단했는지 베꼈는지는 못 가른다 — 그건 행을 쓰는 사람과 리뷰의 몫이다.
 *
 * <p><b>선언만 본다.</b> {@code build.gradle.kts} 의 설정 호출과 {@code plugins { id(…) }},
 * {@code frontend/package.json} 의 두 맵이다. 전이 의존은 표에 {@code (전이)} 로 적고 좌표를 안 단다 —
 * 그것까지 세면 Gradle 해석이 필요해서 빠른 레인에 못 둔다. 라이선스 값이 맞는지는 안 잰다 — 사람이 판단한 칸이다.
 */
@DisplayName("의존성 라이선스 표")
class DependencyLicenseTest {

    private static final Path BUILD = Path.of("build.gradle.kts");

    private static final Path PACKAGE = Path.of("..", "frontend", "package.json");

    private static final Path REFERENCES = Path.of("..", "doc", "reference", "external-references.md");

    /**
     * {@code implementation("group:artifact[:version]")} · {@code testImplementation(platform("…"))} · {@code mavenBom("…")} 꼴.
     * 설정 이름은 대소문자를 안 가린다 — {@code testAnnotationProcessor} 처럼 접두가 붙은 것도 잡는다(마무리 69차 독립 리뷰)
     */
    private static final Pattern GRADLE_CALL = Pattern.compile(
            "\\b(?:implementation|testImplementation|testRuntimeOnly|runtimeOnly|testCompileOnly|developmentOnly"
                    + "|compileOnly|api|annotationProcessor|testAnnotationProcessor|spotbugsPlugins|pitest|checkstyle"
                    // `errorprone` 은 `Q300` 이 더했다 — 없으면 `error_prone_core` 선언이 조용히 안 보였다(플러그인 id 만 잡혔다)
                    + "|errorprone|mavenBom)\\((?:platform\\()?\"([^\":]+):([^\":]+)");

    private static final Pattern PLUGIN_ID = Pattern.compile("\\bid\\(\"([^\"]+)\"\\)");

    /** 이 시험이 못 읽는 선언 꼴. 들어오면 빨갛게 해서 정규식을 먼저 넓히게 한다 */
    private static final Pattern UNREAD_FORM = Pattern.compile("\\b(?:alias|kotlin)\\(|\\blibs\\.");

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /** 표 한 줄. 좌표 열의 홑따옴표 이름들과 「(전이)」 표기 */
    private record Row(String table, String name, List<String> coordinates, boolean transitive) {}

    /** {@code group:artifact} · {@code plugin:id} · npm 이름 */
    private static List<String> declarations() throws IOException {
        List<String> found = new ArrayList<>();
        Matcher call = GRADLE_CALL.matcher(Files.readString(BUILD, StandardCharsets.UTF_8));
        while (call.find()) {
            found.add(call.group(1) + ":" + call.group(2));
        }
        Matcher plugin = PLUGIN_ID.matcher(Files.readString(BUILD, StandardCharsets.UTF_8));
        while (plugin.find()) {
            found.add("plugin:" + plugin.group(1));
        }
        JsonNode pkg = JsonMapper.builder().build().readTree(Files.readString(PACKAGE, StandardCharsets.UTF_8));
        for (String map : List.of("dependencies", "devDependencies", "peerDependencies", "optionalDependencies")) {
            pkg.path(map).propertyNames().forEach(found::add);
        }
        return found;
    }

    /** 「의존성」 절 안에서 머리 셋째 칸이 「좌표」인 표의 행만. 「서비스」 표는 좌표가 없어서 빠진다 */
    private static List<Row> rows() throws IOException {
        List<Row> rows = new ArrayList<>();
        boolean inSection = false;
        String table = null;
        for (String line : Files.readAllLines(REFERENCES, StandardCharsets.UTF_8)) {
            if (line.startsWith("### 의존성")) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) {
                break;
            }
            if (!inSection || !line.startsWith("|")) {
                table = line.startsWith("|") ? table : null;
                continue;
            }
            String[] cells = line.split("\\|", -1);
            if (cells.length < 5) {
                continue;
            }
            String first = cells[1].strip();
            String third = cells[3].strip();
            if (third.equals("좌표")) {
                table = first;
                continue;
            }
            if (table == null || first.startsWith("---")) {
                continue;
            }
            List<String> coordinates = new ArrayList<>();
            Matcher names = BACKTICKED.matcher(third);
            while (names.find()) {
                coordinates.add(names.group(1));
            }
            rows.add(new Row(table, first, coordinates, third.startsWith("(전이)")));
        }
        return rows;
    }

    /** {@code group:*} 은 그 그룹 전부, {@code @scope/*} 은 그 범위 전부, 나머지는 글자 그대로 */
    private static boolean covers(String coordinate, String declaration) {
        if (coordinate.endsWith(":*")) {
            return declaration.startsWith(coordinate.substring(0, coordinate.length() - 1));
        }
        if (coordinate.endsWith("/*")) {
            return declaration.startsWith(coordinate.substring(0, coordinate.length() - 1));
        }
        return coordinate.equals(declaration);
    }

    /** 둘 중 하나라도 못 읽으면 아래 대조가 빈 목록으로 초록이 된다 */
    @Test
    @DisplayName("좌표 열을 가진 표와 세 갈래 선언을 다 읽는다")
    void readsTablesAndDeclarations() throws IOException {
        assertThat(rows())
                .describedAs("external-references.md 「### 의존성」 절에서 머리 셋째 칸이 「좌표」인 표를 못 읽었다 — 절이나 열 이름이 바뀌었다")
                .extracting(Row::table)
                .contains("의존성", "도구");
        assertThat(declarations())
                .describedAs("Gradle 설정 호출 · 플러그인 · package.json 중 한 갈래를 못 읽었다 — 정규식이나 파일 위치가 바뀌었다")
                .contains("org.springframework.boot:spring-boot-starter-webmvc", "plugin:org.springframework.boot", "next", "vitest");
        assertThat(UNREAD_FORM.matcher(Files.readString(BUILD, StandardCharsets.UTF_8)).find())
                .describedAs("build.gradle.kts 에 이 시험이 못 읽는 선언 꼴(alias(…)·kotlin(…)·버전 카탈로그 libs.)이 생겼다 — "
                        + "GRADLE_CALL·PLUGIN_ID 를 먼저 넓힌다. 안 그러면 그 선언이 표 대조를 조용히 비켜 간다")
                .isFalse();
    }

    @Test
    @DisplayName("선언한 의존성마다 표 행이 있다")
    void everyDeclarationHasRow() throws IOException {
        List<Row> rows = rows();
        TreeSet<String> missing = new TreeSet<>();
        for (String declaration : declarations()) {
            boolean covered = rows.stream()
                    .flatMap(row -> row.coordinates().stream())
                    .anyMatch(coordinate -> covers(coordinate, declaration));
            if (!covered) {
                missing.add(declaration);
            }
        }
        assertThat(missing)
                .describedAs("라이선스를 판단한 기록이 없는 선언이다. external-references.md 「의존성」(앱에 실린다) 또는 "
                        + "「도구」(빌드·시험만) 표에 라이선스와 좌표 열(`group:*` · `plugin:id` · npm 이름 · `@scope/*`)로 행을 더한다")
                .isEmpty();
    }

    @Test
    @DisplayName("전이 표기가 아닌 행은 좌표를 든다")
    void everyRowHasCoordinates() throws IOException {
        assertThat(rows())
                .filteredOn(row -> !row.transitive())
                .describedAs("좌표가 빈 행은 어느 선언도 덮지 않는다 — 좌표를 달거나 「(전이)」로 적는다")
                .allSatisfy(row -> assertThat(row.coordinates()).describedAs(row.name()).isNotEmpty());
    }
}
