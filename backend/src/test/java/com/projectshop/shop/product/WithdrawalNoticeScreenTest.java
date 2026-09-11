package com.projectshop.shop.product;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 상품 상세가 그리는 <b>청약철회 제한 사유</b>가 실물과 같은지 대조한다(`D2` R4, `Q20-2`).
 *
 * <p><b>이것은 법 요건이다.</b> 전자상거래법 제17조제2항 단서가 <b>표시를 제한의 성립 요건</b>으로
 * 둔다 — 고지가 안 그려지면 서버가 반품을 막아도 근거가 없다. 그래서 이 어긋남은 화면이
 * 조금 이상해지는 종류가 아니라 <b>집행만 있고 성립이 없는 상태</b>다.
 *
 * <p><b>실제로 갈려 있었다</b>(`Q20-2` 가 찾았다). 화면이 {@code PERISHABLE}·{@code SEALED_COPYRIGHT}
 * 를 들고 있었는데 실물은 {@code COPYABLE_MEDIA}·{@code DIGITAL_CONTENT} 다 — 겹치는 것이
 * {@code MADE_TO_ORDER} 하나뿐이라, 나머지 둘로 등록된 상품은 <b>문단이 빈 채로</b> 그려졌다.
 *
 * <p>{@code ErrorSlugScreenTest} 와 같은 수법이다 — <b>두 층에 흩어진 문자열은 대조 말고
 * 막을 방법이 없다.</b> 화면 테스트로는 안 걸린다: 같은 틀린 키를 쓰면 초록이다.
 *
 * <p>한쪽만 보지 <b>않는다.</b> 여기는 양쪽이 같아야 한다 — 화면에만 있는 키는 죽은 가지고,
 * 실물에만 있는 사유는 <b>고지가 없는 상품</b>이라 법이 막는 자리다.
 */
class WithdrawalNoticeScreenTest {

    private static final Path SCREEN =
            Path.of("..", "frontend", "src", "app", "products", "[productId]", "page.tsx");

    /** {@code const WITHDRAWAL_REASON_TEXT: Record<WithdrawalReason, string> = { … };} */
    private static final Pattern MAP_BLOCK = Pattern.compile(
            "WITHDRAWAL_REASON_TEXT\\s*:\\s*Record<[^>]*>\\s*=\\s*\\{(.*?)\\n\\};", Pattern.DOTALL);

    /** 그 안의 {@code KEY: "문구",} */
    private static final Pattern KEY = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*:", Pattern.MULTILINE);

    @Test
    @DisplayName("화면의 제한 사유 문구 표가 실물 목록과 같다")
    void screenTextCoversEveryRestrictionReason() throws IOException {
        Set<String> onScreen = keysOnScreen();
        Set<String> real = Arrays.stream(WithdrawalRestrictionReason.values())
                .map(Enum::name)
                .collect(TreeSet::new, Set::add, Set::addAll);

        // 정규식이 상하면 0개를 읽고 조용히 통과한다. 그쪽이 어긋난 것보다 나쁘다.
        assertThat(onScreen)
                .describedAs("화면에서 제한 사유 문구를 한 건도 못 읽었다. WITHDRAWAL_REASON_TEXT 모양이 바뀌었나")
                .isNotEmpty();

        assertThat(onScreen)
                .describedAs(
                        "화면의 제한 사유 문구 표가 실물과 갈린다. 실물에만 있는 사유는 그 상품에서 "
                                + "고지가 빈 채로 그려지고, 제17조제2항 단서상 제한이 성립하지 않는다")
                .isEqualTo(real);
    }

    private static Set<String> keysOnScreen() throws IOException {
        String source = Files.readString(SCREEN, StandardCharsets.UTF_8);
        Matcher block = MAP_BLOCK.matcher(source);
        Set<String> keys = new TreeSet<>();

        while (block.find()) {
            Matcher key = KEY.matcher(block.group(1));
            while (key.find()) {
                keys.add(key.group(1));
            }
        }
        return keys;
    }
}
