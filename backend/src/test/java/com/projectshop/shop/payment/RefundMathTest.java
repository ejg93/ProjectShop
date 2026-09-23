package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
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
                  10_000L * refundedQuantity, 0);
    }

    /** 3개 30,000 에 할인 1,000. 3으로 안 나눠떨어져서 여기도 절사 잔액이 생긴다 */
    private static Item discounted(int refundedQuantity, long refundedAmount) {
        return discounted(refundedQuantity, refundedAmount, 0);
    }

    private static Item discounted(int refundedQuantity, long refundedAmount, long refundedDiscount) {
        return new Item(7L, 3, 10_000, COMMISSION, 1_000, refundedQuantity, 0, refundedAmount,
                refundedDiscount);
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

        /**
         * 수량 1 로만 재면 {@code a × q ÷ n} 의 곱과 나눗셈을 뒤집어도 같은 값이 나온다 — 변이 시험이 그 자리를 짚었다(`69`).
         */
        @Test
        @DisplayName("수량 일부는 비율로 자른다")
        void 수량_일부는_비율로_자른다() {
            assertThat(RefundMath.amountRefund(discounted(0, 0), 2))
                    .as("29,000 × 2 ÷ 3 을 버린 값이다")
                    .isEqualTo(19_333);
        }

        @Test
        @DisplayName("할인이 없으면 항목 금액 그대로다")
        void 할인이_없으면_항목_금액_그대로다() {
            assertThat(RefundMath.amountRefund(item(0, 0), 3)).isEqualTo(30_000);
        }
    }

    /**
     * 되돌린 할인을 박제한다(`Q168`). 정산이 {@code amount + discount_refund} 로 판매되돌림을 세우므로
     * <b>둘의 합이 할인 전 값과 끝까지 같아야</b> 판매와 판매되돌림이 같은 축에 선다.
     */
    @Nested
    @DisplayName("되돌릴 할인은")
    class DiscountRefund {

        @Test
        @DisplayName("통째로면 배분된 할인 전부다")
        void 통째로면_배분된_할인_전부다() {
            assertThat(RefundMath.discountRefund(discounted(0, 0), 3)).isEqualTo(1_000);
        }

        @Test
        @DisplayName("나눠 돌려줘도 합이 배분된 할인과 같다")
        void 나눠_돌려줘도_합이_배분된_할인과_같다() {
            long first = RefundMath.discountRefund(discounted(0, 0, 0), 1);
            long second = RefundMath.discountRefund(discounted(1, 0, first), 1);
            long third = RefundMath.discountRefund(discounted(2, 0, first + second), 1);

            assertThat(first + second + third)
                    .as("비율로 매번 버리면 999 가 되고, 셀러는 판 것보다 1원 덜 토해 낸다")
                    .isEqualTo(1_000);
        }

        @Test
        @DisplayName("수량 일부는 비율로 자른다")
        void 할인도_수량_일부는_비율로_자른다() {
            assertThat(RefundMath.discountRefund(discounted(0, 0, 0), 2)).isEqualTo(666);
        }

        @Test
        @DisplayName("대금과 더하면 할인 전 값이다")
        void 대금과_더하면_할인_전_값이다() {
            long amount1 = RefundMath.amountRefund(discounted(0, 0, 0), 1);
            long discount1 = RefundMath.discountRefund(discounted(0, 0, 0), 1);
            long amount2 = RefundMath.amountRefund(discounted(1, amount1, discount1), 2);
            long discount2 = RefundMath.discountRefund(discounted(1, amount1, discount1), 2);

            assertThat(amount1 + discount1 + amount2 + discount2)
                    .as("정산의 sale_reversal 합이 sale(line_amount)과 같아야 한다")
                    .isEqualTo(30_000);
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
        @DisplayName("수량 둘이면 둘 몫이다")
        void splitsTwoUnits() {
            assertThat(RefundMath.commissionRefund(item(0, 0), 2)).isEqualTo(666);
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
        @DisplayName("요청이 비면 다 돌려받은 항목은 건너뛴다")
        void skipsFullyRefundedItemsWhenNoLinesAsked() {
            Item other = new Item(8L, 2, 5_000, 500, 0, 0, 0, 0, 0);

            assertThat(RefundMath.resolvePortions(List.of(item(3, COMMISSION), other), List.of()))
                    .singleElement()
                    .satisfies(portion -> assertThat(portion.orderItemId()).isEqualTo(8L));
        }

        @Test
        @DisplayName("수량 0 은 못 잡는다")
        void refusesZeroQuantity() {
            assertThatThrownBy(() -> RefundMath.resolvePortions(List.of(item(0, 0)),
                    List.of(new RefundService.Line(7L, 0))))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_LIMIT));
        }

        @Test
        @DisplayName("이미 전부 환불된 묶음이면 거부한다")
        void refusesAnAlreadyEmptiedBundle() {
            assertThatThrownBy(() -> RefundMath.resolvePortions(List.of(item(3, COMMISSION)), List.of()))
                    .isInstanceOfSatisfying(ShopException.class, e ->
                            assertThat(e.code()).isEqualTo(ErrorCode.REFUND_EXCEEDS_LIMIT));
        }
    }

    /**
     * 지연배상금(`D2` R5, 전자상거래법 시행령 제21조의3 — 연 15%).
     *
     * <p><b>빠른 레인이 한 번도 안 부르던 식이다</b> — 변이 시험이 NO_COVERAGE 로 짚었다(`69`). 넘긴 날은 하루라도
     * 하루로 세고 원 단위는 올린다. 고객이 받을 돈이라 모자라게 자르지 않는다.
     */
    @Nested
    @DisplayName("지연배상금은")
    class DelayInterest {

        private static final OffsetDateTime DUE = OffsetDateTime.parse("2026-09-10T23:59:59+09:00");

        @Test
        @DisplayName("기한 안이면 0 이다")
        void isZeroWhenOnTime() {
            assertThat(RefundMath.delayInterest(100_000, DUE, DUE)).isZero();
            assertThat(RefundMath.delayInterest(100_000, DUE, DUE.minusHours(1))).isZero();
        }

        @Test
        @DisplayName("한 시간을 넘겨도 하루로 세고 원 단위는 올린다")
        void countsAPartialDayAsADay() {
            assertThat(RefundMath.delayInterest(100_000, DUE, DUE.plusHours(1)))
                    .as("100,000 × 0.15 × 1 ÷ 365 = 41.09 → 42")
                    .isEqualTo(42);
        }

        @Test
        @DisplayName("날 수만큼 늘어난다")
        void growsWithDays() {
            assertThat(RefundMath.delayInterest(100_000, DUE, DUE.plusDays(2)))
                    .as("100,000 × 0.15 × 2 ÷ 365 = 82.19 → 83")
                    .isEqualTo(83);
            assertThat(RefundMath.delayInterest(100_000, DUE, DUE.plusDays(2).plusMinutes(1)))
                    .as("이틀하고 1분이면 사흘이다 — 100,000 × 0.15 × 3 ÷ 365 = 123.28 → 124")
                    .isEqualTo(124);
        }
    }
}
