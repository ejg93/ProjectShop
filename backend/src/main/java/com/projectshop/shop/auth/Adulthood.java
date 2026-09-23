package com.projectshop.shop.auth;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;

/**
 * 만 19세 이상인가(`11b`). 가입 입구가 먼저 보고 {@code app_user_adult_only} 트리거(`V112`)가 같은 셈을 한 번 더 한다.
 *
 * <p><b>나이는 민법 제158조로 센다</b> — 출생일을 산입해 만 나이로 센다. {@link Period#between} 이 그렇게 센다:
 * 생일 당일에 한 살이 늘고, 윤일(2월 29일)생은 평년에 3월 1일에 는다(제160조제3항). 트리거의 {@code age()} 와 같다.
 */
final class Adulthood {

    /** 성년(민법 제4조, `D2` R13). 이보다 어리면 가입을 안 받는다 */
    static final int ADULT_AGE = 19;

    /** 「오늘」은 KST 다(`time-rules.md` 「판단은 KST」) */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private Adulthood() {
    }

    static boolean isAdult(LocalDate birthDate, LocalDate today) {
        return Period.between(birthDate, today).getYears() >= ADULT_AGE;
    }

    static LocalDate todayKst() {
        return LocalDate.now(KST);
    }
}
