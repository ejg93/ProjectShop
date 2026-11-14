package com.projectshop.shop.product;

import java.util.Arrays;
import java.util.Locale;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;

/**
 * 재개 요구에 대한 판단. 저장값은 {@code copyright_reinstatement.decision} 이고 목록은
 * {@code copyright_reinstatement_decision_check} 다(`Q465`, 저작권법 시행령 제43조 `D2` R42).
 */
enum ReinstatementDecision {

    /** 요구를 받아들여 예정일에 다시 게시한다 */
    SCHEDULED,

    /** 요구를 물리친다. 사진은 숨긴 채로 기한 뒤 지운다 */
    REFUSED;

    /** DB 에 들어가는 값. {@code EnumConstraintTest} 가 이 이름의 메서드를 리플렉션으로 읽는다 */
    String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** 저장값을 판단으로 되돌린다. 모르는 값이면 표가 깨진 것이라 터진다 */
    static ReinstatementDecision of(String code) {
        return Arrays.stream(values())
                .filter(decision -> decision.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("모르는 재개 판단이다: " + code));
    }

    /** 바깥에서 온 값으로 고른다. <b>모르면 400 이다</b>({@link CopyrightDecision#ofRequest} 와 같은 까닭) */
    static ReinstatementDecision ofRequest(String code) {
        return Arrays.stream(values())
                .filter(decision -> decision.name().equals(code) || decision.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new ShopException(ErrorCode.VALIDATION_FAILED));
    }
}
