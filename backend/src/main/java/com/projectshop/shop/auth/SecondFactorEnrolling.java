package com.projectshop.shop.auth;

import java.io.Serial;
import java.io.Serializable;

/**
 * 등록 중인 2차 인증 비밀(`Q452`). 세션 속성으로만 산다 — 첫 코드가 맞으면 {@code user_second_factor} 로 간다.
 *
 * <p><b>싼 채로 둔다</b>(고시 제7조① `D2` R45). 세션은 Redis 에 들어서 평문으로 두면 저장소에 인증정보가 생긴다.
 * 표에 행을 안 세우는 것이 「등록 중」 상태를 구조로 없애는 길이다(`Q451`).
 */
record SecondFactorEnrolling(byte[] ciphertext, int keyVersion) implements Serializable {

    /** 세션 속성 이름 */
    static final String ATTRIBUTE = SecondFactorEnrolling.class.getName();

    @Serial
    private static final long serialVersionUID = 1L;
}
