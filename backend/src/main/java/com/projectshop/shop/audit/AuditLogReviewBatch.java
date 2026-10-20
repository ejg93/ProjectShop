package com.projectshop.shop.audit;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.RetryableBatch;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 접속기록 점검을 매월 1일 05:30 KST 에 돌리고 회차를 {@code batch_run} 에 남긴다(`Q280b`).
 *
 * <p>무엇을 세고 무엇을 표시하나는 {@link AuditLogReviewService} 가 정하고 이 클래스는 언제 부르나만 안다
 * (`D23` 「배치는 규칙을 갖지 않는다」).
 *
 * <p><b>05:30 인 이유</b>: 정산 마감이 05:00 이다({@code SettlementCloseBatch}). 같은 시각에 두면 둘이 같은 DB 를
 * 동시에 훑는다 — 선후가 걸린 것은 아니라 체인으로 안 묶는다.
 */
@Component
public class AuditLogReviewBatch implements RetryableBatch {

    private static final Logger log = LoggerFactory.getLogger(AuditLogReviewBatch.class);

    /** 카탈로그(`D19`)가 부르는 이름. {@code batch_run.batch_name} 에 그대로 들어간다 */
    static final String BATCH_NAME = "audit_log_review";

    /**
     * 마지막 회차가 표시한 열람자 수. Gauge 는 이 값을 읽기만 한다 — 물을 때마다 세면 지표를 긁는 쪽이 부하가 된다
     * ({@code RefundSweeper} 와 같다).
     */
    private final AtomicLong flaggedCount = new AtomicLong();

    private final AuditLogReviewService reviews;
    private final BatchRuns runs;

    AuditLogReviewBatch(AuditLogReviewService reviews, BatchRuns runs, MeterRegistry meters) {
        this.reviews = reviews;
        this.runs = runs;
        Gauge.builder("shop.audit_log.flagged", flaggedCount, AtomicLong::doubleValue)
                .description("지난 접속기록 점검이 표시한 열람자 수. 한 달에 서로 다른 정보주체를 임계 이상 열었다")
                .register(meters);
    }

    /**
     * 매월 1일 05:30 KST 에 전달을 점검한다.
     *
     * <p>기준일은 <b>전달 말일</b>이다 — 1일에 도니까 어제가 곧 전달 말일이다.
     */
    @Scheduled(cron = "0 30 5 1 * *", zone = "Asia/Seoul")
    public void review() {
        review(LocalDate.now(BusinessCalendar.ZONE).withDayOfMonth(1).minusDays(1));
    }

    /**
     * 기준일을 받아 그 회차를 돌린다. 테스트가 날짜를 통제하려고 쓴다.
     *
     * @param baselineDate 대상 기간의 마지막 날. 그 달 1일부터가 대상이다
     * @return 표시한 열람자 수. 이미 성공했거나 실패했으면 빈 값
     */
    public Optional<BatchRuns.Counts> review(LocalDate baselineDate) {
        return runs.record(BATCH_NAME, baselineDate, () -> {
            List<AuditLogReviewService.ViewerCount> flagged =
                    reviews.review(baselineDate.withDayOfMonth(1), baselineDate.plusDays(1));
            flaggedCount.set(flagged.size());

            log.info("접속기록 점검 배치 끝 기준일={} 표시={}명", baselineDate, flagged.size());
            return BatchRuns.Counts.of(flagged.size());
        });
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public void runFor(LocalDate baselineDate) {
        review(baselineDate);
    }
}
