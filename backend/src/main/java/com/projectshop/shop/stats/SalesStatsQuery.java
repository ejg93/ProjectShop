package com.projectshop.shop.stats;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.BusinessCalendar;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 날짜별 매출 합계를 본다(`41`).
 *
 * <p><b>집계된 날은 표에서, 안 된 날은 원장에서 센다.</b> 「안 된 날」은 오늘과 <b>배치가 성공하지 않은 날</b>이다 —
 * 날짜로만 가르면(어제까지는 표) 배치가 한 번 실패한 날이 0 으로 보이고, 그 0 은 아무것도 안 깨뜨린 채로 틀린다.
 * 배치가 생기기 전의 날도 같은 길로 원장에서 센다. 두 길이 같은 식을 쓴다({@link DailySalesService}).
 *
 * <p><b>스코프를 합계에 섞는 자리다</b>(`D14`). 목록이 아니라 합이라 행마다 판정할 수 없고, <b>어느 셀러를 더하나를
 * 조건이 정한다</b> — 틀리면 남의 매출이 내 합에 섞이는데 숫자 하나라 눈으로 못 가린다({@code RefundQuery} 와 같은 모양).
 */
@Service
public class SalesStatsQuery {

    private static final String RESOURCE = "sales_stats";
    private static final String READ = "read";

    /** 한 번에 볼 수 있는 가장 긴 기간(일). 1년이면 작년 같은 달과 견줄 수 있다 */
    static final int MAX_DAYS = 366;

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final DailySalesService sales;
    private final BatchRuns runs;

