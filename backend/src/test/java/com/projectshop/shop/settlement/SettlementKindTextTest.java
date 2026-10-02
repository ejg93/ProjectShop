package com.projectshop.shop.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 정산 줄 종류의 화면 문구가 열거형을 다 덮는다(`Q244b`).
 *
 * <p>화면은 모르는 종류를 코드 그대로 그린다(`D5` 「모르는 열거값은 무시한다」) — 그래서 문구가 빠져도 오류가 안 나고
 * 셀러의 정산서에 {@code COUPON_DISCOUNT} 가 글자 그대로 찍힌다. 쿠폰 두 종류가 실제로 그렇게 빠져 있었다.
 * 열거형과 DB {@code check} 는 {@code EnumConstraintTest} 가 잇고, 여기는 열거형과 화면 표를 잇는다.
 */
@DisplayName("정산 줄 종류 문구")
class SettlementKindTextTest {

    private static final Path SCREEN_TEXT = Path.of("..", "frontend", "src", "lib", "settlement-text.ts");

    @Test
    @DisplayName("화면 표가 열거형의 종류를 다 덮는다")
    void coversEveryKind() throws IOException {
        String source = Files.readString(SCREEN_TEXT, StandardCharsets.UTF_8);

        assertThat(codesIn(source, "ITEM_KIND"))
                .describedAs("종류를 열거형에만 더하면 화면이 코드를 그대로 그린다")
                .isEqualTo(Arrays.stream(SettlementItemKind.values())
                        .map(Enum::name)
                        .collect(Collectors.toCollection(TreeSet::new)));
    }

    /** {@code const 이름: Record<string, string> = { CODE: "…", … }} 에서 코드만 뽑는다 */
    private Set<String> codesIn(String source, String table) {
        int start = source.indexOf("const " + table);
        int end = source.indexOf("};", start);
        String body = source.substring(start, end);

        Set<String> codes = new TreeSet<>();
        Matcher matcher = Pattern.compile("(?m)^\\s{2}([A-Z_]+):").matcher(body);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }
}
