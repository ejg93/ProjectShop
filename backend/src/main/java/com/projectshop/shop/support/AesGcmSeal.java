package com.projectshop.shop.support;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 비밀을 AES-256-GCM 으로 싸고 풀며 암호문을 묶음 값(AAD)에 묶는다(`Q450`).
 *
 * <p><b>쓰는 곳이 둘이다</b> — 웹훅 시크릿(`29`)과 2차 인증 비밀(`Q451`, 고시 제7조① `D2` R45). 둘 다 원문을 다시 써야 해서
 * 해시가 아니라 암호다. 키 설정과 키가 없을 때의 오류 코드는 부르는 쪽이 고른다 — 여기는 키가 없으면
 * {@link IllegalStateException} 만 낸다.
 *
 * <p><b>꼴이 틀린 키는 없는 것과 같다.</b> base64 가 아니거나 32바이트가 아니면 {@link #available()} 이 false 다.
 * 빈이 아니다 — 키마다 하나를 만든다.
 */
public final class AesGcmSeal {

    /** AES-256 키 길이. 이것과 다른 꼴의 키는 없는 것과 같이 다룬다 */
    private static final int KEY_BYTES = 32;
    /** GCM 의 IV 길이. NIST SP 800-38D 가 권하는 96비트다 */
    private static final int IV_BYTES = 12;
    /** GCM 인증 태그 길이. 같은 규격의 최대값인 128비트다 */
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private AesGcmSeal(SecretKeySpec key) {
        this.key = key;
    }

    /** base64 키로 만든다. 비었거나 꼴이 틀리면 키 없는 봉인이다 */
    public static AesGcmSeal ofBase64Key(String base64Key) {
        return new AesGcmSeal(keyOf(base64Key));
    }

    public boolean available() {
        return key != null;
    }

    /** 평문을 싼다. 앞 12바이트가 IV 고 뒤가 암호문과 태그다 */
    public byte[] seal(byte[] plain, byte[] binding) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(binding);
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("못 쌌다", e);
        }
    }

    /** 푼다. 묶음 값이나 키가 다르면 태그가 안 맞아 {@link IllegalStateException} 이다 */
    public byte[] open(byte[] sealed, byte[] binding) {
        requireKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            cipher.updateAAD(binding);
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("못 풀었다", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("봉인 키가 없다");
        }
    }

    private static SecretKeySpec keyOf(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            return raw.length == KEY_BYTES ? new SecretKeySpec(raw, "AES") : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
