package com.projectshop.shop.brokerage;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.projectshop.shop.audit.AuditLog;
import com.projectshop.shop.audit.AuditTargetType;
import com.projectshop.shop.auth.PermissionEvaluator;
import com.projectshop.shop.auth.PermissionEvaluator.Target;
import com.projectshop.shop.auth.PermissionResource;
import com.projectshop.shop.error.ErrorCode;
import com.projectshop.shop.error.ShopException;
import com.projectshop.shop.payment.PaymentMethod;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.ListQuery.Paging;

/**
 * 중개 명세 분기 목록과 파일을 {@code brokerage_statement} 와 {@code seller} 에서 읽는다(`Q456`, `D2` R46).
 *
 * <p><b>신원은 내려받는 순간의 {@code seller} 다</b>(2026-11-13 사용자 결정 — 수치만 박제). 그래서 목록이 내는 해시도 지금 파일의 것이고,
 * 제출된 분기만 장부의 해시를 낸다.
 */
@Service
public class BrokerageStatementQuery {

    private final JdbcClient jdbc;
    private final PermissionEvaluator evaluator;
    private final AuditLog auditLog;

    BrokerageStatementQuery(JdbcClient jdbc, PermissionEvaluator evaluator, AuditLog auditLog) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
        this.auditLog = auditLog;
    }

    public StatementPage findAll(long userId, Paging paging) {
        require(userId, "read");
        LocalDate today = LocalDate.now(BusinessCalendar.ZONE);
        List<Statement> items = jdbc.sql("""
                        select s.brokerage_statement_id, s.period_year, s.period_quarter, s.due_date,
                               x.file_sha256,
                               (select count(*) from brokerage_statement_line l
                                 where l.brokerage_statement_id = s.brokerage_statement_id) as line_count,
                               (select coalesce(sum(l.sale_amount), 0) from brokerage_statement_line l
                                 where l.brokerage_statement_id = s.brokerage_statement_id) as total_amount
                          from brokerage_statement s
                          left join brokerage_statement_submission x
                                 on x.brokerage_statement_id = s.brokerage_statement_id
                         order by s.period_year desc, s.period_quarter desc
                         limit :size offset :offset
                        """)
                .param("size", paging.limit())
                .param("offset", paging.offset())
                .query((rs, rowNum) -> {
                    long id = rs.getLong("brokerage_statement_id");
                    String submitted = rs.getString("file_sha256");
                    LocalDate due = rs.getObject("due_date", LocalDate.class);
                    StatementStatus status = submitted != null ? StatementStatus.SUBMITTED
                            : due.isBefore(today) ? StatementStatus.OVERDUE : StatementStatus.OPEN;
                    return new Statement(id, rs.getInt("period_year"), rs.getInt("period_quarter"), due, status,
                            rs.getInt("line_count"), rs.getLong("total_amount"),
                            submitted != null ? submitted : BrokerageStatementFile.sha256Hex(render(id)));
                })
                .list();
        long total = jdbc.sql("select count(*) from brokerage_statement").query(Long.class).single();
        boolean canSubmit = evaluator.decide(userId, PermissionResource.BROKERAGE_STATEMENT, "submit",
                new Target(null, null, null)).allowed();
        return new StatementPage(items, paging.offset(), paging.limit(), total, canSubmit);
    }

    /** 파일을 내려준다. 누가 언제 받았는지 감사 기록을 남긴다 — 셀러 신원이 실린 파일이다 */
    public byte[] file(long userId, long statementId) {
        require(userId, "read");
        requireExists(statementId);
        byte[] file = render(statementId);
        auditLog.record(AuditLog.Kind.OUTCOME, "brokerage_statement.downloaded", userId,
                AuditLog.Target.of(AuditTargetType.BROKERAGE_STATEMENT, statementId), Map.of());
        return file;
    }

    /** 그 분기의 이름 부분 — 파일 이름에 쓴다 */
    String fileName(long statementId) {
        return jdbc.sql("""
                        select period_year || 'Q' || period_quarter from brokerage_statement
                         where brokerage_statement_id = :id
                        """)
                .param("id", statementId)
                .query(String.class)
                .single();
    }

    void requireExists(long statementId) {
        boolean exists = jdbc.sql("select exists(select 1 from brokerage_statement where brokerage_statement_id = :id)")
                .param("id", statementId)
                .query(Boolean.class)
                .single();
        if (!exists) {
            throw new ShopException(ErrorCode.BROKERAGE_STATEMENT_NOT_FOUND);
        }
    }

    /** 지금 신원으로 쓴 파일. 제출이 화면이 본 해시와 견준다 */
    byte[] render(long statementId) {
        List<BrokerageStatementFile.Row> rows = jdbc.sql("""
                        select l.sales_month, l.method, l.sale_count, l.sale_amount, l.seller_id,
                               s.business_reg_no, s.business_name, s.representative_name, s.phone
                          from brokerage_statement_line l
                          join seller s on s.seller_id = l.seller_id
                         where l.brokerage_statement_id = :id
                         order by l.seller_id, l.sales_month, l.method
                        """)
                .param("id", statementId)
                .query((rs, rowNum) -> new BrokerageStatementFile.Row(
                        rs.getObject("sales_month", LocalDate.class),
                        PaymentMethod.of(rs.getString("method")),
                        rs.getInt("sale_count"),
                        rs.getLong("sale_amount"),
                        rs.getLong("seller_id"),
                        rs.getString("business_reg_no"),
                        rs.getString("business_name"),
                        rs.getString("representative_name"),
                        rs.getString("phone")))
                .list();
        return BrokerageStatementFile.render(rows);
    }

    void require(long userId, String action) {
        if (!evaluator.decide(userId, PermissionResource.BROKERAGE_STATEMENT, action, new Target(null, null, null))
                .allowed()) {
            throw new ShopException(ErrorCode.ACCESS_DENIED);
        }
    }

    /** 응답에만 실리는 상태 — 저장하지 않는다. 대문자 스네이크로 나간다(`D5`) */
    public enum StatementStatus {
        /** 기한 전이고 제출 기록이 없다 */
        OPEN,
        /** 기한이 지났는데 제출 기록이 없다 */
        OVERDUE,
        /** 제출 기록이 있다 */
        SUBMITTED
    }

    /**
     * 분기 한 줄.
     *
     * @param fileSha256 제출됐으면 장부의 해시, 아니면 지금 파일의 해시 — 화면의 「제출 기록」 버튼이 이 값을 보낸다
     */
    public record Statement(long brokerageStatementId, int periodYear, int periodQuarter, LocalDate dueDate,
            StatementStatus status, int lineCount, long totalAmount, String fileSha256) {
    }

    /** 목록 — {@code canSubmit} 이 화면의 버튼을 가른다(`D20`) */
    public record StatementPage(List<Statement> items, long offset, int limit, long total, boolean canSubmit) {
    }
}
