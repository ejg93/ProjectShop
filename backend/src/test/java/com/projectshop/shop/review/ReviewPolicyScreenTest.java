package com.projectshop.shop.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 운영정책 제3판(`V94`)이 부르는 두 이름이 화면에 그대로 있나(`Q171`, 마무리 45차 독립 리뷰).
 *
 * <p><b>고지가 「어디서 이의를 제기하나」를 화면 이름으로 가리킨다</b>(`D2` R27). 화면의 글자를 바꾸면 고지가 없는 자리를
 * 가리키게 된다 — {@code ReviewModerationTest} 는 고지 본문만 보아서 화면 쪽이 바뀌어도 초록이었다. 이름을 여기 상수로
 * 두고 두 시험이 같이 쓴다: 고지 쪽은 그 시험(느린 레인, DB)이, 화면 쪽은 이 시험(빠른 레인, 소스)이 잰다.
 */
@DisplayName("운영정책이 부르는 화면 이름")
class ReviewPolicyScreenTest {

    /** 불만·분쟁을 받는 문의 종류의 화면 이름 */
    static final String DISPUTE_LABEL = "불만·분쟁 접수";

    /** 내려간 후기의 사유를 보는 화면으로 가는 링크 */
    static final String MY_REVIEWS_LABEL = "내 후기";

    private static final Path SCREEN_ROOT = Path.of("..", "frontend", "src");

    @Test
    @DisplayName("고지가 가리키는 이름이 화면에 있다")
    void screensCarryTheNamesThePolicyPointsTo() throws IOException {
        assertThat(read("app/me/inquiries/page.tsx")).contains(DISPUTE_LABEL);
        assertThat(read("app/me/page.tsx")).contains(MY_REVIEWS_LABEL);
    }

    private static String read(String path) throws IOException {
        return Files.readString(SCREEN_ROOT.resolve(path), StandardCharsets.UTF_8);
    }
}
