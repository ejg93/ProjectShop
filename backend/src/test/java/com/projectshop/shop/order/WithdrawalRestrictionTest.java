package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 청약철회 제한이 실제로 서나(`D2` R4, 전자상거래법 제17조제2항 단서).
 *
 * <p><b>이 시험이 서비스 안에서는 못 서던 것이다</b>(`Q68`). 규칙이 {@code OrderService} 의
 * {@code private static} 이라 주문을 만들어야 닿았고, 그러면 <b>제한 규칙이 아니라 주문 생성을 시험</b>하게 된다.
 *
 * <p><b>동의가 성립 요건인 쪽이 핵심이다.</b> 주문 제작은 미리 알리고 동의를 받아야 제한이 서고,
 * 안 받았으면 <b>제한이 없는 것</b>이다 — 「값은 있는데 동의가 없다」를 제한으로 읽으면 법을 어긴다.
 */
@DisplayName("청약철회 제한")
class WithdrawalRestrictionTest {

    @Test
    @DisplayName("사유가 없으면 제한도 없다")
    void noReasonMeansNoRestriction() {
        assertThat(WithdrawalRestriction.agreed(null, true)).isNull();
    }

    @Test
    @DisplayName("복제 가능한 매체는 동의 없이도 선다")
    void digitalContentStandsWithoutConsent() {
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestriction.DIGITAL_CONTENT, false))
                .as("포장을 뜯으면 되돌릴 수 없어 동의가 요건이 아니다")
                .isEqualTo(WithdrawalRestriction.DIGITAL_CONTENT);
    }

    @Test
    @DisplayName("주문 제작은 동의가 있어야 선다")
    void madeToOrderNeedsConsent() {
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestriction.MADE_TO_ORDER, true))
                .isEqualTo(WithdrawalRestriction.MADE_TO_ORDER);
    }

    @Test
    @DisplayName("주문 제작에 동의가 없으면 제한이 없다")
    void madeToOrderWithoutConsentIsNoRestriction() {
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestriction.MADE_TO_ORDER, false))
                .as("동의가 성립 요건이다 — 값이 있다고 제한으로 읽으면 법을 어긴다")
                .isNull();
    }

    @Test
    @DisplayName("모르는 사유는 제한이 아니다")
    void unknownReasonIsNoRestriction() {
        assertThat(WithdrawalRestriction.agreed("whatever", true))
                .as("법이 열거한 것만 제한이다 (product_withdrawal_reason_check)")
                .isNull();
    }
}
