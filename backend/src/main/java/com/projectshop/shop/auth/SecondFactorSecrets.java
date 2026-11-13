package com.projectshop.shop.auth;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.AesGcmSeal;

/**
 * 2차 인증 비밀을 {@code SECOND_FACTOR_SECRET_KEY} 로 싸고 풀며 계정에 묶는다(`Q451`, 고시 제7조① `D2` R45).
 *
 * <p><b>키가 없으면 앱은 뜨고 2차 인증만 503 이다</b>({@code second-factor-key-missing}). 웹훅 키와 같은 이유로 기본 키를
 * 코드에 두지 않는다 — 변수를 안 넣은 배포가 알려진 키로 싸게 된다. 키를 웹훅과 가른 것은 하나가 새도 다른 쪽이 안 풀리게 하려는 것이다.
 *
 * <p><b>묶음 값은 {@code user_id} 다.</b> 표를 고칠 수 있는 사람이 남의 암호문을 제 행에 옮겨 붙여도 안 풀린다.
 */
@Component
class SecondFactorSecrets {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorSecrets.class);

    private final AesGcmSeal seal;
    private final int keyVersion;

    SecondFactorSecrets(@Value("${shop.second-factor.secret-key:}") String base64Key,
            @Value("${shop.second-factor.secret-key-version:1}") int keyVersion) {
        this.seal = AesGcmSeal.ofBase64Key(base64Key);
        this.keyVersion = keyVersion;
        if (!seal.available()) {
            log.warn("2차 인증 키가 없거나 꼴이 틀렸다 — 셀러·관리자·감사자 로그인이 503 이다"
                    + "(SECOND_FACTOR_SECRET_KEY, base64 32바이트)");
        }
    }

    boolean available() {
        return seal.available();
    }

    Sealed seal(long userId, byte[] secret) {
        requireKey();
        return new Sealed(seal.seal(secret, bindingOf(userId)), keyVersion);
    }

    /** 푼다. <b>키 판이 다르면 못 푼다</b> — 키를 바꾸는 절차는 아직 없다(판이 둘이 되는 날 세운다) */
    byte[] open(long userId, byte[] ciphertext, int version) {
        requireKey();
        if (version != keyVersion) {
            throw new IllegalStateException("2차 인증 비밀의 키 판이 다르다: 저장 " + version + ", 지금 " + keyVersion);
        }
        return seal.open(ciphertext, bindingOf(userId));
    }

    private void requireKey() {
        if (!seal.available()) {
            throw new ShopException(ErrorCode.SECOND_FACTOR_KEY_MISSING);
        }
    }

    private static byte[] bindingOf(long userId) {
        return Long.toString(userId).getBytes(StandardCharsets.UTF_8);
    }

    /** 싼 비밀과 그 키 판 */
    record Sealed(byte[] ciphertext, int keyVersion) {
    }
}
