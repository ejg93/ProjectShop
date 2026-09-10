package com.projectshop.shop.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

/**
 * 배송완료가 굳히는 기한 둘(청크 {@code Q19}).
 *
 * <p><b>컨테이너를 안 띄운다.</b> 휴일을 인자로 받으므로 빠른 레인에서 돈다 —
 * 이 계산이 서비스 안에 있는 동안은 법 기한 하나를 확인하는 데 3분이 들었다.
 *
 * <p>날짜를 고정해서 쓴다. {@code LocalDate.now()} 를 쓰면 돌리는 날에 따라 답이 갈린다(`D15`).
 */
class OrderDeadlinesTest {

    /** 2026년 실제 공휴일 일부. 이 테스트가 쓰는 구간만 담는다 */
    private static final Set<LocalDate> HOLIDAYS_2026 = Set.of(
            LocalDate.of(2026, 8, 15),   // 광복절 (토)
            LocalDate.of(2026, 8, 17),   // 대체공휴일 (월)
            LocalDate.of(2026, 9, 24),   // 추석 연휴
            LocalDate.of(2026, 9, 25),
            LocalDate.of(2026, 9, 26));

    @Test
    @DisplayName("평일 배송완료면 7일째와 8일째가 그대로다")
    void plainWeekdays() {
        // 2026-09-07(월) 배송완료 → 7일째 9/14(월), 8일째 9/15(화). 둘 다 평일이다.
        OrderDeadlines d = OrderDeadlines.of(LocalDate.of(2026, 9, 7), HOLIDAYS_2026);

        assertThat(d.withdrawalLastDay()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(d.autoConfirmLastDay()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("말일이 토요일이면 다음 월요일로 민다")
    void shiftsOverWeekend() {
        // 2026-09-05(토) 배송완료 → 7일째 9/12(토) → 9/14(월)로 밀린다.
        OrderDeadlines d = OrderDeadlines.of(LocalDate.of(2026, 9, 5), HOLIDAYS_2026);

        assertThat(d.withdrawalLastDay())
                .as("기간은 사용자에게 유리한 쪽으로 늘어난다(`D10`)")
                .isEqualTo(LocalDate.of(2026, 9, 14));
    }

    @Test
    @DisplayName("말일 보정이 두 기한을 붙여도 자동확정이 하루 뒤로 간다")
    void keepsAutoConfirmAfterWithdrawal() {
        // 2026-08-10(월) 배송완료 → 7일째 8/17 인데 대체공휴일이라 8/18 로 밀린다.
        // 8일째도 8/18 이라 보정 없이 계산하면 두 기한이 같은 날이 된다.
        OrderDeadlines d = OrderDeadlines.of(LocalDate.of(2026, 8, 10), HOLIDAYS_2026);

        assertThat(d.withdrawalLastDay()).isEqualTo(LocalDate.of(2026, 8, 18));
        assertThat(d.autoConfirmLastDay())
                .as("같은 날이면 청약철회가 살아 있는 자정에 확정 배치가 돈다(`D10`)")
                .isEqualTo(LocalDate.of(2026, 8, 19));
    }

    @Test
    @DisplayName("연휴가 이어지면 그만큼 더 민다")
    void shiftsOverLongHoliday() {
        // 2026-09-16(수) 배송완료 → 7일째 9/23(수)는 평일. 8일째 9/24 부터 추석 사흘이라
        // 자동확정이 9/28(월)로 간다.
        OrderDeadlines d = OrderDeadlines.of(LocalDate.of(2026, 9, 16), HOLIDAYS_2026);

        assertThat(d.withdrawalLastDay()).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(d.autoConfirmLastDay()).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    @DisplayName("자동확정은 언제나 청약철회 말일보다 뒤다")
    void autoConfirmNeverPrecedesWithdrawal() {
        // 한 해를 통째로 훑어 불변식 하나를 본다. 이 종류는 컨테이너를 띄우면 못 돌린다.
        LocalDate day = LocalDate.of(2026, 1, 1);

        while (day.isBefore(LocalDate.of(2027, 1, 1))) {
            OrderDeadlines d = OrderDeadlines.of(day, HOLIDAYS_2026);
            assertThat(d.autoConfirmLastDay())
                    .as("%s 배송완료", day)
                    .isAfter(d.withdrawalLastDay());
            day = day.plusDays(1);
        }
    }

    @Test
    @DisplayName("휴일 집합이 비어도 주말 보정은 그대로다")
    void worksWithoutHolidayTable() {
        // `holiday` 표가 빈 연도에서도 요일은 날짜에서 나온다.
        OrderDeadlines d = OrderDeadlines.of(LocalDate.of(2026, 9, 5), Set.of());

        assertThat(d.withdrawalLastDay()).isEqualTo(LocalDate.of(2026, 9, 14));
        assertThat(d.autoConfirmLastDay()).isEqualTo(LocalDate.of(2026, 9, 15));
    }
}
