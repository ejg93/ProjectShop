package com.projectshop.shop.webhook;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 웹훅 시크릿을 AES-GCM 으로 싸고 푼다(`29`, `D14`).
 *
 * <p><b>해시가 아니라 암호다.</b> 발송기(`30`)가 HMAC 을 만들려면 원문 키가 있어야 한다. 키는 {@code WEBHOOK_SECRET_KEY}
 * (base64, 32바이트)고 표에 없다 — DB 덤프가 새도 시크릿이 안 샌다.
 *
 * <p><b>키가 없으면 앱은 뜨고 웹훅만 503 이다.</b> 기본값 키를 코드에 두면 변수를 안 넣은 배포가 알려진 키로 싸게 된다 —
 * 그래서 {@code application.yml} 의 기본이 빈 값이고 시험 기반 클래스만 시험 키를 준다. 꼴이 틀린 키도 없는 것과 같이 다룬다.
 */
@Component
class WebhookSecretCipher {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretCipher.class);

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final int keyVersion;
    private final SecureRandom random = new SecureRandom();

    WebhookSecretCipher(@Value("${shop.webhook.secret-key:}") String base64Key,
            @Value("${shop.webhook.secret-key-version:1}") int keyVersion) {
        this.key = keyOf(base64Key);
        this.keyVersion = keyVersion;
        if (key == null) {
            log.warn("웹훅 시크릿 키가 없거나 꼴이 틀렸다 — 웹훅 등록·발송이 503 이다(WEBHOOK_SECRET_KEY, base64 32바이트)");
        }
    }

    boolean available() {
        return key != null;
    }

    int keyVersion() {
        return keyVersion;
    }

    /** 평문을 싼다. 앞 12바이트가 IV 고 뒤가 암호문과 태그다 */
    byte[] encrypt(byte[] plain) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain);
            return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 시크릿을 못 쌌다", e);
        }
    }

    /** 푼다. <b>키 판이 다르면 못 푼다</b> — 키를 바꾸는 절차는 아직 없다(판이 둘이 되는 날 세운다) */
    byte[] decrypt(byte[] stored, int version) {
        requireKey();
        if (version != keyVersion) {
            throw new IllegalStateException("웹훅 시크릿의 키 판이 다르다: 저장 " + version + ", 지금 " + keyVersion);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            return cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("웹훅 시크릿을 못 풀었다", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new ShopException(ErrorCode.WEBHOOK_KEY_MISSING);
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