    SalesStatsQuery(JdbcClient jdbc, PermissionEvaluator evaluator, DailySalesService sales, BatchRuns runs) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.sales = sales;
        this.runs = runs;
    }

    /**
     * 하루치 합. 매출이 없는 날도 0 으로 한 줄이 선다 — 빈 날을 빼면 화면이 날짜를 다시 채워야 한다.
     *
     * @param netAmount 결제에서 환불을 뺀 것. 둘이 같은 축이라(할인 뒤, 배송비 제외) 뺄 수 있다({@code V102})
     */
    @Schema(name = "SalesStatsDay")
    public record Day(LocalDate salesDate, int orderCount, int soldQuantity, long paidAmount, int refundCount,
            long refundedAmount, long netAmount) {
    }

    /** 기간 합 */
    @Schema(name = "SalesStatsTotal")
    public record Total(int orderCount, int soldQuantity, long paidAmount, int refundCount, long refundedAmount,
            long netAmount) {
    }

    /**
     * @param to 이날 전까지(제외). 감사 로그와 같이 끝을 연다 — 기간을 이어 붙일 때 하루가 겹치지 않는다
     */
    @Schema(name = "SalesStats")
    public record Report(LocalDate from, LocalDate to, List<Day> days, Total total) {
    }

    private record Visible(boolean everything, Set<Long> sellers) {

        boolean covers(long sellerId) {
            return everything || sellers.contains(sellerId);
        }
    }

    public Report find(long viewerId, LocalDate from, LocalDate to) {
        long length = ChronoUnit.DAYS.between(from, to);
        if (length < 1 || length > MAX_DAYS) {
            throw new ShopException(ErrorCode.STATS_RANGE_INVALID,
                    "기간은 1일부터 %d일까지다: %s ~ %s".formatted(MAX_DAYS, from, to));
        }
        Visible visible = visibleFor(viewerId);

        Map<LocalDate, Sum> sums = new TreeMap<>();
        Set<LocalDate> aggregated = runs.succeededBetween(DailySalesBatch.BATCH_NAME, from, to.minusDays(1));
        addStored(sums, visible, aggregated);
        addLive(sums, visible, from, to, aggregated);

        List<Day> days = new ArrayList<>();
        Sum total = new Sum();
        for (LocalDate day = from; day.isBefore(to); day = day.plusDays(1)) {
            Sum sum = sums.getOrDefault(day, new Sum());
            days.add(sum.toDay(day));
            total.add(sum);
        }
        return new Report(from, to, days, total.toTotal());
    }

    /** 배치가 성공한 날의 줄을 표에서 더한다 */
    private void addStored(Map<LocalDate, Sum> sums, Visible visible, Set<LocalDate> aggregated) {
        if (aggregated.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        select seller_id, sales_date, order_count, sold_quantity, paid_amount,
                               refund_count, refunded_amount
                          from seller_daily_sales
                         where sales_date = any(cast(:days as date[]))
                           and (:everything or seller_id = any(:sellers))
                        """)
                // PgJDBC 가 `LocalDate[]` 를 배열로 못 묶는다 — 글자로 넘기고 DB 가 날짜로 읽는다.
                .param("days", aggregated.stream().map(LocalDate::toString).toArray(String[]::new))
                .param("everything", visible.everything())
                .param("sellers", visible.sellers().toArray(Long[]::new))
                .query((RowCallbackHandler) rs -> sums.computeIfAbsent(rs.getObject("sales_date", LocalDate.class),
                                key -> new Sum())
                        .add(rs.getInt("order_count"), rs.getInt("sold_quantity"), rs.getLong("paid_amount"),
                                rs.getInt("refund_count"), rs.getLong("refunded_amount")));
    }

    /**
     * 표에 없는 날(오늘·배치가 성공 안 한 날·배치 전의 날)을 원장에서 센다.
     *
     * <p>내일 이후는 안 센다 — 원장에 아무것도 없어서 질의만 헛돈다.
     */
    private void addLive(Map<LocalDate, Sum> sums, Visible visible, LocalDate from, LocalDate to,
            Set<LocalDate> aggregated) {
        LocalDate tomorrow = LocalDate.now(BusinessCalendar.ZONE).plusDays(1);
        LocalDate end = to.isBefore(tomorrow) ? to : tomorrow;
        List<LocalDate> missing = from.datesUntil(end).filter(day -> !aggregated.contains(day)).toList();
        if (missing.isEmpty()) {
            return;
        }

        Set<LocalDate> wanted = Set.copyOf(missing);
        sales.tally(missing.getFirst(), missing.getLast().plusDays(1)).stream()
                .filter(day -> wanted.contains(day.salesDate()) && visible.covers(day.sellerId()))
                .forEach(day -> sums.computeIfAbsent(day.salesDate(), key -> new Sum())
                        .add(day.orderCount(), day.soldQuantity(), day.paidAmount(), day.refundCount(),
                                day.refundedAmount()));
    }

    /** 날짜 하나 또는 기간 전체를 더해 가는 자리. 셀러 여럿의 줄이 한 날로 모인다 */
    private static final class Sum {
        private int orderCount;
        private int soldQuantity;
        private long paidAmount;
        private int refundCount;
        private long refundedAmount;

        void add(int orders, int quantity, long paid, int refunds, long refunded) {
            orderCount += orders;
            soldQuantity += quantity;
            paidAmount += paid;
            refundCount += refunds;
            refundedAmount += refunded;
        }

        void add(Sum other) {
            add(other.orderCount, other.soldQuantity, other.paidAmount, other.refundCount, other.refundedAmount);
        }

        Day toDay(LocalDate day) {
            return new Day(day, orderCount, soldQuantity, paidAmount, refundCount, refundedAmount,
                    paidAmount - refundedAmount);
        }

        Total toTotal() {
            return new Total(orderCount, soldQuantity, paidAmount, refundCount, refundedAmount,
                    paidAmount - refundedAmount);
        }
    }

    /**
     * 판정 결과에서 더할 셀러를 읽는다. <b>판정 로직을 다시 쓰지 않는다</b>({@code SettlementQuery} 와 같은 모양).
     *
     * <p>하나도 없으면 403 이다 — 빈 합(0원)을 주면 「매출이 없다」와 「못 본다」가 안 갈린다.
     */
    private Visible visibleFor(long viewerId) {
        // 남의 것 하나를 물어본다. all 스코프에서만 덮인다.
        if (evaluator.decide(viewerId, RESOURCE, READ, Target.ofSeller(-1L)).allowed()) {
            return new Visible(true, Set.of());
        }

        Set<Long> sellers = jdbc.sql("select seller_id from seller_member where user_id = :id")
                .param("id", viewerId)
                .query(Long.class)
                .set()
                .stream()
                .filter(sellerId -> evaluator.decide(viewerId, RESOURCE, READ, Target.ofSeller(sellerId)).allowed())
                .collect(Collectors.toUnmodifiableSet());

        if (sellers.isEmpty()) {
            throw new ShopException(ErrorCode.SALES_STATS_FORBIDDEN, "매출 통계를 볼 권한이 없다");
        }
        return new Visible(false, sellers);
    }
}
