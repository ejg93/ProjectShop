package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ExposedNumber.insert} 에 적어 넣은 제약 이름이 실재하는지 센다(`Q49`).
 *
 * <p><b>이름을 글자로 넘기는 설계의 값을 여기서 치른다.</b> 오타가 나거나 마이그레이션이
 * 제약 이름을 바꾸면 {@code mentions} 가 영영 {@code false} 를 주고, 그러면
 * <b>충돌이 재시도 없이 그대로 500 이 된다</b> — 그런데 충돌 확률이 32⁶ 분의 1 이라
 * <b>그 사실이 실행 중에 드러날 일이 없다.</b> 드러나지 않는 고장은 테스트로만 잡힌다.
 *
 * <p><b>이 검사가 후보 ① 의 약점을 메운다.</b> 제약 이름 목록을 재시도 쪽에 두는 방식은
 * 새 노출 번호가 생긴 날 거기 적는 것을 빠뜨리는 것이 약점인데, 이름을 부르는 자리에 두면
 * <b>빠뜨릴 자리 자체가 없고</b> 남는 위험은 「적은 이름이 틀린 것」 하나다. 그것을 여기서 잡는다.
 *
 * <p>DB 를 안 띄운다. 마이그레이션도 자바 소스도 파일이라 글자로 읽으면 된다.
 */
class ExposedNumberConstraintTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final Path MIGRATION_DIR =
            Path.of("src", "main", "resources", "db", "migration");

    /** {@code ExposedNumber.insert(접두어, "제약 이름", …)} 의 두 번째 인자 */
    private static final Pattern CALL = Pattern.compile(
            "ExposedNumber\\.insert\\(\\s*[^,]+,\\s*\"([A-Za-z0-9_]+)\"");

    /** {@code constraint x unique (…)} 과 {@code create unique index x on …} 둘 다 받는다 */
    private static final Pattern DECLARED = Pattern.compile(
            "(?:constraint\\s+([a-z0-9_]+)\\s+unique|create\\s+unique\\s+index\\s+([a-z0-9_]+))",
            Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("노출 번호가 부르는 제약 이름이 마이그레이션에 실재한다")
    void everyNamedConstraintExists() throws IOException {
        List<String> used = usedConstraintNames();
        var declared = declaredConstraintNames();

        assertThat(used)
                .describedAs("ExposedNumber.insert 를 부르는 자리를 하나도 못 읽었다. "
                        + "호출 꼴이 바뀌었으면 이 테스트의 정규식을 같이 고친다")
                .isNotEmpty();

        assertThat(declared)
                .describedAs("마이그레이션에서 유일 제약을 하나도 못 읽었다")
                .isNotEmpty();

        assertThat(used)
                .describedAs("ExposedNumber.insert 가 부르는 제약이 DB 에 없다. "
                        + "오타면 고치고, 제약 이름을 바꾼 마이그레이션이 있으면 호출도 같이 고친다. "
                        + "안 맞으면 충돌이 재시도 없이 500 이 되는데 확률이 낮아 실행 중에 안 드러난다")
                .allSatisfy(name -> assertThat(declared).contains(name));
    }

    @Test
    @DisplayName("결제사가 준 번호의 제약은 노출 번호로 안 부른다")
    void gatewaySuppliedNumbersAreNotRetried() throws IOException {
        // 이름 꼬리(`_number_unique`)로 가르면 이 둘이 같이 걸린다. 값을 바깥에서 받으므로
        // 다시 뽑을 것이 없고, 재시도는 진짜 중복 승인을 덮기까지 한다(`Q49`).
        assertThat(usedConstraintNames())
                .describedAs("결제사가 준 번호는 다시 뽑을 수 없다. "
                        + "ExposedNumber 를 이 제약에 붙이면 재시도가 네 번 헛돌고 중복 승인이 가려진다")
                .doesNotContain("payment_approval_number_unique",
                        "refund_gateway_refund_number_unique");
    }

    private static List<String> usedConstraintNames() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                Matcher calls = CALL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (calls.find()) {
                    names.add(calls.group(1));
                }
            }
        }
        return names;
    }

    private static TreeSet<String> declaredConstraintNames() throws IOException {
        var names = new TreeSet<String>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            for (Path file : files.toList()) {
                if (!file.getFileName().toString().endsWith(".sql")) {
                    continue;
                }
                Matcher found = DECLARED.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (found.find()) {
                    String name = found.group(1) != null ? found.group(1) : found.group(2);
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        return names;
    }
}
