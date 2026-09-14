package com.projectshop.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


/**
 * 화면 입력칸의 {@code maxLength} 와 서버 {@code @Size(max)} 가 같은지 본다(`Q27`).
 *
 * <p><b>갈리면 사용자가 막힌다.</b> 화면이 200자를 받아 주는데 서버가 100자까지면
 * <b>400 이어야 할 것이 화면에서 안 걸리고</b> 보낸 뒤에야 실패한다. 실제로 났다 —
 * {@code Q22} 가 {@code product.name} 을 200에서 100으로 좁히면서
 * {@code product-form.tsx} 의 {@code maxLength=200} 을 안 봤다.
 *
 * <p><b>{@link LengthConstraintTest} 와 짝이다.</b> 그쪽이 {@code @Size} ↔ DB {@code check} 를 보고
 * 이쪽이 {@code @Size} ↔ 화면 {@code maxLength} 를 본다. <b>셋이 한 줄에 선다</b>(화면 → 앱 → DB).
 *
 * <h2>무엇으로 잇나 — 이름 관례</h2>
 *
 * <p>화면의 {@code name} 속성과 요청 record 의 칸 이름이 같다는 관례에 기댄다(사용자 결정 2026-09-14).
 * 목록을 손으로 적는 쪽은 <b>빠뜨려도 안 걸린다</b> — 그쪽이 이 대조가 막으려는 사고와 같은 꼴이다.
 *
 * <p><b>관례에 기대면서 관례가 깨지는 것도 잡는다.</b> {@code maxLength} 를 단 칸 중
 * <b>짝을 못 찾은 것</b>을 세서 목록과 맞춘다 — 이름이 어긋나기 시작하면 그 수가 변해서 걸린다.
 *
 * <h2>무엇을 못 보나</h2>
 *
 * <p><b>같은 이름을 여러 record 가 쓰면 전부와 맞춘다.</b> {@code reason} 처럼 흔한 이름은
 * 여러 요청에 있고, 그중 하나라도 상한이 다르면 걸린다 — 화면이 어느 쪽으로 보내는지는
 * 이 테스트가 모르므로 <b>가장 좁은 쪽에 맞추라고 말하는 셈</b>이다.
 */
@DisplayName("화면 상한과 서버 상한의 대조")
class ScreenLengthTest {

    private static final Path SCREEN_ROOT = Path.of("..", "frontend", "src");

    /** {@code name="x"} 와 {@code maxLength={N}} 이 한 태그 안에 있는 자리 */
    private static final Pattern FIELD = Pattern.compile(
            "<[A-Za-z][^<>]*?\\bname=\"(\\w+)\"[^<>]*?\\bmaxLength=\\{(\\d+)\\}[^<>]*>",
            Pattern.DOTALL);

    /** 순서가 반대인 꼴 */
    private static final Pattern FIELD_REVERSED = Pattern.compile(
            "<[A-Za-z][^<>]*?\\bmaxLength=\\{(\\d+)\\}[^<>]*?\\bname=\"(\\w+)\"[^<>]*>",
            Pattern.DOTALL);

    /**
     * 서버에 짝이 없는 화면 칸. <b>값이 「왜 서버 상한이 없나」다.</b>
     *
     * <p>이 수가 변하면 이름 관례가 깨진 것이거나 새 칸이 생긴 것이다 — 둘 다 봐야 한다.
     */
    private static final Map<String, String> WITHOUT_SERVER_LIMIT = new java.util.TreeMap<>(Map.of(
            "app/checkout/checkout-form.tsx  cardNumber",
                    "상한이 @Size 가 아니라 @Pattern 안에 있다 — `[0-9][0-9 -]{10,23}[0-9]` 라 25자다",
            "app/checkout/checkout-form.tsx  postalCode",
                    "상한이 @Pattern 안에 있다 — `^[0-9]{5}$` 라 5자다"));

    private record ScreenField(String where, String name, int max) {}

