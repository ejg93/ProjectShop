package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.account.MeController;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * 이메일 주소를 받는 입구 전부가 <b>같은 상한</b>을 쓰는지 본다.
 *
 * <p><b>픽스처가 아니라 실제 요청 record 를 태운다.</b> 테스트용 record 를 하나 만들어
 * {@link EmailAddress} 를 붙이면 <b>그 애너테이션이 맞다는 것만</b> 확인하게 된다 —
 * 물어야 하는 것은 <b>입구가 그것을 쓰고 있나</b>다. 생 {@code @Size(max = 320)} 을 도로
 * 붙이는 날 여기가 빨개져야 한다.
 *
 * <p><b>실제로 갈려 있었다</b>(`점검 L`). 가입이 254 고 이메일 변경이 320 이라
 * <b>가입으로 못 만드는 주소를 변경으로는 넣을 수 있었다.</b> {@link Password} 가 같은 함정을
 * 먼저 겪고 하나로 모은 자리인데 이메일은 안 모았다 — 모은 쪽은 안 갈렸고 안 모은 쪽은 갈렸다.
 *
 * <p><b>254 의 출처는 표준이다</b>({@code RFC 5321} §4.5.3.1.3, 경로 256 옥텟에서 꺾쇠 둘을 뺀 값).
 * 우리가 고를 수 있는 값이 아니라 이 테스트가 그 숫자를 박아 둔다.
 *
 * <p><b>{@code LoginRequest} 는 일부러 뺐다.</b> 거기는 {@code @NotBlank} 뿐인데,
 * 규칙을 세게 걸면 <b>규칙이 바뀌기 전에 만든 계정이 로그인을 못 한다.</b>
 * {@code PasswordRequest.currentPassword} 가 같은 이유로 규칙을 안 건다.
 */
class EmailAddressTest {

    /** {@code RFC 5321} 이 정한 주소 최대 길이. 이 값은 우리가 못 고친다. */
    private static final int MAX = 254;

    private static final Validator VALIDATOR;

    static {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            VALIDATOR = factory.getValidator();
        }
    }

    @Test
    @DisplayName("가입은 254 자를 받고 255 자를 막는다")
    void signupCapsAtStandardLength() {
        assertThat(emailViolations(signupWith(emailOfLength(MAX)))).isEmpty();
        assertThat(emailViolations(signupWith(emailOfLength(MAX + 1)))).isNotEmpty();
    }

    @Test
    @DisplayName("이메일 변경도 같은 자리에서 막는다")
    void emailChangeCapsAtTheSameLength() {
        assertThat(emailViolations(changeWith(emailOfLength(MAX)))).isEmpty();
        assertThat(emailViolations(changeWith(emailOfLength(MAX + 1)))).isNotEmpty();
    }

    @Test
    @DisplayName("두 입구가 형식도 같이 막는다")
    void bothEntriesRejectMalformed() {
        assertThat(emailViolations(signupWith("@example.com"))).isNotEmpty();
        assertThat(emailViolations(changeWith("@example.com"))).isNotEmpty();
    }

    private static AuthController.SignupRequest signupWith(String email) {
        return new AuthController.SignupRequest(
                email, "aaaaaaaaaaaaaaaa", "이름", Map.of("terms", true));
    }

    private static MeController.EmailRequest changeWith(String email) {
        return new MeController.EmailRequest(email, "aaaaaaaaaaaaaaaa");
    }

    /** {@code email} 칸에서 나온 위반만 센다. 같은 record 의 다른 칸은 이 테스트가 볼 것이 아니다. */
    private static Set<String> emailViolations(Object request) {
        return VALIDATOR.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("email"))
                .map(ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * 정확히 그 길이인 주소를 만든다.
     *
     * <p><b>local-part 를 64 로 고정한다</b> — {@code RFC 5321} §4.5.3.1.1 의 상한이고,
     * 검증기가 그것도 따로 보기 때문에 여기서 넘기면 <b>길이 상한이 아니라 local-part 때문에</b>
     * 빨개진다. 나머지를 도메인 라벨로 채우되 라벨 하나가 63 을 안 넘게 자른다(§4.5.3.1.2).
     */
    private static String emailOfLength(int total) {
        String local = "a".repeat(64);
        int domainLength = total - local.length() - 1;

        StringBuilder domain = new StringBuilder();
        while (domain.length() < domainLength) {
            if (domain.length() > 0) {
                domain.append('.');
            }
            int remaining = domainLength - domain.length();
            domain.append("a".repeat(Math.min(63, remaining)));
        }

        String email = local + "@" + domain;
        assertThat(email).describedAs("만든 주소의 길이가 요청과 다르다").hasSize(total);
        return email;
    }
}
