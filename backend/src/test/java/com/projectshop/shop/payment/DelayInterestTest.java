package com.projectshop.shop.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 환급이 늦으면 얼마가 붙는가(청크 12a-4, `D2` R5).
 *
 * <p>DB 가 필요 없다. 계산이 한 곳에 있어서 그 함수만 밟으면 되고,
 * <b>그것이 계산을 한 곳에 모은 값</b>이다 — 화면과 서비스가 각자 재면 여기서 못 밟는다.
 */
@DisplayName("환급 지연배상금")
class DelayInterestTest {

    private static final OffsetDateTime DUE =
            OffsetDateTime.of(2026, 8, 20, 0, 0, 0, 0, ZoneOffset.ofHours(9));

    @Nested
    @DisplayName("안 늦었으면")
    class NotOverdue {

        @Test
        @DisplayName("기한 안에 처리하면 0 이다")
        void chargesNothingBeforeDue() {
            assertThat(RefundService.delayInterest(100_000, DUE, DUE.minusHours(1))).isZero();
        }

        @Test
        @DisplayName("기한 정각도 0 이다")
        void chargesNothingExactlyAtDue() {
            // 「3영업일을 **초과한** 시점부터」라 정각은 아직 안 넘긴 것이다.
            assertThat(RefundService.delayInterest(100_000, DUE, DUE)).isZero();
        }
    }

    @Nested
    @DisplayName("늦었으면")
    class Overdue {

        @Test
        @DisplayName("연 15% 를 일수로 나눠 붙인다")
        void appliesAnnualRateByDays() {
            // 100,000 x 0.15 x 5 / 365 = 205.47...
            assertThat(RefundService.delayInterest(100_000, DUE, DUE.plusDays(5))).isEqualTo(206);
        }

        @Test
        @DisplayName("하루가 안 찼어도 하루로 센다")
        void countsPartialDayAsWhole() {
            // 한 시간 늦은 것에 0원을 물리면 「늦었는데 배상금이 0」이 된다(사용자 선택).
            assertThat(RefundService.delayInterest(100_000, DUE, DUE.plusHours(1)))
                    .isEqualTo(RefundService.delayInterest(100_000, DUE, DUE.plusDays(1)));
        }

        @Test
        @DisplayName("원 미만은 올린다")
        void roundsUpToWon() {
            // 1,000 x 0.15 x 1 / 365 = 0.41... — 버리면 0원이 되고 법이 정한 금액보다 적게 준다.
            assertThat(RefundService.delayInterest(1_000, DUE, DUE.plusDays(1))).isEqualTo(1);
        }

        @Test
        @DisplayName("늦으면 반드시 1원 이상이다")
        void alwaysChargesAtLeastOneWon() {
            assertThat(RefundService.delayInterest(1, DUE, DUE.plusHours(1)))
                    .describedAs("올림이라 0원이 되는 구간이 없다")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("오래 끌수록 는다")
        void growsWithDelay() {
            long fiveDays = RefundService.delayInterest(100_000, DUE, DUE.plusDays(5));
            long tenDays = RefundService.delayInterest(100_000, DUE, DUE.plusDays(10));

            assertThat(tenDays).isGreaterThan(fiveDays);
        }

        @Test
        @DisplayName("1년을 끌면 대금의 15% 다")
        void reachesAnnualRateAfterOneYear() {
            assertThat(RefundService.delayInterest(100_000, DUE, DUE.plusDays(365)))
                    .isEqualTo(15_000);
        }
    }
}
