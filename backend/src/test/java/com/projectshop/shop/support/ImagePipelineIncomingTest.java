package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 올라온 사진의 표시 이름(`Q305`).
 *
 * <p>DB 를 안 띄운다 — 이름 글자만 본다(`D15`). 바닥인 DB 제약은 {@code ProductImageServiceTest} 가 잰다.
 */
@DisplayName("사진 표시 이름")
class ImagePipelineIncomingTest {

    private static final byte[] BYTES = {1};

    /**
     * 글자를 코드 포인트로 넣는다. 소스에 U+202E 를 역슬래시-u 이스케이프로 적으면 javac 가 컴파일 전에(주석 안에서도) 진짜 문자로 바꿔서
     * ErrorProne({@code UnicodeDirectionalityCharacters})이 그 줄을 막는다 — 이 시험이 막으려는 바로 그 위장이다.
     */
    @ParameterizedTest
    @ValueSource(ints = {0x202E, 0x2066, 0x200F, 0x061C, 0x000A, 0x0000, 0x007F})
    @DisplayName("제어문자나 양방향 서식 문자가 든 이름은 400 이다")
    void rejectsDisguisingCharacters(int codePoint) {
        String name = "photo" + Character.toString(codePoint) + "gpj.exe";

        assertThatThrownBy(() -> new ImagePipeline.Incoming(name, BYTES))
                .isInstanceOfSatisfying(ShopException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    @DisplayName("한글·공백·괄호가 든 보통 이름은 받는다")
    void acceptsOrdinaryNames() {
        assertThat(new ImagePipeline.Incoming("앞면 (1).jpg", BYTES).originalName()).isEqualTo("앞면 (1).jpg");
    }

    @Test
    @DisplayName("이름이 없으면 여기서 안 막는다 — 확장자 검사가 415 로 끊는다")
    void leavesMissingNameToTheExtensionCheck() {
        assertThat(new ImagePipeline.Incoming(null, BYTES).originalName()).isNull();
    }
}
