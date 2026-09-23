package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 흔하거나 추측하기 쉬운 비밀번호를 거르는 규칙(`D14-2`).
 *
 * <p><b>빠른 레인이다.</b> 목록은 클래스패스의 파일이고 DB 도 스프링도 안 탄다. 입구 셋(가입·변경·재설정)이
 * 이것을 부르는지는 각 입구의 시험이 잰다 — 한쪽만 걸면 바꿔서 우회한다.
 */
@DisplayName("비밀번호 블록리스트")
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    @Test
    @DisplayName("목록에 있으면 대소문자와 무관하게 막는다")
    void blocksListedPasswordsCaseInsensitively() {
        assertRejected("123456789012345", List.of());
        assertRejected("PasswordPassword", List.of());
    }

    @Test
    @DisplayName("서비스 이름을 품으면 막는다")
    void blocksServiceName() {
        assertRejected("MyProjectShop-2026!", List.of());
    }

    @Test
    @DisplayName("그 사람의 이메일 앞부분이나 이름을 품으면 막는다 — 네 글자부터")
    void blocksContextWords() {
        assertRejected("hello-jiwoo-kim-99", List.of(PasswordPolicy.localPartOf("jiwoo@test.local")));
        assertRejected("i-am-harold-the-great", List.of("Harold"));

        assertThatCode(() -> policy.requireAcceptable("lemon-tree-orbit-3", List.of("bo")))
                .as("세 글자 이하 문맥 단어까지 막으면 막는 것이 너무 넓다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("한 글자 되풀이는 막는다")
    void blocksSingleCharacterRepetition() {
        assertRejected("xxxxxxxxxxxxxxxxxxxx", List.of());
    }

    @Test
    @DisplayName("흔하지 않은 비밀번호는 지나간다")
    void acceptsUncommonPasswords() {
        assertThatCode(() -> policy.requireAcceptable("tangerine-kite7-violet", Arrays.asList("buyer", null)))
                .doesNotThrowAnyException();
    }

    private void assertRejected(String password, List<String> context) {
        assertThatThrownBy(() -> policy.requireAcceptable(password, context))
                .isInstanceOf(ShopException.class)
                .hasFieldOrPropertyWithValue("code", ErrorCode.PASSWORD_TOO_COMMON);
    }
}
