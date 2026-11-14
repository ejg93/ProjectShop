package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AES-GCM 봉인이 묶음 값(AAD)까지 묶는지 잰다(`Q450`).
 *
 * <p><b>묶음 값이 이 도구의 요지다.</b> 표를 고칠 수 있는 사람이 남의 행 암호문을 제 행에 옮겨 붙여도
 * 풀리지 않아야 한다 — 웹훅 시크릿(셀러·주소)과 2차 인증 비밀(계정)이 같은 이유로 쓴다.
 */
@DisplayName("AES-GCM 봉인")
class AesGcmSealTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final byte[] PLAIN = "secret".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BINDING = "1".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("같은 키와 같은 묶음 값으로 풀면 원문이다")
    void roundTrips() {
        AesGcmSeal seal = AesGcmSeal.ofBase64Key(KEY);
        assertThat(seal.open(seal.seal(PLAIN, BINDING), BINDING)).isEqualTo(PLAIN);
    }

    @Test
    @DisplayName("묶음 값이 다르면 못 푼다")
    void rejectsOtherBinding() {
        AesGcmSeal seal = AesGcmSeal.ofBase64Key(KEY);
        byte[] sealed = seal.seal(PLAIN, BINDING);
        assertThatThrownBy(() -> seal.open(sealed, "2".getBytes(StandardCharsets.UTF_8)))
                .as("남의 행 암호문을 옮겨 붙이면 풀리면 안 된다")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("같은 평문을 두 번 싸면 결과가 다르다")
    void usesFreshIv() {
        AesGcmSeal seal = AesGcmSeal.ofBase64Key(KEY);
        assertThat(seal.seal(PLAIN, BINDING)).isNotEqualTo(seal.seal(PLAIN, BINDING));
    }

    @Test
    @DisplayName("키가 비었거나 32바이트가 아니면 쓸 수 없다")
    void unavailableWithoutValidKey() {
        assertThat(AesGcmSeal.ofBase64Key("").available()).isFalse();
        assertThat(AesGcmSeal.ofBase64Key(null).available()).isFalse();
        assertThat(AesGcmSeal.ofBase64Key(Base64.getEncoder().encodeToString(new byte[16])).available()).isFalse();
        assertThat(AesGcmSeal.ofBase64Key("not base64!").available()).isFalse();
        assertThat(AesGcmSeal.ofBase64Key(KEY).available()).isTrue();
    }

    @Test
    @DisplayName("키 없이 싸면 IllegalStateException 이다")
    void sealWithoutKeyFails() {
        assertThatThrownBy(() -> AesGcmSeal.ofBase64Key("").seal(PLAIN, BINDING))
                .isInstanceOf(IllegalStateException.class);
    }
}
