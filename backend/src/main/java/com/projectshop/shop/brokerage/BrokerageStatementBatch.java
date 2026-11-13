package com.projectshop.shop.brokerage;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.metric.MetricName;
import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.RetryableBatch;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 끝난 직전 분기의 중개 명세가 없으면 매일 06:00 KST 에 세운다(`Q455`, 부가가치세법 제75조 `D2` R46).
 *
 * <p><b>분기 첫날에만 돌지 않는다.</b> 「끝난 직전 분기인데 명세가 없다」가 고르는 조건이자 처리 조건이라
 * (`batch-catalog.md` 「재실행 안전성」) 분기 첫날 서버가 죽어 있어도 다음 날 선다. 이미 있으면 아무것도 안 한다.
 *
 * <p><b>지표 {@code shop.brokerage_statement.overdue}</b> — 기한이 지났는데 제출 기록이 없는 분기 수. Gauge 는 회차가 적어 둔 값을
 * 읽기만 한다({@code AuditLogReviewBatch} 와 같은 꼴). 기동 때도 한 번 채운다.
 */
@Component
public class BrokerageStatementBatch implements RetryableBatch {

    /** 회차 이름. `batch_run.batch_name` 과 수동 실행 경로가 이 글자로 찾는다 */
    static final String BATCH_NAME = "brokerage_statement";

    private static final Logger log = LoggerFactory.getLogger(BrokerageStatementBatch.class);

    /** 연체 분기 수. Gauge 는 이 값을 읽기만 한다 */
    private final AtomicLong overdue = new AtomicLong();

    private final BrokerageStatementService statements;
    private final BatchRuns runs;

    BrokerageStatementBatch(BrokerageStatementService statements, BatchRuns runs, MeterRegistry meters) {
        this.statements = statements;
        this.runs = runs;
        MetricName.BROKERAGE_STATEMENT_OVERDUE.gauge(overdue, AtomicLong::doubleValue)
                .description("제출 기한이 지났는데 제출 기록이 없는 중개 명세 분기 수(부가가치세법 제75조)")
                .register(meters);
    }

    /** 기준일은 어제다 — 오늘 06:00 에 돌아 어제까지 끝난 분기를 본다 */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void close() {
        close(LocalDate.now(BusinessCalendar.ZONE).minusDays(1));
    }

    /**
     * 기준일을 받아 그 회차를 돌린다. 테스트가 날짜를 통제하려고 쓴다.
     *
     * @param baselineDate 어제. 그다음 날이 속한 분기의 앞 분기를 본다
     */
    public Optional<BatchRuns.Counts> close(LocalDate baselineDate) {
        Optional<BatchRuns.Counts> counts = runs.record(BATCH_NAME, baselineDate, () -> {
            int created = statements.closeMissing(baselineDate.plusDays(1));
            log.info("중개 명세 배치 끝 기준일={} 새 분기={}", baselineDate, created);
            return BatchRuns.Counts.of(created);
        });
        refreshOverdue();
        return counts;
    }

    /** 기동 때 연체 수를 채운다. DB 를 못 읽으면 경고만 남기고 앞 값을 둔다 — 지표 하나로 앱이 안 뜨면 안 된다 */
    @EventListener(ApplicationReadyEvent.class)
    void refreshOverdue() {
        try {
            overdue.set(statements.countOverdue(LocalDate.now(BusinessCalendar.ZONE)));
        } catch (DataAccessException e) {
            log.warn("중개 명세 연체 지표를 못 채웠다 — 다음 회차까지 앞 값이다", e);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public void runFor(LocalDate baselineDate) {
        close(baselineDate);
    }
}
