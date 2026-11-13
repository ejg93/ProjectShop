package com.projectshop.shop.brokerage;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.audit.AuditTargetType;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.support.BusinessCalendar;

/**
 * 판매·결제 중개 명세를 분기마다 {@code brokerage_statement} 와 그 줄로 세운다(`Q455`, 부가가치세법 제75조 `D2` R46).
 *
 * <p><b>금액은 셀러 공급 총액이다</b>(2026-11-13 사용자 결정) — 셀러 묶음마다 줄 금액 + 배송비 − 셀러 부담 할인. 몰 부담 할인은
 * 셀러 공급가를 안 줄여서 안 뺀다. <b>환불은 안 뺀다</b> — 별지 제48호서식에 그 칸이 없다. 승인 시각의 한국 월로 묶는다.
 *
 * <p><b>한 번 선 분기는 다시 안 센다.</b> 머리 행이 분기당 하나라(`brokerage_statement_period_unique`) 두 번째 부름은 아무것도 안 한다 —
 * 제출 뒤에 금액이 바뀌면 낸 것과 장부가 갈린다.
 */
@Service
public class BrokerageStatementService {

    /** 제출 기한 — 분기 말일의 다음 달 15일. 부가가치세법 시행령 제121조제1항(`D2` R46) */
    private static final int DUE_DAY_OF_MONTH = 15;

    /**
     * 셀러 묶음마다 먼저 합친다. 바로 셀러·월·수단으로 묶으면 배송비가 항목 수만큼 곱해진다.
     * 쿠폰은 주문당 하나다(`coupon_issue.used_order_id` — {@code DailySalesService} 와 같은 조인).
     */
    private static final String TALLY = """
            insert into brokerage_statement_line
                   (brokerage_statement_id, seller_id, sales_month, method, sale_count, sale_amount)
            select :statementId, b.seller_id, b.sales_month, b.method, count(*), sum(b.amount)
              from (select so.seller_order_id,
                           so.seller_id,
                           date_trunc('month', p.created_at at time zone :zone)::date as sales_month,
                           p.method,
                           sum(oi.line_amount
                               - case when c.bearer = 'seller' then oi.discount_amount else 0 end)
                               + so.shipping_fee as amount
                      from payment p
                      join seller_order so on so.order_id = p.order_id
                      join order_item oi   on oi.seller_order_id = so.seller_order_id
                      left join coupon_issue ci on ci.used_order_id = so.order_id
                      left join coupon c        on c.coupon_id = ci.coupon_id
                     where p.status = 'approved'
                       and p.created_at >= :from and p.created_at < :to
                     group by so.seller_order_id, so.seller_id, sales_month, p.method, so.shipping_fee) b
             group by b.seller_id, b.sales_month, b.method
            """;

    private final JdbcClient jdbc;
    private final BrokerageStatementQuery query;
    private final AuditLog auditLog;

    BrokerageStatementService(JdbcClient jdbc, BrokerageStatementQuery query, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.query = query;
        this.auditLog = auditLog;
    }

    /**
     * 오늘이 속한 분기의 앞 분기에 명세가 없으면 세운다.
     *
     * @return 새로 세운 분기 수(0 또는 1)
     */
    @Transactional
    public int closeMissing(LocalDate today) {
        Quarter quarter = Quarter.before(today);
        Long statementId = jdbc.sql("""
                        insert into brokerage_statement (period_year, period_quarter, period_start, period_end, due_date)
                        values (:year, :quarter, :start, :end, :due)
                        on conflict (period_year, period_quarter) do nothing
                        returning brokerage_statement_id
                        """)
                .param("year", quarter.start().getYear())
                .param("quarter", quarter.number())
                .param("start", quarter.start())
                .param("end", quarter.end())
                .param("due", quarter.end().plusMonths(1).withDayOfMonth(DUE_DAY_OF_MONTH))
                .query(Long.class)
                .optional()
                .orElse(null);
        if (statementId == null) {
            return 0;
        }
        jdbc.sql(TALLY)
                .param("statementId", statementId)
                .param("zone", BusinessCalendar.ZONE.getId())
                .param("from", startOf(quarter.start()))
                .param("to", startOf(quarter.end().plusDays(1)))
                .update();
        return 1;
    }

    /**
     * 국세청에 냈다고 기록한다(`Q456`). 데모라 실제 제출은 안 한다(2026-11-13 사용자 결정) — 절차와 증빙 자리를 세운다.
     *
     * <p><b>화면이 본 해시를 같이 받는다.</b> 신원은 내려받을 때 붙어서, 그 사이 셀러가 상호를 바꾸면 낸 파일과 지금 파일이 다르다 —
     * 다르면 409 로 돌려 다시 내려받게 한다. 분기당 기록 하나는 기본키가 막는다.
     */
    @Transactional
    public void submit(long actorUserId, long statementId, String fileSha256) {
        query.require(actorUserId, "submit");
        query.requireExists(statementId);
        if (!BrokerageStatementFile.sha256Hex(query.render(statementId)).equals(fileSha256)) {
            throw new ShopException(ErrorCode.BROKERAGE_STATEMENT_FILE_CHANGED);
        }
        try {
            jdbc.sql("""
                            insert into brokerage_statement_submission
                                   (brokerage_statement_id, submitted_by_user_id, file_sha256)
                            values (:id, :actor, :sha)
                            """)
                    .param("id", statementId)
                    .param("actor", actorUserId)
                    .param("sha", fileSha256)
                    .update();
        } catch (DuplicateKeyException e) {
            throw new ShopException(ErrorCode.BROKERAGE_STATEMENT_ALREADY_SUBMITTED);
        }
        auditLog.record(AuditLog.Kind.OUTCOME, "brokerage_statement.submitted", actorUserId,
                AuditLog.Target.of(AuditTargetType.BROKERAGE_STATEMENT, statementId), Map.of());
    }

    /** 기한이 지났는데 제출 기록이 없는 분기 수 */
    public int countOverdue(LocalDate today) {
        return jdbc.sql("""
                        select count(*)
                          from brokerage_statement s
                         where s.due_date < :today
                           and not exists (select 1 from brokerage_statement_submission x
                                            where x.brokerage_statement_id = s.brokerage_statement_id)
                        """)
                .param("today", today)
                .query(Integer.class)
                .single();
    }

    private OffsetDateTime startOf(LocalDate day) {
        return day.atStartOfDay(BusinessCalendar.ZONE).toOffsetDateTime();
    }

    /** 분기 — 번호(1~4)와 첫날·끝날 */
    record Quarter(int number, LocalDate start, LocalDate end) {

        /** 그날이 속한 분기의 바로 앞 분기 */
        static Quarter before(LocalDate day) {
            LocalDate thisStart = day.withDayOfMonth(1).withMonth(((day.getMonthValue() - 1) / 3) * 3 + 1);
            LocalDate start = thisStart.minusMonths(3);
            return new Quarter((start.getMonthValue() - 1) / 3 + 1, start, thisStart.minusDays(1));
        }
    }
}
