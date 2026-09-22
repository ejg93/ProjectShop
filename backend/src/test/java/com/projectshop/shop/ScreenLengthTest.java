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
 * <p><b>같은 이름의 요청 칸이 서로 다른 상한을 가지면 대조에서 뺀다</b>({@link #AMBIGUOUS_NAMES}).
 * 화면이 어느 입구로 보내는지를 이 대조가 몰라서, 그냥 이으면 <b>틀린 쌍을 대조하며 초록</b>이 된다 —
 * {@code Q28} 이 사유 컬럼에서 정한 것과 같은 기준이다.
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

    /**
     * <b>같은 이름의 요청 칸이 서로 다른 상한을 가진 자리.</b> 화면이 어느 쪽으로 보내는지를
     * 이 대조가 모르므로 <b>대조에서 뺀다.</b>
     *
     * <p>「아무거나 맞으면 통과」로 두지 않는 이유는 {@code Q28} 이 사유 컬럼에서 정한 것과 같다 —
     * <b>잘못 이으면 틀린 쌍을 대조하며 초록</b>이 된다. 어느 입구인지를 알아내 이으려면
     * 호출 사슬을 따라가야 하고, 그건 이 대조가 아니라 청크가 할 일이다(`Q46` 과 같은 꼴).
     *
     * <p>값이 「왜 갈리나」다.
     */
    private static final Map<String, String> AMBIGUOUS_NAMES = Map.of(
            "name", "상품 이름 100 과 옵션 이름 50 이 같은 칸 이름을 쓴다");

    private record ScreenField(String where, String name, int max) {}

    @Test
    @DisplayName("화면 maxLength 가 서버 @Size 와 같다")
    void screenLimitsMatchServer() {
        Map<String, List<Integer>> serverLimits = serverLimits();
        List<ScreenField> fields = screenFields();

        assertThat(fields).as("화면을 못 읽으면 0개를 재고 조용히 통과한다").hasSizeGreaterThan(8);

        assertThat(serverLimits)
                .as("요청 record 를 못 읽으면 0쌍을 대조하고 조용히 통과한다")
                .hasSizeGreaterThan(8);

        List<String> mismatched = fields.stream()
                .filter(field -> serverLimits.containsKey(field.name()))
                .filter(field -> !AMBIGUOUS_NAMES.containsKey(field.name()))
                // 상한이 여럿이면 「아무거나 맞으면 통과」가 아니라 전부와 같아야 한다.
                // 여럿인 이름은 아래 AMBIGUOUS_NAMES 가 따로 든다.
                .filter(field -> serverLimits.get(field.name()).stream()
                        .anyMatch(max -> max != field.max()))
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
                    // 상한 애너테이션이 칸이 아니라 그 칸이 만든 필드에 붙는다.
                    // LengthConstraintTest 가 이미 그것을 푸는 헬퍼를 든다 —
                    // 복제하면 한쪽만 고치는 날이 온다.
                    int max;
                    try {
                        max = LengthConstraintTest.maxOf(component);
                    } catch (AssertionError error) {
                        // 「상한이 없는 칸」만 넘긴다. 그 헬퍼는 record 가 깨진 경우에도 같은
                        // 예외를 던지는데, 거기까지 삼키면 진짜 고장이 「상한 없음」으로 조용해진다.
                        String message = error.getMessage();
                        if (message == null || !message.contains("가 없다")) {
                            throw error;
                        }
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

    /**
     * {@code name="x"} 만 있는 칸. <b>{@code maxLength} 가 붙었는지는 안 묻는다.</b>
     *
     * <p>위 {@code FIELD} 는 둘을 <b>같이</b> 요구해서 {@code maxLength} 가 없는 칸을 아예 못 걷는다 —
     * 그래서 「상한을 빠뜨린 칸」이 목록에 안 들어가고 두 대조가 조용히 통과했다(점검 O).
     */
    private static final Pattern NAMED_FIELD = Pattern.compile(
            "<[A-Za-z][^<>]*?\\bname=\"(\\w+)\"[^<>]*>",
            Pattern.DOTALL);

    /**
     * {@code maxLength} 가 없어도 되는 화면 칸과 그 근거.
     *
     * <p><b>근거 없이 이름만 넣지 않는다.</b> 근거 칸이 없으면 이 목록이
     * <b>상한을 빠뜨렸을 때 도망칠 자리</b>가 된다.
     */
    private static final Map<String, String> WITHOUT_SCREEN_LIMIT = new java.util.TreeMap<>(Map.ofEntries(
            // **비밀번호는 안 자른다.** maxLength 는 넘친 글자를 조용히 버려서 사용자가 친 것과
            // 다른 값이 간다 — 로그인은 그냥 실패하고, 변경은 <b>본인도 모르는 비밀번호</b>가 된다.
            // 길이는 서버의 @Password 가 재고 거기서 400 으로 돌려준다(`D14`).
            Map.entry("app/login/login-form.tsx  password", "비밀번호는 잘리면 안 된다. 서버 @Password 가 잰다"),
            Map.entry("app/me/account-forms.tsx  currentPassword", "〃"),
            Map.entry("app/me/account-forms.tsx  emailPassword", "〃"),
            Map.entry("app/me/account-forms.tsx  newPassword", "〃"),
            Map.entry("app/me/withdraw/withdraw-form.tsx  password", "〃"),
            Map.entry("app/signup/signup-form.tsx  password", "〃"),

            // 아래 넷은 글자를 받는 칸이 아니다. maxLength 속성 자체가 안 걸린다.
            Map.entry("app/me/inquiries/inquiry-form.tsx  kind", "select 다. 고를 수 있는 값이 목록으로 닫혀 있다"),
            Map.entry("app/products/[productId]/ask-form.tsx  isPublic", "checkbox 다. 값이 둘뿐이다"),
            Map.entry("app/seller/products/new/product-form.tsx  priceInclVat",
                    "type=number 다. maxLength 가 안 걸리고 범위는 min/max 가 든다"),
            Map.entry("app/seller/products/new/product-form.tsx  stockCount", "〃"),
            Map.entry("app/seller/products/[productId]/images/image-manager.tsx  file",
                    "type=file 이다. 글자가 아니라 파일이고 크기·형식은 서버가 막는다(`Q140`)"),
            Map.entry("app/admin/roles/page.tsx  userId",
                    "type=number 다. maxLength 가 안 걸리고 아래쪽은 min 이 든다(`16`)"),
            Map.entry("app/seller/members/member-panel.tsx  roleCode",
                    "select 다. 고를 수 있는 값이 목록으로 닫혀 있다(`16a`)")));

    /**
     * 화면 칸 중 {@code maxLength} 가 없는 것을 찾는다.
     *
     * <p><b>위 둘과 방향이 반대다.</b> {@code screenLimitsMatchServer} 는 상한이 <b>있는</b> 칸의 수를 묻고
     * {@code unmatchedFieldsArePinned} 는 그중 서버 짝이 없는 것을 묻는다 — 둘 다
     * <b>상한이 있는 칸의 목록</b>에서 시작해서, 아예 없는 칸은 어느 쪽에도 안 걸린다.
     * 점검 O 가 그 구멍으로 셋을 찾았다: 가입의 {@code email}·{@code displayName} 과 계정의 {@code email} 이
     * 서버에서 254·50·254 인데 화면에는 상한이 없었고, 그때 이 파일의 두 대조가 초록이었다.
     */
    @Test
    @DisplayName("maxLength 가 없는 화면 칸은 전부 근거가 적혀 있다")
    void fieldsWithoutMaxLengthArePinned() {
        List<String> limited = screenFields().stream()
                .map(field -> field.where() + "  " + field.name())
                .toList();

        List<String> missing = namedFields().stream()
                .filter(field -> !limited.contains(field))
                .filter(field -> !WITHOUT_SCREEN_LIMIT.containsKey(field))
                .sorted()
                .distinct()
                .toList();

        assertThat(missing)
                .as("화면 입력칸에 maxLength 가 없다. 서버 @Size 와 같은 값을 주거나, "
                        + "상한이 없어야 할 이유를 WITHOUT_SCREEN_LIMIT 에 근거와 함께 적는다")
                .isEmpty();
    }

    /** 이름이 붙은 화면 칸 전부. {@code where  name} 꼴이라 위 목록들과 같은 표기다. */
    private static List<String> namedFields() {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SCREEN_ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".tsx")).toList()) {
                Matcher matcher = NAMED_FIELD.matcher(Files.readString(file));
                while (matcher.find()) {
                    found.add(SCREEN_ROOT.relativize(file).toString().replace('\\', '/')
                            + "  " + matcher.group(1));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("화면 소스를 못 읽었다: " + SCREEN_ROOT.toAbsolutePath(), e);
        }
        return found;
    }
}
