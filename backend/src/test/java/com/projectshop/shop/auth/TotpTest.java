package com.projectshop.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * TOTP 계산기를 RFC 원문의 시험 벡터로 묶는다(`Q450`).
 *
 * <p><b>벡터가 구현을 묶는다.</b> 동적 절단·카운터 바이트 순서를 하나라도 틀리면 인증 앱이 낸 코드와 안 맞는데,
 * 그것은 화면에서 「코드가 틀렸다」로만 보인다 — 시험이 아니면 원인이 안 갈린다.
 * 벡터는 RFC 6238 부록 B(SHA1 열) · RFC 4226 부록 D · RFC 4648 §10 에서 옮겼다(2026-11-13 `rfc-editor.org`).
 */
@DisplayName("TOTP 계산기")
class TotpTest {

    /** RFC 4226 부록 A·D 와 RFC 6238 부록 B 의 SHA1 시험 비밀 — ASCII 「12345678901234567890」 */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest(name = "RFC 6238 — {0}초")
    @CsvSource({
            "59, 287082",
            "1111111109, 081804",
            "1111111111, 050471",
            "1234567890, 005924",
            "2000000000, 279037",
            "20000000000, 353130"
    })
    @DisplayName("RFC 6238 벡터의 끝 여섯 자리가 나온다")
    void rfc6238Vectors(long epochSecond, String expected) {
        long step = Totp.stepAt(Instant.ofEpochSecond(epochSecond));
        assertThat(Totp.codeAt(RFC_SECRET, step)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "RFC 4226 — 카운터 {0}")
    @CsvSource({
            "0, 755224", "1, 287082", "2, 359152", "3, 969429", "4, 338314",
            "5, 254676", "6, 287922", "7, 162583", "8, 399871", "9, 520489"
    })
    @DisplayName("RFC 4226 열이 나온다")
    void rfc4226Vectors(long counter, String expected) {
        assertThat(Totp.codeAt(RFC_SECRET, counter)).isEqualTo(expected);
    }

    @Test
    @DisplayName("현재와 앞뒤 한 스텝의 코드를 받는다")
    void acceptsAdjacentSteps() {
        long now = 1000;
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now), now)).hasValue(now);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now - 1), now)).hasValue(now - 1);
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + 1), now)).hasValue(now + 1);
    }

    @Test
    @DisplayName("두 스텝 밖의 코드는 빈 값이다")
    void rejectsOutsideWindow() {
        long now = 1000;
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now - 2), now)).isEqualTo(OptionalLong.empty());
        assertThat(Totp.matchingStep(RFC_SECRET, Totp.codeAt(RFC_SECRET, now + 2), now)).isEqualTo(OptionalLong.empty());
    }

    @Test
    @DisplayName("꼴이 틀린 코드는 빈 값이다")
    void rejectsMalformedCode() {
        long now = 1000;
        String code = Totp.codeAt(RFC_SECRET, now);
        assertThat(Totp.matchingStep(RFC_SECRET, code.substring(1), now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, code.substring(0, 5) + "a", now)).isEmpty();
        assertThat(Totp.matchingStep(RFC_SECRET, null, now)).isEmpty();
    }

    @Test
    @DisplayName("RFC 4648 벡터를 패딩 없이 낸다")
    void base32Vector() {
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.fromBase32("MZXW6YTBOI======")).isEqualTo("foobar".getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    @DisplayName("새 비밀은 20바이트이고 Base32 왕복이 된다")
    void newSecretRoundTrips() {
        byte[] secret = Totp.newSecret(new SecureRandom());
        assertThat(secret).hasSize(20);
        assertThat(Totp.fromBase32(Totp.base32(secret))).isEqualTo(secret);
        assertThat(Totp.fromBase32(Totp.base32(secret).toLowerCase(Locale.ROOT))).isEqualTo(secret);
    }

    @Test
    @DisplayName("otpauth 주소에 발급자와 계정과 비밀이 든다")
    void otpauthUri() {
        String uri = Totp.otpauthUri("ProjectShop", "admin@example.com", "foobar".getBytes(StandardCharsets.US_ASCII));
        assertThat(uri).isEqualTo(
                "otpauth://totp/ProjectShop:admin%40example.com?secret=MZXW6YTBOI&issuer=ProjectShop");
    }
}
