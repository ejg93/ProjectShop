package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.payment.RefundMath.Item;
import com.projectshop.shop.payment.RefundMath.Portion;

/**
 * 환불 금액 계산(`Q54`). <b>DB 를 안 띄운다</b> — 빠른 레인이다(`D15`).
 *
 * <p>같은 규칙을 {@code RefundServiceTest} 도 밟지만 그쪽이 보는 것은 <b>흐름</b>이다 —
 * 요청 세 번이 누계로 이어지고 트리거가 상한을 막는지. 여기는 <b>값</b>만 본다.
 */
@DisplayName("환불 계산")
class RefundMathTest {

    /** 3개에 항목 수수료 1000. 3으로 안 나눠떨어져서 절사 잔액이 생긴다 */
    private static final long COMMISSION = 1_000;

    /**
     * 3개짜리 항목. <b>환불 대금 누계도 같이 채운다</b> — 수량만 세고 금액을 0 으로 두면
     * 「1개 돌려줬는데 돈은 안 나갔다」가 되고, `Q164` 가 더한 잔액 계산이 그 거짓을 읽는다.
     */
    private static Item item(int refundedQuantity, long refundedCommission) {
        return new Item(7L, 3, 10_000, COMMISSION, 0, refundedQuantity, refundedCommission,
                10_000L * refundedQuantity);
    }

    /** 3개 30,000 에 할인 1,000. 3으로 안 나눠떨어져서 여기도 절사 잔액이 생긴다 */
    private static Item discounted(int refundedQuantity, long refundedAmount) {
        return new Item(7L, 3, 10_000, COMMISSION, 1_000, refundedQuantity, 0, refundedAmount);
    }

    /**
     * 환불 대금이 <b>배분된 할인을 뺀다</b>(`Q164`).
     *
     * <p>안 빼면 둘이 난다 — <b>전액 환불이 결제액 상한에 걸려 막히고</b>, 부분 환불은
     * <b>소비자가 낸 것보다 많이 돌려준다.</b> `50` 이 결제액을 할인만큼 줄이면서 생긴 자리다.
     */
    @Nested
    @DisplayName("환불 대금은")
    class AmountRefund {

        @Test
        @DisplayName("배분된 할인을 뺀다")
        void 배분된_할인을_뺀다() {
            assertThat(RefundMath.amountRefund(discounted(0, 0), 3))
                    .as("30,000 짜리에 1,000 을 깎았으면 돌려줄 것은 29,000 이다")
                    .isEqualTo(29_000);
        }

        @Test
        @DisplayName("절사 잔액이 마지막 수량에 몰린다")
        void 절사_잔액이_마지막_수량에_몰린다() {
            long first = RefundMath.amountRefund(discounted(0, 0), 1);
            long second = RefundMath.amountRefund(discounted(1, first), 1);
            long third = RefundMath.amountRefund(discounted(2, first + second), 1);

            assertThat(first + second + third)
                    .as("나눠 돌려줘도 합이 실제 낸 값과 같아야 한다 — 1원이 남으면"
                            + " 통째로 환불했는데 낸 것보다 덜 돌아간다")
                    .isEqualTo(29_000);
        }

        @Test
        @DisplayName("할인이 없으면 항목 금액 그대로다")
        void 할인이_없으면_항목_금액_그대로다() {
            assertThat(RefundMath.amountRefund(item(0, 0), 3)).isEqualTo(30_000);
        }
    }

    @Nested
    @DisplayName("수수료 환급은")
    class CommissionRefund {

        @Test
        @DisplayName("수량 일부면 항목 수수료를 수량으로 절사한다")
        void splitsByQuantityWithTruncation() {
            assertThat(RefundMath.commissionRefund(item(0, 0), 1)).isEqualTo(333);
            assertThat(RefundMath.commissionRefund(item(1, 333), 1)).isEqualTo(333);
        }

        @Test
        @DisplayName("절사 잔액이 마지막 수량에 몰린다")
        void putsTheRoundingRemainderOnTheLastUnit() {
            long first = RefundMath.commissionRefund(item(0, 0), 1);
            long second = RefundMath.commissionRefund(item(1, first), 1);
            long third = RefundMath.commissionRefund(item(2, first + second), 1);

            assertThat(List.of(first, second, third)).containsExactly(333L, 333L, 334L);
            assertThat(first + second + third)
                    .as("통째로 환불하면 commission_refund 합이 commission_amount 와 같다"
                            + "(`money-invariants`). 1원이 남으면 그만큼 정산에 우리 몫이 남는다")
                    .isEqualTo(COMMISSION);
        }

        @Test
        @DisplayName("한 번에 다 돌려주는 것과 나눠 돌려주는 것이 같다")
        void doesNotDependOnHowManyTimesItIsSplit() {
            long atOnce = RefundMath.commissionRefund(item(0, 0), 3);

            assertThat(atOnce)
                    .as("마지막인지를 누계로 판단하므로 횟수가 값을 안 바꾼다")
                    .isEqualTo(COMMISSION);
        }
    }

    @Nested
    @DisplayName("돌려줄 항목은")
    class ResolvePortions {

        @Test
        @DisplayName("요청이 비면 남은 것 전부다")
        void takesEverythingRemainingWhenNoLinesAsked() {
            List<Portion> portions = RefundMath.resolvePortions(List.of(item(1, 333)), List.of());

            assertThat(portions).singleElement().satisfies(portion -> {
                assertThat(portion.quantity()).isEqualTo(2);
                assertThat(portion.amount()).isEqualTo(20_000);
                assertThat(portion.commissionRefund())
                        .as("남은 것 전부라 마지막 수량이 걸리고 잔액이 몰린다")
                        .isEqualTo(COMMISSION - 333);
            });
        }

        @Test
        @DisplayName("남은 수량보다 많이 못 잡는다")
        void refusesMoreThanRemaining() {
            assertThatThrownBy(() -> RefundMath.resolvePortions(List.of(item(1, 333)),
                    List.of(new RefundService.Line(7L, 3))))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_LIMIT));
        }

        @Test
        @DisplayName("이 묶음의 항목이 아니면 없는 항목과 같은 답이다")
        void hidesWhetherSomeoneElsesItemExists() {
            assertThatThrownBy(() -> RefundMath.resolvePortions(List.of(item(0, 0)),
                    List.of(new RefundService.Line(999L, 1))))
                    .as("가르면 항목 번호를 두드려서 남의 주문 구성을 셀 수 있다(`D5`)")
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.ORDER_NOT_FOUND));
        }

        @Test
        @DisplayName("이미 전부 환불된 묶음이면 거부한다")
        void refusesAnAlreadyEmptiedBundle() {
            assertThatThrownBy(() -> RefundMath.resolvePortions(List.of(item(3, COMMISSION)), List.of()))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_LIMIT));
        }
    }
}
