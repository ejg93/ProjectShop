package com.projectshop.shop.stats;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.support.BusinessCalendar;

/**
 * 셀러의 날짜별 매출을 원장에서 센다(`40`).
 *
 * <p><b>세는 식이 한 곳이다</b>({@link #TALLY}). 배치가 어제를 {@code seller_daily_sales} 에 채울 때와 조회가 오늘을
 * 원장에서 셀 때(`41`) 같은 식을 쓴다 — 둘로 쓰면 어제까지와 오늘이 다른 규칙으로 세어져서 자정마다 숫자가 튄다.
 *
 * <p><b>결제는 승인된 날, 환불은 승인된 날에 든다.</b> 두 시각 다 한 번 박히고 안 움직여서, 한 날의 합은 그날이
 * 끝나면 안 바뀐다 — 그래서 지난 날을 다시 안 센다(`V102`).
 */
@Service
public class DailySalesService {

    /**
     * 원장에서 셀러×날짜로 센다. {@code :from}·{@code :to} 는 한국 자정의 시각이다.
     *
     * <p>시각으로 자르고 날짜로 묶는다 — 거꾸로 하면 {@code created_at} 에 식을 씌워서 범위 조건이 인덱스를 못 탄다.
     */
    private static final String TALLY = """
            with paid as (
                select so.seller_id,
                       (p.created_at at time zone :zone)::date as sales_date,
                       count(distinct so.seller_order_id)    as order_count,
                       sum(oi.quantity)                       as sold_quantity,
                       sum(oi.line_amount - oi.discount_amount) as paid_amount,
                       sum(case when c.bearer = 'mall' then oi.discount_amount else 0 end) as mall_discount_amount
                  from payment p
                  join seller_order so on so.order_id = p.order_id
                  join order_item oi   on oi.seller_order_id = so.seller_order_id
                  left join coupon_issue ci on ci.used_order_id = so.order_id
                  left join coupon c        on c.coupon_id = ci.coupon_id
                 where p.status = 'approved'
                   and p.created_at >= :from and p.created_at < :to
                 group by 1, 2
            ),
            refunded as (
                select so.seller_id,
                       (r.decided_at at time zone :zone)::date as sales_date,
                       count(distinct r.refund_id)            as refund_count,
                       sum(ri.amount)                         as refunded_amount,
                       sum(case when c.bearer = 'mall' then ri.discount_refund else 0 end)
                                                              as refunded_mall_discount_amount
                  from refund r
                  join seller_order so on so.seller_order_id = r.seller_order_id
                  join refund_item ri  on ri.refund_id = r.refund_id
                  left join coupon_issue ci on ci.used_order_id = so.order_id
                  left join coupon c        on c.coupon_id = ci.coupon_id
                 where r.status = 'approved'
                   and r.decided_at >= :from and r.decided_at < :to
                 group by 1, 2
            )
            select coalesce(p.seller_id, r.seller_id)   as seller_id,
                   coalesce(p.sales_date, r.sales_date) as sales_date,
                   coalesce(p.order_count, 0)           as order_count,
                   coalesce(p.sold_quantity, 0)         as sold_quantity,
                   coalesce(p.paid_amount, 0)           as paid_amount,
                   coalesce(r.refund_count, 0)          as refund_count,
                   coalesce(r.refunded_amount, 0)       as refunded_amount,
                   coalesce(p.mall_discount_amount, 0)  as mall_discount_amount,
                   coalesce(r.refunded_mall_discount_amount, 0) as refunded_mall_discount_amount
              from paid p
              full join refunded r on r.seller_id = p.seller_id and r.sales_date = p.sales_date
            """;

    private final JdbcClient jdbc;

    DailySalesService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 셀러 하루치.
     *
     * @param paidAmount     고객이 상품에 낸 돈. 쿠폰 할인 뒤고 배송비는 안 든다
     * @param refundedAmount 그날 승인된 환불. {@code paidAmount} 와 같은 축이라 빼면 순매출이다
     * @param mallDiscountAmount         결제된 줄의 할인 중 몰이 문 몫(`Q197`). 더하면 셀러 매출 — 정산서의 축이다
     * @param refundedMallDiscountAmount 환불이 되돌린 할인 중 몰이 문 몫. 셀러 매출의 환불 축이다
     */
    public record Day(long sellerId, LocalDate salesDate, int orderCount, int soldQuantity, long paidAmount,
            int refundCount, long refundedAmount, long mallDiscountAmount, long refundedMallDiscountAmount) {
    }

    /**
     * {@code from} 부터 {@code to} 전날까지를 원장에서 센다. 빈 날은 줄이 없다.
     */
    public List<Day> tally(LocalDate from, LocalDate to) {
        return jdbc.sql(TALLY + " order by seller_id, sales_date")
                .param("zone", BusinessCalendar.ZONE.getId())
                .param("from", startOf(from))
                .param("to", startOf(to))
                .query((rs, rowNum) -> new Day(
                        rs.getLong("seller_id"),
                        rs.getObject("sales_date", LocalDate.class),
                        rs.getInt("order_count"),
                        rs.getInt("sold_quantity"),
                        rs.getLong("paid_amount"),
                        rs.getInt("refund_count"),
                        rs.getLong("refunded_amount"),
                        rs.getLong("mall_discount_amount"),
                        rs.getLong("refunded_mall_discount_amount")))
                .list();
    }

    /**
     * 하루를 원장에서 다시 세어 표에 넣는다. <b>지우고 넣는다</b> — 두 번 돌아도 결과가 같다(`D19` 「재실행 안전성」).
     *
     * <p>덮어쓰기(upsert)로 안 한 이유는 <b>빈 날</b>이다. 다시 센 결과에서 셀러 하나가 빠지면 덮어쓰기는 그 줄을
     * 못 지워서 옛 숫자가 남는다.
     *
     * @return 넣은 줄 수. 그날 매출이 있던 셀러 수다
     */
    @Transactional
    public int rebuild(LocalDate day) {
        jdbc.sql("delete from seller_daily_sales where sales_date = :day")
                .param("day", day)
                .update();

        return jdbc.sql("""
                        insert into seller_daily_sales (seller_id, sales_date, order_count, sold_quantity,
                                                        paid_amount, refund_count, refunded_amount,
                                                        mall_discount_amount, refunded_mall_discount_amount)
                        """ + TALLY)
                .param("zone", BusinessCalendar.ZONE.getId())
                .param("from", startOf(day))
                .param("to", startOf(day.plusDays(1)))
                .update();
    }

    private static OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(BusinessCalendar.ZONE).toOffsetDateTime();
    }
}
