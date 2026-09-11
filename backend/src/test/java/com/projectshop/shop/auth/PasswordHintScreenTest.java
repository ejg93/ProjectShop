package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.validation.constraints.Size;

/**
 * 가입 화면이 적은 <b>비밀번호 규칙 문구</b>가 실제 검사와 같은 수인지 대조한다(`Q20-2`).
 *
 * <p><b>갈리면 사용자가 규칙을 못 맞춘 채 422 를 받는다.</b> 화면이 「12자 이상」이라 적고
 * 서버가 15자를 요구하면, 적힌 대로 쳤는데 거절당하고 <b>무엇이 틀렸는지 화면이 안 말한다</b> —
 * 규칙을 적어 둔 이유가 그 왕복을 없애는 것인데 적힌 값이 틀리면 없느니만 못하다.
 *
 * <p>{@code Password.java} 가 이미 「규칙이 두 군데 있으면 한쪽만 고치는 날 가입은 되는데
 * 변경이 막힌다」고 적고 {@code @Size} 를 하나로 모았다. <b>그 하나가 화면에도 복사돼 있다</b> —
 * 복사를 없앨 수는 없어서(서버가 화면 문구를 만들지 않는다) 대조로 묶는다.
 *
 * <p><b>수만 본다.</b> 문장은 화면 몫이라 자유롭게 바꿔도 되고(`D20`), 여기서 굳히면
 * 문구를 다듬을 때마다 빨개진다. 묶는 것은 <b>15</b> 와 <b>64</b> 두 수다.
 */
class PasswordHintScreenTest {

    private static final Path SCREEN =
            Path.of("..", "frontend", "src", "app", "signup", "signup-form.tsx");

    /** {@code const PASSWORD_HINT = "15자 이상 64자 이하, …";} 에서 두 수만 */
    private static final Pattern HINT =
            Pattern.compile("PASSWORD_HINT\\s*=\\s*\"(\\d+)자 이상 (\\d+)자 이하");

    @Test
    @DisplayName("화면이 적은 비밀번호 길이가 @Password 의 값과 같다")
    void screenHintMatchesTheRuleTheServerEnforces() throws IOException {
        Size size = Password.class.getAnnotation(Size.class);
        assertThat(size)
                .describedAs("@Password 에서 @Size 가 사라졌다. 규칙의 출처가 바뀌었으면 이 테스트도 같이 옮긴다")
                .isNotNull();

        String source = Files.readString(SCREEN, StandardCharsets.UTF_8);
        Matcher hint = HINT.matcher(source);

        // 정규식이 상하면 조용히 통과한다. 그쪽이 어긋난 것보다 나쁘다.
        assertThat(hint.find())
                .describedAs("가입 화면에서 비밀번호 규칙 문구를 못 읽었다. PASSWORD_HINT 모양이 바뀌었나")
                .isTrue();

        assertThat(Integer.parseInt(hint.group(1)))
                .describedAs("화면이 적은 최소 길이가 서버가 요구하는 것과 다르다")
                .isEqualTo(size.min());

        assertThat(Integer.parseInt(hint.group(2)))
                .describedAs("화면이 적은 최대 길이가 서버가 허용하는 것과 다르다")
                .isEqualTo(size.max());
    }
}
