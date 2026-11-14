package com.projectshop.shop.auth;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;

import com.projectshop.shop.auth.ShopUserDetailsService.ShopUser;

/**
 * 비밀번호는 맞았고 2차 인증 코드를 기다리는 로그인(`Q452`). 세션 속성으로만 산다 — 인증된 세션이 아니다.
 *
 * <p><b>5분 뒤에는 없는 것과 같다.</b> 비밀번호를 친 사람이 자리를 비운 사이 그 세션으로 코드만 맞히면 들어가는 창을 좁힌다.
 */
record SecondFactorPending(ShopUser user, Instant startedAt) implements Serializable {

    /** 세션 속성 이름 */
    static final String ATTRIBUTE = SecondFactorPending.class.getName();

    /** 코드를 기다리는 시간 */
    static final Duration TTL = Duration.ofMinutes(5);

    @Serial
    private static final long serialVersionUID = 1L;

    boolean expiredAt(Instant now) {
        return now.isAfter(startedAt.plus(TTL));
    }
}
