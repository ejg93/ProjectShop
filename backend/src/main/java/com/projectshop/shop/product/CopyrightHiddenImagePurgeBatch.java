package com.projectshop.shop.product;

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
 * 저작권 신고로 숨긴 사진을 보존 기한 뒤 비공개 버킷과 표에서 지운다(`Q464`, 시행령 제41~43조 `D2` R42).
 *
 * <p><b>그때가 「실제로 지운다」다</b>({@code media-rules.md} 「남의 것이 올라오면」). 기한 안에는 재개(저작권법 제103조③)를
 * 위해 남긴다. 매일 05:00 KST.
 */
@Component
public class CopyrightHiddenImagePurgeBatch implements RetryableBatch {

    /** 회차 이름. `batch_run.batch_name` 과 수동 실행 경로가 이 글자로 찾는다 */
    static final String BATCH_NAME = "copyright_hidden_purge";

    private static final Logger log = LoggerFactory.getLogger(CopyrightHiddenImagePurgeBatch.class);

    /** 숨김 중인 사진 수. Gauge 는 이 값을 읽기만 한다 */
    private final AtomicLong pending = new AtomicLong();

    private final CopyrightReportService reports;
    private final BatchRuns runs;

    CopyrightHiddenImagePurgeBatch(CopyrightReportService reports, BatchRuns runs, MeterRegistry meters) {
        this.reports = reports;
        this.runs = runs;
        MetricName.COPYRIGHT_REPORT_HIDDEN_IMAGE.gauge(pending, AtomicLong::doubleValue)
                .description("저작권 신고로 숨긴 채 비공개 버킷에 있는 사진 수(저작권법 제103조)")
                .register(meters);
    }

    /** 기준일은 오늘이다 — 오늘까지 기한이 찬 숨김을 지운다 */
    @Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
    public void purge() {
        purge(LocalDate.now(BusinessCalendar.ZONE));
    }

    /**
     * 기준일을 받아 그 회차를 돌린다. 테스트가 날짜를 통제하려고 쓴다.
     *
     * @param baselineDate 이 날 기준으로 숨긴 지 {@link CopyrightReportService#HIDDEN_RETENTION_DAYS} 일이 지난 것을 지운다
     */
    public Optional<BatchRuns.Counts> purge(LocalDate baselineDate) {
        Optional<BatchRuns.Counts> counts = runs.record(BATCH_NAME, baselineDate, () -> {
            int purged = reports.purgeHidden(baselineDate);
            log.info("숨긴 사진 파기 끝 기준일={} 지운 것={}", baselineDate, purged);
            return BatchRuns.Counts.of(purged);
        });
        refreshPending();
        return counts;
    }

    /** 기동 때 숨김 수를 채운다. DB 를 못 읽으면 경고만 남기고 앞 값을 둔다 — 지표 하나로 앱이 안 뜨면 안 된다 */
    @EventListener(ApplicationReadyEvent.class)
    void refreshPending() {
        try {
            pending.set(reports.countHidden());
        } catch (DataAccessException e) {
            log.warn("숨긴 사진 지표를 못 채웠다 — 다음 회차까지 앞 값이다", e);
        }
    }

    @Override
    public String batchName() {
        return BATCH_NAME;
    }

    @Override
    public void runFor(LocalDate baselineDate) {
        purge(baselineDate);
    }
}
