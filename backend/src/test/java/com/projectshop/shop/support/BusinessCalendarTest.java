package com.projectshop.shop.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * 영업일 계산(청크 {@code Q19}). <b>컨테이너를 안 띄운다</b> — 휴일을 인자로 받는다.
 *
 * <p>DB 를 타는 쪽({@code BusinessCalendar} 의 인스턴스 메서드)은 표에서 집합을 읽어
 * 여기 있는 계산을 부르는 껍데기라, 계산이 맞는지는 이 층에서 본다(`D15`).
 */
class BusinessCalendarTest {

    private static final Set<LocalDate> 광복절 = Set.of(LocalDate.of(2026, 8, 15));

    @Test
    @DisplayName("평일은 그대로 둔다")
    void keepsWeekday() {
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 9, 7), Set.of()))
                .isEqualTo(LocalDate.of(2026, 9, 7));
    }

    @Test
    @DisplayName("토요일은 다음 월요일로 민다")
    void shiftsWeekend() {
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 9, 5), Set.of()))
                .isEqualTo(LocalDate.of(2026, 9, 7));
    }

    @Test
    @DisplayName("휴일 집합에 있는 날도 민다")
    void shiftsHoliday() {
        // 2026-08-15 는 토요일이고 광복절이다. 둘 다여도 결과는 하나 — 다음 평일로 간다.
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 8, 14), 광복절))
                .as("8/14 는 금요일이라 안 밀린다")
                .isEqualTo(LocalDate.of(2026, 8, 14));
        assertThat(BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 8, 15), 광복절))
                .isEqualTo(LocalDate.of(2026, 8, 17));
    }

    @Test
    @DisplayName("3영업일은 주말이 끼면 역일로 닷새가 된다")
    void countsBusinessDaysOnly() {
        // 2026-09-03(목)에 요청 → 다음날부터 세서 9/4(금)·9/7(월)·9/8(화).
        assertThat(BusinessCalendar.plusBusinessDays(LocalDate.of(2026, 9, 3), 3, Set.of()))
                .as("초일을 안 넣는다(`D2` R5, 전자상거래법 제18조제2항)")
                .isEqualTo(LocalDate.of(2026, 9, 8));
    }

    @Test
    @DisplayName("밀어도 영업일이 없으면 표가 잘못된 것이라고 말한다")
    void failsLoudlyWhenTableIsWrong() {
        // 한 달을 통째로 휴일로 채운다. 무한 반복 대신 잘못을 드러내는 것이 이 상한의 목적이다.
        Set<LocalDate> 전부휴일 = new HashSet<>();
        LocalDate day = LocalDate.of(2026, 9, 1);
        while (day.isBefore(LocalDate.of(2026, 10, 1))) {
            전부휴일.add(day);
            day = day.plusDays(1);
        }

        assertThatThrownBy(() -> BusinessCalendar.nextBusinessDay(LocalDate.of(2026, 9, 7), 전부휴일))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("holiday 표를 본다");
    }

    @Test
    @DisplayName("말일 24시는 마이크로초에서 끊는다")
    void endOfDayStopsAtMicros() {
        // `LocalTime.MAX` 를 쓰면 Postgres 가 나노초를 올려 다음날 00:00 이 된다(`stack.md`).
        assertThat(BusinessCalendar.endOfDay(LocalDate.of(2026, 9, 7)).toString())
                .startsWith("2026-09-07T23:59:59.999999");
    }
}
