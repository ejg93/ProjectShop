package com.projectshop.shop.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 웹훅 시크릿 암호(`29`). <b>키가 없으면 앱은 뜨고 웹훅만 503</b>이어야 한다 — 기본 키를 코드에 두면 변수를 안 넣은 배포가
 * 알려진 키로 싼다.
 */
@DisplayName("웹훅 시크릿 암호")
class WebhookSecretCipherTest {

    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    @DisplayName("싸고 풀면 같다 — 같은 평문도 쌀 때마다 다르다")
    void roundTrip() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(KEY, 1);
        byte[] plain = "whsec-plain".getBytes(StandardCharsets.UTF_8);
        byte[] binding = cipher.bindingOf(7L, "https://example.com/hook");

        byte[] first = cipher.encrypt(plain, binding);
        byte[] second = cipher.encrypt(plain, binding);

        assertThat(cipher.decrypt(first, 1, binding)).isEqualTo(plain);
        assertThat(first).as("IV 가 매번 달라야 같은 시크릿이 같은 암호문으로 안 보인다").isNotEqualTo(second);
    }

    @Test
    @DisplayName("키가 없거나 꼴이 틀리면 503 이다")
    void missingKeyIs503() {
        for (String key : new String[] {"", "   ", "not-base64!", "AAEC"}) {
            WebhookSecretCipher cipher = new WebhookSecretCipher(key, 1);

            assertThat(cipher.available()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt(new byte[] {1}, new byte[0]))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.WEBHOOK_KEY_MISSING));
        }
    }

    @Test
    @DisplayName("키 판이 다르면 못 푼다")
    void refusesOtherKeyVersion() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(KEY, 2);

        assertThatThrownBy(() -> cipher.decrypt(cipher.encrypt(new byte[] {1}, new byte[0]), 1, new byte[0]))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * 암호문을 남의 행에 옮겨 붙이면 못 푼다(마무리 47차 독립 리뷰). 풀리면 표를 고칠 수 있는 사람이 남의 시크릿으로 서명된
     * 사건을 제 주소로 받아 그 셀러의 수신 서버에 되던질 수 있다.
     */
    @Test
    @DisplayName("다른 셀러·주소의 행에 옮겨 붙인 암호문은 못 푼다")
    void boundToItsEndpoint() {
        WebhookSecretCipher cipher = new WebhookSecretCipher(KEY, 1);
        byte[] sealed = cipher.encrypt(new byte[] {1, 2, 3}, cipher.bindingOf(7L, "https://a.example/hook"));

        assertThatThrownBy(() -> cipher.decrypt(sealed, 1, cipher.bindingOf(8L, "https://a.example/hook")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(sealed, 1, cipher.bindingOf(7L, "https://b.example/hook")))
                .isInstanceOf(IllegalStateException.class);
    }
}
