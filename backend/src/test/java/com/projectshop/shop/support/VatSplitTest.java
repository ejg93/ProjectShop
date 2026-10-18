package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 부가세 포함 금액의 역산(`Q274`, `D2` R17) */
class VatSplitTest {

    @Test
    @DisplayName("11,000원은 10,000원과 1,000원이다")
    void roundAmount() {
        VatSplit split = VatSplit.of(11_000);

        assertThat(split.supply()).isEqualTo(10_000);
        assertThat(split.vat()).isEqualTo(1_000);
    }

    @Test
    @DisplayName("7원은 6원과 1원이다 — 공급가액을 반올림한다")
    void roundsSupply() {
        VatSplit split = VatSplit.of(7);

        assertThat(split.supply()).isEqualTo(6);
        assertThat(split.vat()).isEqualTo(1);
    }

    @Test
    @DisplayName("0원은 둘 다 0이다")
    void zero() {
        VatSplit split = VatSplit.of(0);

        assertThat(split.supply()).isZero();
        assertThat(split.vat()).isZero();
    }

    /** 차감 줄과 그 되돌림이 같은 금액으로 나뉘어야 한 쌍이 정확히 지워진다 */
    @Test
    @DisplayName("음수는 부호만 다르고 같은 금액으로 나뉜다")
    void negativeMirrorsPositive() {
        VatSplit deduction = VatSplit.of(-1_000);
        VatSplit refund = VatSplit.of(1_000);

        assertThat(deduction.supply()).isEqualTo(-refund.supply()).isEqualTo(-909);
        assertThat(deduction.vat()).isEqualTo(-refund.vat()).isEqualTo(-91);
    }

    @Test
    @DisplayName("어느 금액이든 합이 원금이다")
    void sumIsAlwaysTheOriginal() {
        for (long amount = -2_000; amount <= 2_000; amount++) {
            VatSplit split = VatSplit.of(amount);
            assertThat(split.supply() + split.vat()).as("amount=%d", amount).isEqualTo(amount);
        }
    }

    @Test
    @DisplayName("원금의 역산이 아닌 값은 만들 수 없다")
    void rejectsMismatchedSum() {
        assertThatThrownBy(() -> new VatSplit(11_000, 10_000, 999))
                .isInstanceOf(IllegalArgumentException.class);
        // 합은 맞아도 역산이 아니다
        assertThatThrownBy(() -> new VatSplit(11_000, 0, 11_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
