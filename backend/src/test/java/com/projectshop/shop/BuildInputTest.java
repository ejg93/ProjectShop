package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 테스트가 읽는 <b>저장소 밖 파일</b>이 Gradle 에 신고돼 있는지 본다(`Q25`).
 *
 * <h2>무엇을 막나</h2>
 *
 * <p>Gradle 은 테스트가 무엇을 읽는지 스스로 모른다. {@code inputs.files} 로 적어 준 것만 안다.
 * 빠뜨리면 <b>그 파일만 고친 청크에서 테스트가 통째로 {@code UP-TO-DATE} 로 건너뛴다</b> —
 * 대조가 한 번도 안 도는데 빌드는 초록이다.
 *
 * <p><b>저장소가 네 번 밟았다.</b> {@code 2f}({@code stack.md}) · {@code Q16}(화면 소스) ·
 * {@code data-lifecycle.md}(느린 레인에 신고가 아예 없었다) · {@code docker-compose.yml}.
 * 첫 번째 뒤에 이력에 적어 뒀는데 <b>그 뒤로 세 번 더 났다</b> —
 * 기록은 재발을 못 막고 강제 지점은 막는다.
 *
 * <h2>어떻게 걷나 — 둘을 같이 쓴다</h2>
 *
 * <table>
 *   <caption>둘이 못 보는 자리가 서로 다르다</caption>
 *   <tr><th>방법</th><th>못 보는 것</th></tr>
 *   <tr><td><b>소스를 글자로</b> — {@code Path.of("..", …)} 를 정규식으로 찾는다</td>
 *       <td>조각을 이어 붙여 만든 경로. 정규식에 통째로 안 걸린다</td></tr>
 *   <tr><td><b>상수를 리플렉션으로</b> — {@code Path} 타입 정적 필드의 값을 읽는다</td>
 *       <td>상수로 안 빼고 부르는 자리에 바로 쓴 경로. 읽을 필드가 없다</td></tr>
 * </table>
 *
 * <p>합집합으로 두면 한쪽이 놓친 것을 다른 쪽이 덮는다(사용자 결정 2026-09-14).
 *
 * <p><b>컨테이너를 띄우는 클래스는 값을 안 읽는다.</b> 정적 필드를 읽으려면 클래스를 초기화해야
 * 하는데, {@code PostgresTestBase}·{@code HttpTestBase} 를 상속한 것은 그 순간 컨테이너가 뜬다 —
 * <b>빠른 레인이 Docker 를 타게 된다.</b> 그래서 초기화 없이 조상만 보고 걸러 내고
 * ({@code Class.forName(name, false, …)} 은 초기화를 안 한다), 그쪽은 글자 훑기에 맡긴다.
 *
 * <h2>신고 목록을 어떻게 아나</h2>
 *
 * <p><b>{@code build.gradle.kts} 를 글자로 안 읽는다.</b> 그 파일이 목록을
 * {@code declaredComparedInputs} 시스템 속성으로 내려보낸다 — 신고를 쓰는 방식이 하나 늘어도
 * (파일 하나씩과 폴더 통째로가 이미 섞여 있다) 읽는 쪽이 안 깨진다.
 *
 * <h2>못 보는 것</h2>
 *
 * <p><b>실행할 때 정해지는 경로</b>는 둘 다 못 본다. {@code StackVersionConsistencyTest} 가
 * 그 하나고, {@link #DYNAMIC_ALLOWED} 에 이유와 같이 적혀 있다.
 * {@link #DYNAMIC_COUNT} 가 그 수를 박아서 조용히 느는 것을 막는다.
 */
class BuildInputTest {

    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    /** {@code Path.of(...)} 한 자리. 괄호 안에 또 괄호가 없는 꼴만 본다 */
    private static final Pattern PATH_OF = Pattern.compile("Path\\.of\\(([^()]*)\\)");

    private static final Pattern STRING_ARG = Pattern.compile("^\"([^\"]*)\"$");

    /** 컨테이너를 띄우는 베이스. 상속한 클래스는 초기화하지 않는다 */
    private static final Set<String> DB_BASES = Set.of(
            "com.projectshop.shop.PostgresTestBase",
            "com.projectshop.shop.HttpTestBase");

    /**
     * 실행할 때 경로가 정해져서 둘 다 못 보는 자리. <b>항목마다 어떻게 신고되는지를 적는다</b> —
     * 이유가 없으면 다음 사람이 그냥 지운다.
     *
     * <p><b>{@link SqlTextTest} 처럼 코드 옆 마커를 안 쓴 이유</b>: 거기는 예외가 늘어날 자리라
     * 마커가 값을 하는데(새 결합 자리가 생길 때마다 테스트를 안 고친다), 여기는 <b>지금 하나고
     * 늘 이유도 없다</b> — 저장소 밖 파일을 실행 때 골라 여는 테스트가 더 생기는 것 자체가
     * 드물다. 「세어 보고 하나뿐이면 안 만든다」(`CLAUDE.md` 대전제)라 목록으로 둔다.
     * 둘째가 생기면 그때 마커로 옮긴다.
     */
    private static final Map<String, String> DYNAMIC_ALLOWED = Map.of(
            "support/StackVersionConsistencyTest.java",
            "stack.md 표의 세 번째 칸이 가리키는 파일을 실행할 때 연다."
                    + " 그 파일은 build.gradle.kts 의 comparedInFastLane 에 이름으로 신고한다(docker-compose.yml)");

    /** 위 목록의 크기를 박는다. <b>내리기만 한다</b> — 늘리는 것은 못 보는 자리를 하나 더 인정하는 것이다 */
    private static final int DYNAMIC_COUNT = 1;

    private record Usage(String where, String path) {}

    @Test
    @DisplayName("테스트가 읽는 저장소 밖 파일이 전부 Gradle 에 신고돼 있다")
    void everyOutsidePathIsDeclared() {
        List<String> declared = declaredInputs();
        List<String> missing = new ArrayList<>(new TreeSet<>(
                usages().stream()
                        .filter(usage -> declared.stream().noneMatch(d -> covers(d, usage.path())))
                        .map(usage -> usage.where() + "  " + usage.path())
                        .toList()));

        assertThat(missing)
                .as("신고가 빠지면 그 파일만 고친 청크에서 Gradle 이 테스트를 UP-TO-DATE 로 건너뛴다."
                        + " 대조가 한 번도 안 도는데 빌드는 초록이다 (testing-strategy.md, Q25)."
                        + " build.gradle.kts 의 comparedInFastLane·comparedInSlowLane 에 더한다")
                .isEmpty();
    }

    @Test
    @DisplayName("실행할 때 정해지는 경로는 이유와 같이 적혀 있다")
    void dynamicPathsAreListedWithReason() {
        List<String> undocumented = sources().stream()
                .filter(source -> hasDynamicOutsidePath(source.text()))
                .map(BuildInputTest::relativeName)
                .filter(name -> !DYNAMIC_ALLOWED.containsKey(name))
                .toList();

        assertThat(undocumented)
                .as("경로가 실행할 때 정해지면 글자로도 상수로도 못 본다."
                        + " 어떻게 신고되는지를 BuildInputTest.DYNAMIC_ALLOWED 에 같이 적는다")
                .isEmpty();
        assertThat(DYNAMIC_ALLOWED)
                .as("못 보는 자리가 조용히 늘면 이 테스트가 덮는 범위가 줄어든다."
                        + " 늘리려면 DYNAMIC_COUNT 를 같이 고치고 그 줄을 리뷰에 올린다")
                .hasSize(DYNAMIC_COUNT);
    }

    @Test
    @DisplayName("걷은 경로가 실제 대조 수만큼 있다")
    void enoughUsagesWereCollected() {
        // 걷는 쪽이 고장나면 0개를 찾고 조용히 통과한다. 그쪽이 신고가 빠진 것보다 나쁘다.
        // 2026-09-14 실측이 아홉 자리였다.
        assertThat(usages()).hasSizeGreaterThan(7);
    }

    /** 신고 목록. {@code ../} 를 떼어 저장소 뿌리 기준으로 맞춘다 */
    private static List<String> declaredInputs() {
        String raw = System.getProperty("declaredComparedInputs");
        assertThat(raw)
                .as("build.gradle.kts 가 declaredComparedInputs 를 안 넘겼다."
                        + " tasks.withType<Test> 의 systemProperty 를 본다")
                .isNotNull();
        return Stream.of(raw.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(BuildInputTest::normalize)
                .toList();
    }

    /** 신고한 것이 파일 자체이거나, 그 파일을 담은 폴더인가 */
    private static boolean covers(String declared, String used) {
        return used.equals(declared) || used.startsWith(declared + "/");
    }

    private static String normalize(String path) {
        String slashed = path.replace('\\', '/');
        while (slashed.startsWith("../")) {
            slashed = slashed.substring(3);
        }
        return slashed;
    }

    private List<Usage> usages() {
        Set<Usage> found = new LinkedHashSet<>();
        found.addAll(usagesFromText());
        found.addAll(usagesFromConstants());
        return List.copyOf(found);
    }

    /** 글자 훑기 — {@code Path.of("..", …)} 를 찾아 조각을 잇는다 */
    private static List<Usage> usagesFromText() {
        List<Usage> found = new ArrayList<>();
        for (Source source : sources()) {
            Matcher matcher = PATH_OF.matcher(source.text());
            while (matcher.find()) {
                List<String> segments = literalSegments(matcher.group(1));
                if (segments.isEmpty() || !segments.get(0).startsWith("..")) {
                    continue;
                }
                int line = 1 + (int) source.text().substring(0, matcher.start()).chars()
                        .filter(c -> c == '\n').count();
                found.add(new Usage(relativeName(source) + ":" + line, normalize(String.join("/", segments))));
            }
        }
        return found;
    }

    /** 리플렉션 — {@code Path} 타입 정적 상수의 값을 읽는다. 조각을 이어 만든 것도 완성된 값으로 보인다 */
    private static List<Usage> usagesFromConstants() {
        List<Usage> found = new ArrayList<>();
        for (String className : testClassNames()) {
            Class<?> loaded;
            try {
                loaded = Class.forName(className, false, BuildInputTest.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            if (startsContainers(loaded) || !hasPathField(loaded)) {
                continue;
            }
            Class<?> initialized;
            try {
                initialized = Class.forName(className, true, BuildInputTest.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }
            for (Field field : initialized.getDeclaredFields()) {
                if (field.getType() != Path.class || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Path value = (Path) field.get(null);
                    if (value != null && value.normalize().startsWith("..")) {
                        found.add(new Usage(className + "." + field.getName(),
                                normalize(value.normalize().toString())));
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // 값을 못 읽는 필드는 글자 훑기에 맡긴다. 여기서 죽으면 대조 전체가 멈춘다.
                }
            }
        }
        return found;
    }

    private static boolean startsContainers(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (DB_BASES.contains(c.getName())) {
                return true;
            }
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing != null && startsContainers(enclosing);
    }

    private static boolean hasPathField(Class<?> type) {
        try {
            return Stream.of(type.getDeclaredFields())
                    .anyMatch(f -> f.getType() == Path.class && Modifier.isStatic(f.getModifiers()));
        } catch (LinkageError e) {
            return false;
        }
    }

    /** 인자가 전부 문자열 리터럴이면 그 값들, 하나라도 아니면 빈 목록 */
    private static List<String> literalSegments(String args) {
        List<String> segments = new ArrayList<>();
        for (String arg : args.split(",")) {
            Matcher matcher = STRING_ARG.matcher(arg.trim());
            if (!matcher.matches()) {
                return List.of();
            }
            segments.add(matcher.group(1));
        }
        return segments;
    }

    /** {@code Path.of("..", 변수)} 처럼 밖을 가리키는데 값이 실행 때 정해지는 자리가 있나 */
    private static boolean hasDynamicOutsidePath(String text) {
        Matcher matcher = PATH_OF.matcher(text);
        while (matcher.find()) {
            String args = matcher.group(1);
            if (args.trim().startsWith("\"..\"") && literalSegments(args).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private record Source(Path path, String text) {}

    private static List<Source> sources() {
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            // 주석을 걷는다. **이 파일의 javadoc 이 자기 테스트를 빨갛게 만들었다** —
                            // 설명에 적은 `Path.of("..", …)` 예시가 대조 대상으로 잡혔다.
                            return new Source(p, JavaSourceText.withoutComments(Files.readString(p)));
                        } catch (IOException e) {
                            throw new UncheckedIOException(p + " 를 못 읽었다", e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("테스트 소스를 못 읽었다: " + TEST_SOURCES.toAbsolutePath(), e);
        }
    }

    private static String relativeName(Source source) {
        return TEST_SOURCES.resolve("com/projectshop/shop").relativize(source.path())
                .toString().replace('\\', '/');
    }

    /** 컴파일된 테스트 클래스 이름. 이 클래스가 놓인 자리에서 찾는다 */
    private static List<String> testClassNames() {
        Path root;
        try {
            root = Path.of(BuildInputTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString()
                            .replace(".class", "")
                            .replace('\\', '.')
                            .replace('/', '.'))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
