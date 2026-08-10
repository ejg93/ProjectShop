package com.projectshop.shop.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 영업일을 판단한다(`D10`).
 *
 * <p>영업일이 아닌 날은 토·일과 {@code holiday} 표에 있는 날이다.
 * <b>토·일을 표에 안 담는 이유</b>는 요일이 날짜에서 나와서다 — 담으면 같은 사실이 두 곳에 있다.
 *
 * <p><b>쓰는 자리가 둘뿐이다</b>(`D10`). 기한의 말일 보정과 정산 지급일이고,
 * 그 밖의 기간은 역일로 센다. 넓게 쓰면 {@code holiday} 가 빈 연도에서 결과가 조용히 틀린다.
 */
@Component
public class BusinessCalendar {

    /**
     * 업무 판단의 시간대(`D10`). "오늘"·"말일" 은 전부 이 기준이다.
     *
     * <p>저장은 UTC 지만 며칠째인지를 세는 것은 KST 다. 둘을 안 가르면
     * 밤 9시 이후에 만든 주문이 하루 일찍 만료된다.
     */
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 이만큼 밀어도 영업일이 안 나오면 표가 잘못된 것이다.
     *
     * <p>무한 반복을 막으려는 것이 아니라 <b>잘못을 드러내려는 것</b>이다.
     * 연휴가 아무리 길어도 열흘을 안 넘는다.
     */
    private static final int MAX_SHIFT_DAYS = 15;

    private final JdbcClient jdbc;

    BusinessCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 그날이 영업일이면 그대로, 아니면 다음 영업일까지 민다 */
    public LocalDate nextBusinessDay(LocalDate date) {
        LocalDate moved = date;

        for (int shifted = 0; shifted <= MAX_SHIFT_DAYS; shifted++) {
            if (isBusinessDay(moved)) {
                return moved;
            }
            moved = moved.plusDays(1);
        }
        throw new IllegalStateException(
                "%s 부터 %d일을 밀어도 영업일이 없다. holiday 표를 본다".formatted(date, MAX_SHIFT_DAYS));
    }

    public boolean isBusinessDay(LocalDate date) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !isHoliday(date);
    }

    private boolean isHoliday(LocalDate date) {
        return jdbc.sql("select exists (select 1 from holiday where holiday_date = :date)")
                .param("date", date)
                .query(Boolean.class)
                .single();
    }
}