    @Test
    @DisplayName("화면 maxLength 가 서버 @Size 와 같다")
    void screenLimitsMatchServer() {
        Map<String, List<Integer>> serverLimits = serverLimits();
        List<ScreenField> fields = screenFields();

        assertThat(fields).as("화면을 못 읽으면 0개를 재고 조용히 통과한다").hasSizeGreaterThan(8);

        List<String> mismatched = fields.stream()
                .filter(field -> serverLimits.containsKey(field.name()))
                .filter(field -> !serverLimits.get(field.name()).contains(field.max()))
                .map(field -> field.where() + "  " + field.name() + " 화면 " + field.max()
                        + " ↔ 서버 " + serverLimits.get(field.name()))
                .toList();

        assertThat(mismatched)
                .as("화면이 더 헐거우면 사용자가 보낸 뒤에야 실패하고, 더 빡빡하면 서버가 받는 값을"
                        + " 화면이 막는다 (testing-strategy.md 「두 층에 흩어진 문자열의 대조」, Q27)")
                .isEmpty();
    }

    @Test
    @DisplayName("서버에 짝이 없는 화면 칸이 적어 둔 것뿐이다")
    void unmatchedFieldsArePinned() {
        Map<String, List<Integer>> serverLimits = serverLimits();

        assertThat(screenFields().stream()
                .filter(field -> !serverLimits.containsKey(field.name()))
                .map(field -> field.where() + "  " + field.name())
                .sorted()
                .toList())
                .as("이 대조는 「화면의 name 과 요청 record 의 칸 이름이 같다」는 관례에 기댄다."
                        + " 짝을 못 찾은 칸이 늘면 그 관례가 깨진 것이라, 위 대조가 조용히 덜 본다")
                .isEqualTo(List.copyOf(WITHOUT_SERVER_LIMIT.keySet()));
    }

    /** 요청 record 칸 이름 → 그 이름으로 걸린 상한들 */
    private static Map<String, List<Integer>> serverLimits() {
        Map<String, List<Integer>> limits = new LinkedHashMap<>();
        for (Class<?> controller : controllers()) {
            for (Class<?> nested : controller.getDeclaredClasses()) {
                if (!nested.isRecord()) {
                    continue;
                }
                for (RecordComponent component : nested.getRecordComponents()) {
                    //  가 칸이 아니라 그 칸이 만든 필드에 붙는다. LengthConstraintTest 가
                    // 이미 그것을 푸는 헬퍼를 든다 — 복제하면 한쪽만 고치는 날이 온다.
                    int max;
                    try {
                        max = LengthConstraintTest.maxOf(component);
                    } catch (AssertionError noSize) {
                        // 상한이 없는 칸이다. 화면이 걸 것도 없다.
                        continue;
                    }
                    limits.computeIfAbsent(component.getName(), key -> new ArrayList<>()).add(max);
                }
            }
        }
        return limits;
    }

    /** 컨트롤러는 {@code main} 에 산다 — 이 테스트가 놓인 자리를 훑으면 하나도 안 나온다 */
    private static List<Class<?>> controllers() {
        Path root;
        try {
            root = Path.of(BackendApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith("Controller.class"))
                    .map(path -> root.relativize(path).toString()
                            .replace(".class", "").replace('\\', '.').replace('/', '.'))
                    .<Class<?>>map(name -> {
                        try {
                            return Class.forName(name, false, ScreenLengthTest.class.getClassLoader());
                        } catch (ClassNotFoundException | LinkageError e) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<ScreenField> screenFields() {
        List<ScreenField> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SCREEN_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".tsx")).toList()) {
                String text = Files.readString(file);
                collect(FIELD.matcher(text), 1, 2, file, found);
                collect(FIELD_REVERSED.matcher(text), 2, 1, file, found);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("화면 소스를 못 읽었다: " + SCREEN_ROOT.toAbsolutePath(), e);
        }
        return found;
    }

    private static void collect(Matcher matcher, int nameGroup, int maxGroup, Path file,
            List<ScreenField> found) {
        while (matcher.find()) {
            found.add(new ScreenField(
                    SCREEN_ROOT.relativize(file).toString().replace('\\', '/'),
                    matcher.group(nameGroup),
                    Integer.parseInt(matcher.group(maxGroup))));
        }
    }
}
