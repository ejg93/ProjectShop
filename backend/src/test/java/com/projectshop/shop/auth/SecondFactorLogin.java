package com.projectshop.shop.auth;

import java.time.Instant;

/**
 * 다른 패키지의 시험이 2차 인증 코드를 만드는 입구(`Q451`).
 *
 * <p>{@link Totp} 는 {@code auth} 밖에 안 연다 — 운영 코드가 계산기를 직접 부르면 재사용 금지(저장소의 조건부 UPDATE)를
 * 비껴간다. 시험만 이 입구로 코드를 얻는다.
 */
public final class SecondFactorLogin {

    private SecondFactorLogin() {
    }

    /** 그 비밀의 그 시각 코드 */
    public static String codeAt(byte[] secret, Instant now) {
        return Totp.codeAt(secret, Totp.stepAt(now));
    }
}
