package com.projectshop.shop.stats;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.projectshop.shop.support.BatchRuns;
import com.projectshop.shop.support.BusinessCalendar;
import com.projectshop.shop.support.RetryableBatch;

/**
 * 어제의 셀러 매출을 집계 표에 넣는다(`40`).
 *
 * <p><b>기준일이 어제다.</b> 끝난 날만 센다 — 한 날의 합은 그날이 끝나야 안 바뀐다({@link DailySalesService}).
 * 오늘 몫은 조회가 원장에서 센다.
 *
 * <p><b>00:15 인 이유</b>: 자정 직전에 승인된 결제가 커밋되기를 기다린다.
 *
 * <p><b>규칙은 여기 없다</b> — 무엇을 세나는 {@link DailySalesService} 가 정한다(`D23` 「배치는 규칙을 갖지 않는다」).
 */
@Component
public class DailySalesBatch implements RetryableBatch {

    private static final Logger log = LoggerFactory.getLogger(DailySalesBatch.class);

    /** 카탈로그(`D19`)가 부르는 이름. {@code batch_run.batch_name} 에 그대로 들어간다 */
    static final String BATCH_NAME = "daily_sales";

    private final DailySalesService sales;
    private final BatchRuns runs;

    DailySalesBatch(DailySalesService sales, BatchRuns runs) {
        this.sales = sales;
        this.runs = runs;
    }

    @Scheduled(cron = "0 15 0 * * *", zone = "Asia/Seoul")
    public void aggregate() {
        runFor(LocalDate.now(BusinessCalendar.ZONE).minusDays(1));
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    /** 그날 회차를 돌리고 이력을 남긴다. 기준일이 곧 세는 날이다 */
    @Override
    public void runFor(LocalDate baselineDate) {
        runs.record(BATCH_NAME, baselineDate, () -> {
            int sellers = sales.rebuild(baselineDate);
            log.info("일별 매출 집계 끝 기준일={} 셀러={}곳", baselineDate, sellers);
            return BatchRuns.Counts.of(sellers);
        });
    }
}
