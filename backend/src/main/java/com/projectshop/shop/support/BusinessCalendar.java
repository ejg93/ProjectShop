package com.projectshop.shop.support;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;

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

    /** 하루의 끝. Postgres 가 담는 마지막 눈금이 마이크로초라 여섯 자리에서 끊는다 */
    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59, 999_999_000);

    private final JdbcClient jdbc;

    BusinessCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 그날이 영업일이면 그대로, 아니면 다음 영업일까지 민다 */
    public LocalDate nextBusinessDay(LocalDate date) {
        return nextBusinessDay(date, holidaysAround(date, 0));
    }

    /**
     * 위와 같은 계산인데 <b>휴일 집합을 받는다</b>(`Q19`). DB 를 모른다.
     *
     * <p>이 자리가 순수 계산이라 <b>단위 층에서 검증한다</b>(`D15`) — 컨테이너를 안 띄우므로
     * 법 기한(`D2` R3·R5)을 확인하는 되먹임이 3분에서 초 단위로 온다.
     * 인스턴스 메서드는 표에서 집합을 읽어 이것을 부르는 껍데기다.
     */
    public static LocalDate nextBusinessDay(LocalDate date, Set<LocalDate> holidays) {
        LocalDate moved = date;

        for (int shifted = 0; shifted <= MAX_SHIFT_DAYS; shifted++) {
            if (isBusinessDay(moved, holidays)) {
                return moved;
            }
            moved = moved.plusDays(1);
        }
        throw new IllegalStateException(
                "%s 부터 %d일을 밀어도 영업일이 없다. holiday 표를 본다".formatted(date, MAX_SHIFT_DAYS));
    }

    /**
     * 그날 다음날부터 세서 {@code days} 번째 영업일.
     *
     * <p><b>초일을 안 넣는다.</b> 「3영업일 이내」는 요청한 날 다음날부터 세는 것이고
     * (`D2` R5, 전자상거래법 제18조제2항), 초일을 넣으면 밤에 들어온 요청이 하루를 잃는다.
     *
     * <p>{@link #nextBusinessDay} 와 다르다. 그쪽은 <b>기한의 말일이 쉬는 날일 때 미는 것</b>이고
     * 이쪽은 <b>영업일만 세는 것</b>이다 — 3영업일은 주말이 끼면 역일로 닷새가 된다.
     */
    public LocalDate plusBusinessDays(LocalDate from, int days) {
        return plusBusinessDays(from, days, holidaysAround(from, days));
    }

    /** 위와 같은 계산인데 휴일 집합을 받는다(`Q19`). DB 를 모른다. */
    public static LocalDate plusBusinessDays(LocalDate from, int days, Set<LocalDate> holidays) {
        LocalDate moved = from;

        for (int counted = 0; counted < days; counted++) {
            moved = moved.plusDays(1);

            for (int shifted = 0; !isBusinessDay(moved, holidays); shifted++) {
                if (shifted > MAX_SHIFT_DAYS) {
                    throw new IllegalStateException(
                            "%s 부터 %d일을 밀어도 영업일이 없다. holiday 표를 본다"
                                    .formatted(from, MAX_SHIFT_DAYS));
                }
                moved = moved.plusDays(1);
            }
        }
        return moved;
    }

    public boolean isBusinessDay(LocalDate date) {
        return isBusinessDay(date, holidaysAround(date, 0));
    }

    /**
     * 토·일이 아니고 휴일 집합에도 없으면 영업일이다.
     *
     * <p><b>토·일을 집합에 안 담는다</b> — 요일은 날짜에서 나온다. 담으면 같은 사실이 두 곳에 있다.
     */
    public static boolean isBusinessDay(LocalDate date, Set<LocalDate> holidays) {
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(date);
    }

    /**
     * 말일 24시. 기간은 날짜 단위라 시각으로 안 센다(`D10`).
     *
     * <p><b>{@code LocalTime.MAX} 를 안 쓴다.</b> 그건 나노초까지({@code .999999999})인데
     * Postgres {@code timestamptz} 는 마이크로초까지만 담고 나머지를 <b>올린다</b> —
     * 저장되면 말일이 아니라 <b>다음날 {@code 00:00:00}</b> 이 된다.
     *
     * <p>시각으로 비교하는 코드는 그래도 멀쩡해서 늦게 드러난다. 저장된 값을 다시 날짜로
     * 되돌리는 자리만 한 칸 밀리고, 그게 말일이 금요일인 날에만 테스트를 깨뜨렸다(`stack.md`).
     *
     * <p>기한을 박제하는 자리가 둘이 되면서 여기로 왔다 — 청약철회·자동확정(`11-2`)과
     * 환급 기한(`12a`)이 같은 함정을 지난다.
     */
    public static OffsetDateTime endOfDay(LocalDate lastDay) {
        return lastDay.atTime(END_OF_DAY).atZone(ZONE).toOffsetDateTime();
    }

    /**
     * 계산이 훑을 구간의 휴일만 읽는다(`Q19`).
     *
     * <p>{@link #MAX_SHIFT_DAYS} 를 넘어 밀지 않으므로 그만큼만 있으면 된다.
     * 앞쪽으로도 조금 잡는 것은 부르는 쪽이 이미 며칠 더한 날짜를 넘길 수 있어서다.
     * {@code extraDays} 는 {@link #plusBusinessDays} 처럼 <b>세면서 나아가는</b> 계산이 쓴다 —
     * 한 걸음마다 최대 {@code MAX_SHIFT_DAYS} 를 더 밀 수 있다.
     *
     * <p><b>표 전체를 안 읽는다.</b> 임시공휴일이 계속 쌓이는 표라 전체를 들면 해가 갈수록 커진다.
     */
    /** 밖에서 순수 계산을 부를 때 쓸 휴일 집합. {@link OrderDeadlines} 가 이것을 받는다(`Q19`) */
    public Set<LocalDate> holidaysNear(LocalDate date) {
        return holidaysAround(date, 0);
    }

    private Set<LocalDate> holidaysAround(LocalDate date, int extraDays) {
        return Set.copyOf(jdbc.sql(
                        "select holiday_date from holiday where holiday_date between :from and :to")
                .param("from", date.minusDays(MAX_SHIFT_DAYS))
                .param("to", date.plusDays(MAX_SHIFT_DAYS * 2L + extraDays * (MAX_SHIFT_DAYS + 1L)))
                .query(LocalDate.class)
                .list());
    }
}
