package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.support.WithdrawalRestrictionReason;

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
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestrictionReason.DIGITAL_CONTENT, false))
                .as("포장을 뜯으면 되돌릴 수 없어 동의가 요건이 아니다")
                .isEqualTo(WithdrawalRestrictionReason.DIGITAL_CONTENT);
    }

    @Test
    @DisplayName("주문 제작은 동의가 있어야 선다")
    void madeToOrderNeedsConsent() {
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestrictionReason.MADE_TO_ORDER, true))
                .isEqualTo(WithdrawalRestrictionReason.MADE_TO_ORDER);
    }

    @Test
    @DisplayName("주문 제작에 동의가 없으면 제한이 없다")
    void madeToOrderWithoutConsentIsNoRestriction() {
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestrictionReason.MADE_TO_ORDER, false))
                .as("동의가 성립 요건이다 — 값이 있다고 제한으로 읽으면 법을 어긴다")
                .isNull();
    }

    /**
     * <b>모르는 글자를 넘기던 시험이 여기 있었다.</b> 이제 그것을 <b>쓸 수가 없다</b> —
     * 서명이 열거형이라 컴파일이 막는다({@code Q125}). 그 자리를 실물 사례로 바꾼다.
     */
    @Test
    @DisplayName("복제 가능한 매체는 주문 시점에 절대 안 선다")
    void copyableMediaNeverStandsAtOrderTime() {
        assertThat(WithdrawalRestriction.agreed(WithdrawalRestrictionReason.COPYABLE_MEDIA, true))
                .as("포장 훼손은 물건이 돌아와야 아는 사실이고 그 입증은 우리 몫이다"
                        + " (제17조제5항, V32) — 접수를 막는 근거가 못 된다")
                .isNull();
    }
}
