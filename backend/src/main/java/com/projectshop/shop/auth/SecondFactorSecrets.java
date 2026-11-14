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
 *
 * <p><b>키 판은 둘까지 읽는다</b>(`Q461`). 돌리는 동안 옛 키를 {@code SECOND_FACTOR_SECRET_KEY_PREVIOUS} 로 같이 받아 옛 판 행을 풀고,
 * 로그인이 맞으면 서비스가 현재 키로 다시 싼다. 어느 판도 아닌 행은 {@link #readable(int)} 이 거짓이다 — 로그인이 그 등록을 지우고
 * 등록 단계로 보낸다(옛 판 행을 일괄 삭제하면 돌리는 날 전원이 비밀번호만으로 다시 등록해 2차 인증이 그날 빠진다).
 */
@Component
class SecondFactorSecrets {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorSecrets.class);

    private final AesGcmSeal aes;
    private final int keyVersion;
    private final AesGcmSeal previous;
    private final int previousKeyVersion;

    SecondFactorSecrets(@Value("${shop.second-factor.secret-key:}") String base64Key,
            @Value("${shop.second-factor.secret-key-version:1}") int keyVersion,
            @Value("${shop.second-factor.previous-secret-key:}") String previousBase64Key,
            @Value("${shop.second-factor.previous-secret-key-version:0}") int previousKeyVersion) {
        this.aes = AesGcmSeal.ofBase64Key(base64Key);
        this.keyVersion = keyVersion;
        this.previous = AesGcmSeal.ofBase64Key(previousBase64Key);
        this.previousKeyVersion = previousKeyVersion;
        if (!aes.available()) {
            log.warn("2차 인증 키가 없거나 꼴이 틀렸다 — 셀러·관리자·감사자 로그인이 503 이다"
                    + "(SECOND_FACTOR_SECRET_KEY, base64 32바이트)");
        }
    }

    boolean available() {
        return aes.available();
    }

    Sealed seal(long userId, byte[] secret) {
        requireKey();
        return new Sealed(aes.seal(secret, bindingOf(userId)), keyVersion);
    }

    /** 이 판의 행을 풀 수 있나 — 현재 판이거나, 키가 들어온 이전 판이다 */
    boolean readable(int version) {
        return version == keyVersion || version == previousKeyVersion && previousKeyVersion > 0 && previous.available();
    }

    /** 현재 키로 싼 판인가. 아니면 로그인이 다시 싼다 */
    boolean isCurrent(int version) {
        return version == keyVersion;
    }

    /** 푼다. 부르기 전에 {@link #readable(int)} 로 가른다 — 못 읽는 판이 여기까지 오면 코드 잘못이다 */
    byte[] open(long userId, byte[] ciphertext, int version) {
        requireKey();
        if (version == keyVersion) {
            return aes.open(ciphertext, bindingOf(userId));
        }
        if (readable(version)) {
            return previous.open(ciphertext, bindingOf(userId));
        }
        throw new IllegalStateException("2차 인증 비밀의 키 판을 못 읽는다: 저장 " + version + ", 지금 " + keyVersion);
    }

    private void requireKey() {
        if (!aes.available()) {
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
