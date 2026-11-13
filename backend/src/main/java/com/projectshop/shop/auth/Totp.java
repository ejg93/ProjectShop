package com.projectshop.shop.auth;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP(RFC 6238) 코드를 HMAC-SHA1 으로 계산하고 Base32(RFC 4648)로 비밀을 옮긴다(`Q450`, `D2` R45).
 *
 * <p><b>값은 인증 앱 기본값이다</b> — HMAC-SHA1 · 6자리 · 30초. RFC 6238 §5.2 가 권하는 스텝이고
 * 앱 대부분이 이 셋 말고는 {@code otpauth} 주소의 매개변수를 무시한다. 비밀은 20바이트(RFC 4226 §4 R6 의 160비트)다.
 *
 * <p><b>허용 창은 앞뒤 한 스텝이다</b>(RFC 6238 §5.2 「at most one time step」). 같은 스텝의 재사용 금지는
 * 저장소가 쥔다 — 여기는 어느 스텝과 맞았는지만 돌려준다.
 */
final class Totp {

    /** 코드 자릿수. 인증 앱 기본값이다(RFC 4226 §5.3) */
    private static final int DIGITS = 6;
    /** 코드를 자릿수로 자르는 나머지 — 10 의 {@link #DIGITS} 제곱 */
    private static final int MODULUS = 1_000_000;
    /** 시간 스텝(초). RFC 6238 §5.2 권고값 */
    private static final long STEP_SECONDS = 30;
    /** 앞뒤로 받는 스텝 수. RFC 6238 §5.2 */
    private static final int WINDOW = 1;
    /** 비밀 길이(바이트). RFC 4226 §4 R6 의 160비트 */
    private static final int SECRET_BYTES = 20;
    /** RFC 4648 §6 의 Base32 알파벳 */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() {
    }

    static long stepAt(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), STEP_SECONDS);
    }

    /** 그 스텝의 코드. 카운터는 8바이트 big-endian 이고 동적 절단은 RFC 4226 §5.3 대로다 */
    static String codeAt(byte[] secret, long step) {
        byte[] hash = hmacSha1(secret, ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format(Locale.ROOT, "%0" + DIGITS + "d", binary % MODULUS);
    }

    /** 코드가 맞은 스텝. 현재와 앞뒤 한 스텝만 본다. 꼴이 틀렸거나 안 맞으면 빈 값 */
    static OptionalLong matchingStep(byte[] secret, String code, long currentStep) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = currentStep - WINDOW; step <= currentStep + WINDOW; step++) {
            if (MessageDigest.isEqual(given, codeAt(secret, step).getBytes(StandardCharsets.US_ASCII))) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    static byte[] newSecret(SecureRandom random) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    /** Base32 로 낸다. 패딩은 안 붙인다 — 인증 앱이 받는 꼴이다 */
    static String base32(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    /** Base32 를 푼다. 패딩·공백·소문자를 받는다. 알파벳 밖 글자는 {@link IllegalArgumentException} */
    static byte[] fromBase32(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '=' || c == ' ') {
                continue;
            }
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Base32 밖 글자: " + c);
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    /** 인증 앱이 읽는 등록 주소. 발급자와 계정은 URL 인코딩한다 */
    static String otpauthUri(String issuer, String account, byte[] secret) {
        String encodedIssuer = encode(issuer);
        return "otpauth://totp/" + encodedIssuer + ":" + encode(account)
                + "?secret=" + base32(secret) + "&issuer=" + encodedIssuer;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 을 못 돌렸다", e);
        }
    }
}
